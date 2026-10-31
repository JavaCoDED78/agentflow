package com.example.agentflow.messaging.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.messaging.domain.DlqMessage;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-PostgreSQL integration test for the {@code dlq_messages} path (S06-11). The {@code V3_3_12}
 * migration is applied (together with the rest of {@code classpath:migrations}) against a real
 * {@code pgvector/pgvector:0.8.6-pg18} container, and the {@link DlqMessageRepository} is exercised
 * on it: a record round-trips, the idempotent {@code ON CONFLICT ... DO NOTHING} upsert collapses a
 * duplicate delivery to one row (invariant 3 / ADR-003), and the counts filter as documented. No H2
 * is used as a stand-in for PostgreSQL (S03-08).
 */
@Testcontainers
class DlqMessageRepositoryIT {

  @Container
  static PostgreSQLContainer pg =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.6-pg18"))
          .withDatabaseName("agentflow");

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
            .locations("classpath:migrations")
            .load();
    flyway.migrate();
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
  }

  @BeforeEach
  void clean() {
    // The Testcontainers DB is shared across the @Test methods in this class (Flyway runs once in
    // @BeforeAll), so reset the DLQ table between tests to keep each one isolated.
    jdbc.update("DELETE FROM dlq_messages");
  }

  @Test
  void record_round_trips_through_the_dlq_store() {
    DlqMessageRepository repo = new DlqMessageRepository(jdbc);
    DlqMessage record =
        DlqMessage.deadLetter(
            UUID.randomUUID(),
            "agent-run.v1",
            "agent:run:step",
            DlqMessage.Status.DEAD_LETTER,
            3,
            "IllegalArgumentException: malformed envelope");

    assertThat(repo.record(record)).isTrue();

    Optional<DlqMessage> found =
        repo.findByConsumerAndEvent(record.consumerName(), record.eventId());

    assertThat(found).isPresent();
    DlqMessage row = found.get();
    assertThat(row.eventId()).isEqualTo(record.eventId());
    assertThat(row.topic()).isEqualTo(record.topic());
    assertThat(row.consumerName()).isEqualTo(record.consumerName());
    assertThat(row.status()).isEqualTo(DlqMessage.Status.DEAD_LETTER);
    assertThat(row.attempts()).isEqualTo(3);
    assertThat(row.lastError()).isEqualTo("IllegalArgumentException: malformed envelope");
  }

  @Test
  void a_second_delivery_of_the_same_pair_is_not_recorded_twice() {
    DlqMessageRepository repo = new DlqMessageRepository(jdbc);
    DlqMessage first =
        DlqMessage.deadLetter(
            UUID.randomUUID(),
            "agent-run.v1",
            "agent:run:step",
            DlqMessage.Status.RETRY,
            2,
            "transient");

    assertThat(repo.record(first)).isTrue();
    // A duplicate Kafka delivery (at-least-once, ADR-003) with the same dedup key is collapsed to
    // one row: the upsert returns true (no error) but the row is not rewritten.
    DlqMessage duplicate =
        DlqMessage.deadLetter(
            first.eventId(),
            "agent-run.v1",
            first.consumerName(),
            DlqMessage.Status.RETRY,
            2,
            "transient");

    assertThat(repo.record(duplicate)).isTrue();

    assertThat(repo.countByConsumer(first.consumerName())).isEqualTo(1);
    assertThat(repo.countByConsumerAndStatus(first.consumerName(), DlqMessage.Status.RETRY))
        .isEqualTo(1);
  }

  @Test
  void records_are_counted_per_consumer_and_status() {
    DlqMessageRepository repo = new DlqMessageRepository(jdbc);
    DlqMessage retry =
        DlqMessage.deadLetter(
            UUID.randomUUID(),
            "agent-run.v1",
            "agent:run:step",
            DlqMessage.Status.RETRY,
            1,
            "retry");
    DlqMessage dead =
        DlqMessage.deadLetter(
            UUID.randomUUID(),
            "agent-run.v1",
            "agent:run:step",
            DlqMessage.Status.DEAD_LETTER,
            1,
            "dead");
    DlqMessage other =
        DlqMessage.deadLetter(
            UUID.randomUUID(),
            "agent-run.v1",
            "other:consumer",
            DlqMessage.Status.DEAD_LETTER,
            1,
            "dead");

    repo.record(retry);
    repo.record(dead);
    repo.record(other);

    assertThat(repo.countByConsumer("agent:run:step")).isEqualTo(2);
    assertThat(repo.countByConsumerAndStatus("agent:run:step", DlqMessage.Status.RETRY))
        .isEqualTo(1);
    assertThat(repo.countByConsumerAndStatus("agent:run:step", DlqMessage.Status.DEAD_LETTER))
        .isEqualTo(1);
    assertThat(repo.countByConsumer("other:consumer")).isEqualTo(1);
  }

  @Test
  void find_by_consumer_and_event_is_empty_for_an_unknown_pair() {
    DlqMessageRepository repo = new DlqMessageRepository(jdbc);
    Optional<DlqMessage> found = repo.findByConsumerAndEvent("agent:run:step", UUID.randomUUID());

    assertThat(found).isEmpty();
  }
}
