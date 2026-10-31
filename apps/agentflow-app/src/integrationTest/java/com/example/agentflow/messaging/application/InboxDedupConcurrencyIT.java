package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Acceptance test for the inbox dedup helper (S06-10) on a <b>real PostgreSQL</b> (Testcontainers):
 * {@code CONSUMERS} concurrent deliveries of the <b>same</b> {@code (consumer_name, event_id)} pair
 * must create <b>exactly one</b> domain transition &mdash; only the first claim proceeds, the rest
 * are rejected by the composite {@code PRIMARY KEY} and skip the business change.
 *
 * <p>Proven end-to-end, not by mock:
 *
 * <ul>
 *   <li>a real Flyway-migrated schema ({@code inbox_messages});
 *   <li>a real {@link JdbcTemplate} against the container;
 *   <li>{@code CONSUMERS} independent {@link InboxDedupService} consumers racing on the same pair,
 *       each with its own {@link TransactionTemplate} (its own transaction);
 *   <li>a thread-safe counter of how many times the business {@link InboxConsumerAction} ran.
 * </ul>
 *
 * <p>PostgreSQL blocks a second {@code INSERT} of the same composite key on the unique index until
 * the first transaction commits, so the second delivery then fails the constraint and returns
 * {@code false} from {@link InboxMessageRepository#claim} &mdash; it runs neither the business
 * change nor its side effect.
 */
@Testcontainers
class InboxDedupConcurrencyIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static final int CONSUMERS = 4;
  private static final String CONSUMER = "agent:run:step";

  private JdbcTemplate jdbc;

  @BeforeEach
  void bootRealPostgresAndSchema() {
    // Flyway creates the inbox_messages schema exactly as CI does (S06-04 migration).
    org.flywaydb.core.Flyway flyway =
        org.flywaydb.core.Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:migrations")
            .load();
    flyway.migrate();
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
  }

  /**
   * S06-10 acceptance criterion (restated): concurrent duplicate delivery creates exactly one
   * domain transition. {@code CONSUMERS} consumers race to consume the SAME {@code (consumer_name,
   * event_id)} pair; only the first claim runs the business {@link InboxConsumerAction}, so the
   * transition runs exactly once and the row is {@code PROCESSED} exactly once.
   */
  @Test
  void concurrent_duplicate_delivery_creates_exactly_one_domain_transition()
      throws InterruptedException {
    UUID eventId = UUID.randomUUID();
    AtomicInteger transitions = new AtomicInteger();

    // One consumer instance per racing thread, each with its own transaction (the acceptance
    // criterion is literally about two or more concurrent deliveries of the same pair).
    InboxDedupService[] consumers = new InboxDedupService[CONSUMERS];
    for (int c = 0; c < CONSUMERS; c++) {
      DriverManagerDataSource ds =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      consumers[c] =
          new InboxDedupService(
              new InboxMessageRepository(new JdbcTemplate(ds)),
              new TransactionTemplate(new DataSourceTransactionManager(ds)),
              1);
    }

    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(CONSUMERS);
    ExecutorService pool = Executors.newFixedThreadPool(CONSUMERS);

    for (int c = 0; c < CONSUMERS; c++) {
      final InboxDedupService consumer = consumers[c];
      pool.submit(
          () -> {
            try {
              start.await();
              consumer.consume(
                  CONSUMER,
                  InboxMessage.claiming(CONSUMER, eventId),
                  () -> {
                    transitions.incrementAndGet(); // the domain transition the inbox dedup guards
                    return "prior=RUNNING new=SUCCEEDED";
                  });
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    pool.shutdownNow();

    // exactly one domain transition ran even though CONSUMERS deliveries raced on the same pair
    assertThat(transitions.get()).isEqualTo(1);
    // the inbox row for the pair is created exactly once and is PROCESSED (no duplicate row, no
    // FAILED, no PENDING left)
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_messages WHERE event_id = ?", Integer.class, eventId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_messages WHERE event_id = ? AND status = 'PROCESSED'",
                Integer.class,
                eventId))
        .isEqualTo(1);
  }
}
