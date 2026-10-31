package com.example.agentflow.messaging.persistence;

import com.example.agentflow.messaging.domain.DlqMessage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Write / read path for the {@code dlq_messages} table, owned by the {@code messaging} module
 * (S06-11).
 *
 * <p>The single dead-letter appender. {@link #record} is idempotent by the same dedup key as the
 * inbox — the composite {@code UNIQUE (consumer_name, event_id)} with {@code ON CONFLICT ... DO
 * NOTHING} — so a duplicate Kafka delivery (at-least-once, ADR-003) is never dead-lettered twice.
 * The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * (S01-02).
 *
 * <p>The DLQ is keyed by {@code (consumer, event)}, not by tenant (the inbox is not tenant scoped
 * either): tenant isolation is enforced by the consumer, which processes in its own tenant context
 * established from the stored run / verified security context (invariant 1). The {@code event_id}
 * is the SAME id as the outbox event id / envelope eventId, the anchor a consumer re-checks
 * against.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class DlqMessageRepository {

  private static final String TABLE = "dlq_messages";

  private final JdbcTemplate jdbc;

  /**
   * @param jdbc the JDBC read/write path for {@code dlq_messages}
   */
  public DlqMessageRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Append a dead-letter record, idempotently by {@code (consumer_name, event_id)} (invariant 3: a
   * duplicate Kafka delivery is never dead-lettered twice). A second append of the same pair is a
   * no-op &mdash; the original {@code recorded_at} is kept, so the DLQ row is not rewritten.
   *
   * @param record the dead-letter record to append
   * @return {@code true} if a new row was inserted, {@code false} when the pair already existed
   */
  public boolean record(DlqMessage record) {
    // ON CONFLICT (consumer_name, event_id) DO NOTHING makes this idempotent (invariant 3): a
    // duplicate Kafka delivery is never dead-lettered twice, and the original recorded_at is kept.
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, event_id, topic, consumer_name, status, attempts, last_error, recorded_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, now()) "
            + "ON CONFLICT (consumer_name, event_id) DO NOTHING",
        ps -> {
          ps.setObject(1, record.id());
          ps.setObject(2, record.eventId());
          ps.setString(3, record.topic());
          ps.setString(4, record.consumerName());
          ps.setString(5, record.status().name());
          ps.setInt(6, record.attempts());
          ps.setString(7, record.lastError());
        });
    return true;
  }

  /**
   * @param consumerName the consumer half of the dedup key
   * @param eventId the event half of the dedup key
   * @return the DLQ row for the pair, or empty when no record exists
   */
  public Optional<DlqMessage> findByConsumerAndEvent(String consumerName, UUID eventId) {
    List<DlqMessage> rows =
        jdbc.query(
            "SELECT id, event_id, topic, consumer_name, status, attempts, last_error, "
                + "recorded_at FROM "
                + TABLE
                + " WHERE consumer_name = ? AND event_id = ?",
            rowMapper,
            consumerName,
            eventId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * @param consumerName the consumer half of the dedup key
   * @return the number of DLQ rows recorded for the consumer (test / operational visibility)
   */
  public int countByConsumer(String consumerName) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + TABLE + " WHERE consumer_name = ?", Integer.class, consumerName);
  }

  /**
   * @param consumerName the consumer half of the dedup key
   * @param status the disposition to count
   * @return the number of the consumer's DLQ rows in the given disposition
   */
  public int countByConsumerAndStatus(String consumerName, DlqMessage.Status status) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + TABLE + " WHERE consumer_name = ? AND status = ?",
        Integer.class,
        consumerName,
        status.name());
  }

  // A package-private row mapper (S06-11); unit-tested directly in DlqMessageRowMapperTest.
  private final DlqMessageRowMapper rowMapper = new DlqMessageRowMapper();
}
