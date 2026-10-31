package com.example.agentflow.messaging.persistence;

import com.example.agentflow.messaging.domain.InboxMessage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Write / read path for the {@code inbox_messages} table, owned by the {@code messaging} module
 * (S06-04 / S06-10).
 *
 * <p>The single inbox claim + outcome adapter. The {@link #claim} insert is the dedup fence: the
 * composite {@code PRIMARY KEY (consumer_name, event_id)} makes a second insert of the same pair
 * fail the unique constraint, so a duplicate Kafka delivery (at-least-once, ADR-003) re-runs
 * neither the business state change nor its side effect &mdash; only the first claim proceeds.
 * {@link #markProcessed} is fenced to {@code status = 'PENDING'} so a row is completed only once.
 * The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * (S01-02).
 *
 * <p>The inbox is keyed by {@code (consumer, event)}, not by tenant: there is no {@code tenant_id}
 * column, and tenant isolation is enforced by the consumer, which processes in its own tenant
 * context established from the stored run / verified security context (invariant 1). The {@code
 * event_id} is the SAME id as the outbox event id / envelope eventId, the anchor a consumer
 * re-checks against.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class InboxMessageRepository {

  private static final String TABLE = "inbox_messages";

  private final JdbcTemplate jdbc;

  /**
   * @param jdbc the JDBC read/write path for {@code inbox_messages}
   */
  public InboxMessageRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Claim an event for a consumer by inserting its {@code (consumer_name, event_id)} row as {@code
   * PENDING} (the dedup fence, S06-04 / S06-10).
   *
   * <p>The composite {@code PRIMARY KEY} makes a second insert of the same pair fail the unique
   * constraint; that collision is caught and reported as {@code false} so the caller skips the
   * business state change &mdash; the event was already claimed by another delivery (concurrent
   * duplicate delivery produces at most one domain transition).
   *
   * @param claim the fresh {@link InboxMessage.Status#PENDING} claim to insert
   * @return {@code true} on the first claim; {@code false} when the pair already exists (a
   *     duplicate delivery)
   */
  public boolean claim(InboxMessage claim) {
    try {
      jdbc.update(
          "INSERT INTO "
              + TABLE
              + " (consumer_name, event_id, status, attempts, created_at) VALUES (?, ?, 'PENDING', 0, now())",
          ps -> {
            ps.setString(1, claim.consumerName());
            ps.setObject(2, claim.eventId());
          });
      return true;
    } catch (DuplicateKeyException duplicate) {
      // A second delivery of the same (consumer_name, event_id) collides on the composite PK; the
      // event was already claimed, so this duplicate delivery re-runs nothing (S06-10).
      return false;
    }
  }

  /**
   * Record a successful business state change: {@code PENDING -> PROCESSED} with the recorded
   * outcome and {@code processed_at = now()}. The {@code status = 'PENDING'} fence makes the
   * transition idempotent &mdash; a second completion of an already-completed row affects zero
   * rows, so a re-delivered envelope cannot move the row again.
   *
   * @param consumerName the consumer half of the dedup key
   * @param eventId the event half of the dedup key
   * @param outcome the business outcome recorded on completion (never {@code null} on success)
   */
  public void markProcessed(String consumerName, UUID eventId, String outcome) {
    jdbc.update(
        "UPDATE "
            + TABLE
            + " SET status = 'PROCESSED', outcome = ?, processed_at = now() "
            + "WHERE consumer_name = ? AND event_id = ? AND status = 'PENDING'",
        outcome,
        consumerName,
        eventId);
  }

  /**
   * Record a permanently given-up processing attempt: {@code -> FAILED} with the attempt count, a
   * NULL {@code next_attempt_at} (terminal for the local bounded retry) and a safe, redacted last
   * error. The row is held for the consumer retry / DLQ policy (S06-11); it is never {@code
   * PROCESSED}.
   *
   * @param consumerName the consumer half of the dedup key
   * @param eventId the event half of the dedup key
   * @param attempts the attempt count after the failed attempts (already the exhausted total)
   * @param nextAttemptAt the earliest next retry instant, or {@code null} for a terminal hold
   * @param lastError safe, redacted text of the last failure (never a secret, full prompt or
   *     document)
   */
  public void markFailed(
      String consumerName,
      UUID eventId,
      int attempts,
      OffsetDateTime nextAttemptAt,
      String lastError) {
    jdbc.update(
        "UPDATE "
            + TABLE
            + " SET status = 'FAILED', attempts = ?, next_attempt_at = ?, last_error = ? "
            + "WHERE consumer_name = ? AND event_id = ?",
        attempts,
        nextAttemptAt,
        lastError,
        consumerName,
        eventId);
  }

  /**
   * @param consumerName the consumer half of the dedup key
   * @param eventId the event half of the dedup key
   * @return the row for the pair, or empty when no claim exists (a consumer re-checks against this)
   */
  public Optional<InboxMessage> findByConsumerAndEvent(String consumerName, UUID eventId) {
    List<InboxMessage> rows =
        jdbc.query(
            "SELECT consumer_name, event_id, status, attempts, next_attempt_at, last_error, "
                + "outcome, processed_at, created_at FROM "
                + TABLE
                + " WHERE consumer_name = ? AND event_id = ?",
            rowMapper,
            consumerName,
            eventId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * @param consumerName the consumer half of the dedup key
   * @return the number of inbox rows claimed by the consumer (test / operational visibility)
   */
  public int countByConsumer(String consumerName) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + TABLE + " WHERE consumer_name = ?", Integer.class, consumerName);
  }

  /**
   * @param consumerName the consumer half of the dedup key
   * @param status the processing status to count
   * @return the number of the consumer's rows in the given status
   */
  public int countByConsumerAndStatus(String consumerName, InboxMessage.Status status) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + TABLE + " WHERE consumer_name = ? AND status = ?",
        Integer.class,
        consumerName,
        status.name());
  }

  // A package-private row mapper (S06-07); unit-tested directly in InboxMessageRowMapperTest.
  private final InboxMessageRowMapper rowMapper = new InboxMessageRowMapper();
}
