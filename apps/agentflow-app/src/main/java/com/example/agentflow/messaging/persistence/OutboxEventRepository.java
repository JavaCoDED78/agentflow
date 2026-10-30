package com.example.agentflow.messaging.persistence;

import com.example.agentflow.messaging.domain.OutboxEvent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Write path for the {@code outbox_events} table, owned by the {@code messaging} module (S06-05).
 *
 * <p>This is the single appender for the transactional outbox. The {@link #insert} adds exactly one
 * {@code PENDING} row; a controller never sets {@code status}, the publisher owns the {@code
 * PENDING -> SENT/PUBLISHED / FAILED} transitions (S06-07). The row is tenant-scoped (invariant 1)
 * and the insert is the sole write path, so an event can never be written without its durable state
 * and vice versa.
 *
 * <p>The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * (S01-02); {@link com.example.agentflow.messaging.application.OutboxService} fails loudly when the
 * repository is absent.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class OutboxEventRepository {

  private static final String TABLE = "outbox_events";

  private final JdbcTemplate jdbc;

  public OutboxEventRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Append one queued event and return its assigned row id. The row is tenant-scoped (invariant 1)
   * and {@code PENDING} until the publisher handles it, so a run can never be committed without its
   * outbox event and an event can never be committed without durable state (S06-05 acceptance).
   *
   * @param event the queued event to append
   * @return the row id ({@code id})
   */
  public UUID insert(OutboxEvent event) {
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, event_id, tenant_id, aggregate_type, aggregate_id, topic, event_key, "
            + "payload, headers, status, publish_attempts, next_attempt_at, last_error, "
            + "created_at, published_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), NULL)",
        ps -> {
          ps.setObject(1, event.id());
          ps.setObject(2, event.eventId());
          ps.setObject(3, event.tenantId());
          ps.setString(4, event.aggregateType());
          ps.setObject(5, event.aggregateId());
          ps.setString(6, event.topic());
          ps.setString(7, event.eventKey());
          ps.setString(8, event.payloadJson());
          ps.setString(9, event.headersJson());
          ps.setString(10, event.status().name());
          ps.setInt(11, event.publishAttempts());
          ps.setObject(12, event.nextAttemptAt());
          ps.setString(13, event.lastError());
        });
    return event.id();
  }

  /**
   * Select a bounded, oldest-first batch of rows the publisher may publish now: every {@code
   * PENDING} row plus a {@code FAILED} row whose bounded-retry {@code next_attempt_at} has arrived.
   * The partial {@code outbox_events_pending_idx} backs the {@code status} filter and the {@code
   * created_at} ordering, so the scan is an index scan, never a full table scan (invariant: the
   * pending read is backed by an index, S06-07).
   *
   * @param batchSize the maximum number of rows to fetch (the poll batch bound, S06-07)
   * @return up to {@code batchSize} rows ordered by {@code created_at} ascending, empty when
   *     nothing is due
   */
  public List<OutboxEvent> selectForClaim(int batchSize) {
    return jdbc.query(SELECT_ALL + WHERE_DUE, rowMapper, OffsetDateTime.now(), batchSize);
  }

  /**
   * Atomically claim the given rows for publishing: transition each still-{@code PENDING} row to
   * {@code CLAIMED} and bump its {@code publish_attempts} in a single conditional update. The
   * {@code WHERE ... AND status = 'PENDING'} fence makes the claim atomic &mdash; if two publishers
   * race on the same row only the first transition matches, so a row is claimed by exactly one
   * publisher (S06-07 acceptance: two publishers never claim the same row). Only the claimed rows
   * are returned; a candidate a concurrent publisher already claimed simply does not appear, so the
   * caller never publishes the same row twice.
   *
   * @param ids the candidate row ids (from {@link #selectForClaim})
   * @return the rows successfully transitioned to {@code CLAIMED}, ready to be published
   */
  public List<OutboxEvent> claim(List<UUID> ids) {
    return jdbc.query(CLAIM_SQL, rowMapper, (Object) ids.toArray(new UUID[0]));
  }

  /**
   * Record a successful broker delivery: {@code CLAIMED -> SENT} (delivered, awaiting the
   * consumer's acknowledgement, S06-08). The {@code status = 'CLAIMED'} fence rejects a double
   * update when the row was already consumed, so a retried publish of a delivered row is a no-op.
   *
   * @param id the claimed row id
   */
  public void markSent(UUID id) {
    jdbc.update(MARK_SENT_SQL, id);
  }

  /**
   * Record a publish failure: {@code CLAIMED -> FAILED} with the attempt count, the next retry
   * instant and a safe, redacted error. A {@code null} {@code nextAttemptAt} marks the row terminal
   * for the auto-poll (attempts exhausted or a permanent failure): it stays visible for monitoring
   * and the DLQ (S06-11) but is not re-fetched. The {@code status = 'CLAIMED'} fence keeps the
   * transition fenced to the current publisher (invariant 5).
   *
   * @param id the claimed row id
   * @param publishAttempts the attempt count after the failed attempt (already bumped by the claim)
   * @param nextAttemptAt the earliest next retry instant, or {@code null} when the row is terminal
   * @param lastError safe, redacted text of the failure (never a secret, full prompt or document)
   */
  public void markFailed(
      UUID id, int publishAttempts, OffsetDateTime nextAttemptAt, String lastError) {
    jdbc.update(MARK_FAILED_SQL, publishAttempts, nextAttemptAt, lastError, id);
  }

  // A package-private row mapper (S06-07); unit-tested directly in OutboxEventRowMapperTest.
  private final OutboxEventRowMapper rowMapper = new OutboxEventRowMapper();
  private static final String COLUMNS =
      "id, event_id, tenant_id, aggregate_type, aggregate_id, topic, event_key, "
          + "payload, headers, status, publish_attempts, next_attempt_at, last_error, "
          + "created_at, published_at";

  private static final String SELECT_ALL = "SELECT " + COLUMNS + " FROM outbox_events ";

  private static final String WHERE_DUE =
      "WHERE status = 'PENDING' "
          + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
          + "ORDER BY created_at ASC LIMIT ?";

  private static final String CLAIM_SQL =
      "UPDATE outbox_events SET status = 'CLAIMED', publish_attempts = publish_attempts + 1 "
          + "WHERE id = ANY (?) AND status = 'PENDING' RETURNING "
          + COLUMNS;

  private static final String MARK_SENT_SQL =
      "UPDATE outbox_events SET status = 'SENT', published_at = now() "
          + "WHERE id = ? AND status = 'CLAIMED'";

  private static final String MARK_FAILED_SQL =
      "UPDATE outbox_events SET status = 'FAILED', publish_attempts = ?, next_attempt_at = ?, "
          + "last_error = ? WHERE id = ? AND status = 'CLAIMED'";
}
