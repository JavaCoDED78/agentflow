package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Acceptance test for the polling outbox publisher (S06-07) on a <b>real PostgreSQL</b>
 * (Testcontainers): two or more publisher instances must never lose a pending row and must never
 * deliver the same row twice.
 *
 * <p>Proven end-to-end, not by mock:
 *
 * <ul>
 *   <li>a real Flyway-migrated schema ({@code tenants} + {@code outbox_events});
 *   <li>a real {@link JdbcTemplate} against the container;
 *   <li>N publisher threads racing on the same rows concurrently;
 *   <li>a thread-safe {@link MessageDeliverer} that counts deliveries per event id.
 * </ul>
 *
 * <p>Because the claim is a conditional {@code UPDATE ... WHERE status = 'PENDING'}, every row is
 * claimed by exactly one publisher, so N threads draining M rows deliver each envelope exactly once
 * regardless of how their polls interleave.
 */
@Testcontainers
class OutboxPublisherConcurrencyIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static final int PUBLISHERS = 4;
  private static final int EVENTS = 200;
  private static final int MAX_POLLS_PER_PUBLISHER = 100;

  private OutboxEventRepository repository;
  private MessageDeliverer deliverer;
  private Map<UUID, Integer> deliveries;
  private JdbcTemplate jdbc;

  @BeforeEach
  void bootRealPostgresAndPublisher() {
    // Flyway creates the schema (tenants + outbox_events + the pending index) exactly as CI does.
    assertThatNoException()
        .isThrownBy(
            () -> {
              // Same programmatic API + migration location as the app's FlywayMigrate entry point,
              // so the test migrates exactly the schema the running service expects.
              org.flywaydb.core.Flyway flyway =
                  org.flywaydb.core.Flyway.configure()
                      .dataSource(
                          postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                      .locations("classpath:migrations")
                      .load();
              flyway.migrate();
            });

    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(ds);
    repository = new OutboxEventRepository(jdbc);

    // A valid tenant row (outbox_events.tenant_id has a FK to tenants(id)).
    jdbc.update("INSERT INTO tenants (id, slug) VALUES (?, ?)", UUID.randomUUID(), "tenant-it");

    // Initialize the registry before the lambda captures it.
    deliveries = new ConcurrentHashMap<>();
    deliverer =
        event -> {
          deliveries.merge(event.eventId(), 1, Integer::sum);
          return MessageDeliverer.DeliverResult.DELIVERED;
        };
  }

  /**
   * Insert {@code EVENTS} pending rows owned by the test tenant and return their event ids, so the
   * publishers have real work to race over.
   */
  private List<UUID> seedPendingRows() {
    UUID tenant = jdbc.queryForObject("SELECT id FROM tenants", UUID.class);
    return IntStream.range(0, EVENTS)
        .mapToObj(
            i -> {
              UUID id = UUID.randomUUID();
              UUID eventId = UUID.randomUUID();
              UUID run = UUID.randomUUID();
              repository.insert(
                  OutboxEvent.pending(
                      id,
                      eventId,
                      "workflow.run.requested.v1",
                      "agentflow.workflow.run-requested.v1",
                      "workflow_run",
                      run,
                      tenant,
                      "{\"runId\":\"" + run + "\"}",
                      null));
              return eventId;
            })
        .collect(Collectors.toList());
  }

  @Test
  void n_publishers_drain_all_rows_and_deliver_each_exactly_once() throws InterruptedException {
    List<UUID> eventIds = seedPendingRows();
    AgentFlowProperties props = new AgentFlowProperties();

    // One publisher instance per thread (the acceptance criterion is literally about two or more
    // publisher instances racing), each with its own delivery counter; a single shared registry
    // keyed by event id proves the exactly-once invariant across all of them.
    OutboxPublisherService[] publishers = new OutboxPublisherService[PUBLISHERS];
    for (int p = 0; p < PUBLISHERS; p++) {
      publishers[p] = new OutboxPublisherService(repository, deliverer, null, props);
    }
    long[] deliveriesPerPublisher = new long[PUBLISHERS];

    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(PUBLISHERS);
    ExecutorService pool = Executors.newFixedThreadPool(PUBLISHERS);

    for (int p = 0; p < PUBLISHERS; p++) {
      final int publisherId = p;
      pool.submit(
          () -> {
            try {
              start.await();
              for (int i = 0; i < MAX_POLLS_PER_PUBLISHER && !drained(); i++) {
                int published = publishers[publisherId].pollOnce();
                if (published > 0) {
                  deliveriesPerPublisher[publisherId] += published;
                }
              }
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

    // every pending row was delivered, and exactly once across all publishers — no loss, no dup
    assertThat(deliveries).hasSize(EVENTS);
    long total = 0;
    for (UUID eventId : eventIds) {
      assertThat(deliveries.get(eventId))
          .as("event %s delivered exactly once", eventId)
          .isEqualTo(1);
      total += deliveries.get(eventId);
    }
    assertThat(total).isEqualTo(EVENTS);
    // no row is left PENDING or terminal: the outbox fully drained
    assertThat(repository.selectForClaim(1000)).isEmpty();
    // more than one publisher instance made progress — they raced instead of serialising onto one
    long activePublishers = 0;
    for (long count : deliveriesPerPublisher) {
      if (count > 0) activePublishers++;
    }
    assertThat(activePublishers)
        .as("multiple publisher instances drained concurrently", activePublishers)
        .isGreaterThanOrEqualTo(2);
  }

  /** True when nothing is due for publish anymore. */
  private boolean drained() {
    return repository.selectForClaim(1000).isEmpty();
  }
}
