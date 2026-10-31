package com.example.agentflow.messaging.persistence;

import com.example.agentflow.messaging.domain.InboxMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps an {@code inbox_messages} row to an {@link InboxMessage} (S06-04 / S06-10).
 *
 * <p>Kept as a standalone class so it can be unit-tested directly with a mock {@link ResultSet},
 * mirroring {@link OutboxEventRowMapper}. The {@code next_attempt_at}, {@code last_error}, {@code
 * outcome}, {@code processed_at} and {@code created_at} columns may be NULL (for example {@code
 * next_attempt_at} is NULL for a freshly claimed row, {@code processed_at} is NULL until the row
 * reaches {@link InboxMessage.Status#PROCESSED}); NULLs map to {@code null} and are not coerced.
 * The composite key is {@code (consumer_name, event_id)}; {@code event_id} is the same id as the
 * outbox event id and the envelope {@code eventId} (invariant 3).
 */
final class InboxMessageRowMapper implements RowMapper<InboxMessage> {

  @Override
  public InboxMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new InboxMessage(
        rs.getString("consumer_name"),
        rs.getObject("event_id", UUID.class),
        InboxMessage.Status.valueOf(rs.getString("status")),
        rs.getInt("attempts"),
        timestamp(rs, "next_attempt_at"),
        rs.getString("last_error"),
        rs.getString("outcome"),
        timestamp(rs, "processed_at"),
        timestamp(rs, "created_at"));
  }

  private static OffsetDateTime timestamp(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
  }
}
