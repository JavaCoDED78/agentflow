package com.example.agentflow.messaging.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.DlqMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for {@link DlqMessageRowMapper} (S06-11): a mock {@link ResultSet} drives the
 * mapper. The {@code last_error} column may be NULL; it maps to {@code null}, never a coerced
 * value. The {@code event_id} is the same id as the outbox event id and the envelope {@code
 * eventId} (invariant 3); the dedup key is {@code (consumer_name, event_id)}.
 */
class DlqMessageRowMapperTest {

  @Test
  void maps_a_dead_letter_record() throws SQLException {
    DlqMessageRowMapper mapper = new DlqMessageRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant recorded = Instant.parse("2026-01-01T00:00:00Z");

    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("event_id", UUID.class)).thenReturn(eventId);
    when(rs.getString("topic")).thenReturn("agent-run.v1");
    when(rs.getString("consumer_name")).thenReturn("agent:run:step");
    when(rs.getString("status")).thenReturn("DEAD_LETTER");
    when(rs.getInt("attempts")).thenReturn(1);
    when(rs.getString("last_error")).thenReturn("IllegalArgumentException: malformed envelope");
    when(rs.getTimestamp("recorded_at")).thenReturn(new Timestamp(recorded.toEpochMilli()));

    DlqMessage mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.id()).isEqualTo(id);
    assertThat(mapped.eventId()).isEqualTo(eventId);
    assertThat(mapped.topic()).isEqualTo("agent-run.v1");
    assertThat(mapped.consumerName()).isEqualTo("agent:run:step");
    assertThat(mapped.status()).isEqualTo(DlqMessage.Status.DEAD_LETTER);
    assertThat(mapped.attempts()).isEqualTo(1);
    assertThat(mapped.lastError()).isEqualTo("IllegalArgumentException: malformed envelope");
    assertThat(mapped.recordedAt().toInstant().toEpochMilli()).isEqualTo(recorded.toEpochMilli());
  }

  @Test
  void maps_a_record_with_null_last_error() throws SQLException {
    DlqMessageRowMapper mapper = new DlqMessageRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    OffsetDateTime recorded =
        OffsetDateTime.ofInstant(Instant.parse("2026-01-01T00:00:05Z"), ZoneOffset.UTC);

    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("event_id", UUID.class)).thenReturn(eventId);
    when(rs.getString("topic")).thenReturn("agent-run.v1");
    when(rs.getString("consumer_name")).thenReturn("agent:run:step");
    when(rs.getString("status")).thenReturn("RETRY");
    when(rs.getInt("attempts")).thenReturn(3);
    when(rs.getString("last_error")).thenReturn(null); // no error detail yet
    when(rs.getTimestamp("recorded_at"))
        .thenReturn(new Timestamp(recorded.toInstant().toEpochMilli()));

    DlqMessage mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.status()).isEqualTo(DlqMessage.Status.RETRY);
    assertThat(mapped.attempts()).isEqualTo(3);
    assertThat(mapped.lastError()).isNull();
    assertThat(mapped.recordedAt()).isEqualTo(recorded);
  }
}
