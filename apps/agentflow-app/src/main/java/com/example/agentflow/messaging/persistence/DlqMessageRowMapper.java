package com.example.agentflow.messaging.persistence;

import com.example.agentflow.messaging.domain.DlqMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps a {@code dlq_messages} row to a {@link DlqMessage} (S06-11).
 *
 * <p>Kept as a standalone class so it can be unit-tested directly with a mock {@link ResultSet},
 * mirroring {@link InboxMessageRowMapper} and {@link OutboxEventRowMapper}. The {@code last_error}
 * column may be NULL (for example a record appended before the error detail was known); it maps to
 * {@code null} and is not coerced. The dedup key is {@code (consumer_name, event_id)}; {@code
 * event_id} is the same id as the outbox event id and the envelope {@code eventId} (invariant 3).
 */
final class DlqMessageRowMapper implements RowMapper<DlqMessage> {

  @Override
  public DlqMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
    return DlqMessage.read(
        rs.getObject("id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getString("topic"),
        rs.getString("consumer_name"),
        DlqMessage.Status.valueOf(rs.getString("status")),
        rs.getInt("attempts"),
        rs.getString("last_error"),
        timestamp(rs, "recorded_at"));
  }

  private static OffsetDateTime timestamp(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
  }
}
