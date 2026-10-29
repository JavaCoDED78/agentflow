package com.example.agentflow.messaging.persistence;

import com.example.agentflow.messaging.domain.OutboxEvent;
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
}
