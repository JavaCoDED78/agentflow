package com.example.agentflow.messaging.persistence;

import com.example.agentflow.messaging.domain.OutboxEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps an {@code outbox_events} row to an {@link OutboxEvent} (S06-07).
 *
 * <p>Kept as a standalone class so it can be unit-tested directly with a mock {@link ResultSet},
 * mirroring {@code WorkflowEventRowMapperTest}. The {@code next_attempt_at}, {@code created_at} and
 * {@code published_at} timestamptz columns may be NULL (e.g. {@code next_attempt_at} is NULL for a
 * freshly inserted {@code PENDING} row, {@code published_at} is NULL until SENT); NULLs map to
 * {@code null} and are not coerced.
 */
final class OutboxEventRowMapper implements RowMapper<OutboxEvent> {

  @Override
  public OutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new OutboxEvent(
        rs.getObject("id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("aggregate_type"),
        rs.getObject("aggregate_id", UUID.class),
        rs.getString("topic"),
        rs.getString("event_key"),
        rs.getString("payload"),
        rs.getString("headers"),
        OutboxEvent.State.valueOf(rs.getString("status")),
        rs.getInt("publish_attempts"),
        timestamp(rs, "next_attempt_at"),
        rs.getString("last_error"),
        timestamp(rs, "created_at"),
        timestamp(rs, "published_at"));
  }

  private static OffsetDateTime timestamp(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
  }
}
