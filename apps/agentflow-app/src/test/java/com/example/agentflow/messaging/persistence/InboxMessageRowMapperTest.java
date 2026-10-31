package com.example.agentflow.messaging.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.InboxMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for {@link InboxMessageRowMapper} (S06-04 / S06-10): a mock {@link ResultSet}
 * drives the mapper. The {@code next_attempt_at}, {@code last_error}, {@code outcome} and {@code
 * processed_at} columns may be NULL (a freshly claimed row is {@code PENDING} with NULLs); NULLs
 * map to {@code null}, never a coerced value. The {@code event_id} is the same id as the outbox
 * event id and the envelope {@code eventId} (invariant 3).
 */
class InboxMessageRowMapperTest {

  @Test
  void maps_a_freshly_claimed_row_with_nulls_and_zero_attempts() throws SQLException {
    InboxMessageRowMapper mapper = new InboxMessageRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID eventId = UUID.randomUUID();
    Instant created = Instant.parse("2026-01-01T00:00:00Z");

    when(rs.getString("consumer_name")).thenReturn("agent:run:step");
    when(rs.getObject("event_id", UUID.class)).thenReturn(eventId);
    when(rs.getString("status")).thenReturn("PENDING");
    when(rs.getInt("attempts")).thenReturn(0);
    when(rs.getTimestamp("next_attempt_at")).thenReturn(null); // not retried yet
    when(rs.getString("last_error")).thenReturn(null);
    when(rs.getString("outcome")).thenReturn(null);
    when(rs.getTimestamp("processed_at")).thenReturn(null); // not PROCESSED yet
    when(rs.getTimestamp("created_at")).thenReturn(new Timestamp(created.toEpochMilli()));

    InboxMessage mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.consumerName()).isEqualTo("agent:run:step");
    assertThat(mapped.eventId()).isEqualTo(eventId);
    assertThat(mapped.status()).isEqualTo(InboxMessage.Status.PENDING);
    assertThat(mapped.attempts()).isEqualTo(0);
    assertThat(mapped.nextAttemptAt()).isNull();
    assertThat(mapped.lastError()).isNull();
    assertThat(mapped.outcome()).isNull();
    assertThat(mapped.processedAt()).isNull();
    assertThat(mapped.createdAt().toInstant().toEpochMilli()).isEqualTo(created.toEpochMilli());
  }

  @Test
  void maps_a_processed_row_with_outcome_and_processed_at() throws SQLException {
    InboxMessageRowMapper mapper = new InboxMessageRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID eventId = UUID.randomUUID();
    OffsetDateTime processed =
        OffsetDateTime.ofInstant(Instant.parse("2026-01-01T00:00:05Z"), ZoneOffset.UTC);

    when(rs.getString("consumer_name")).thenReturn("agent:run:step");
    when(rs.getObject("event_id", UUID.class)).thenReturn(eventId);
    when(rs.getString("status")).thenReturn("PROCESSED");
    when(rs.getInt("attempts")).thenReturn(1);
    when(rs.getTimestamp("next_attempt_at")).thenReturn(null);
    when(rs.getString("last_error")).thenReturn(null);
    when(rs.getString("outcome")).thenReturn("prior=RUNNING new=SUCCEEDED");
    when(rs.getTimestamp("processed_at"))
        .thenReturn(new Timestamp(processed.toInstant().toEpochMilli()));
    when(rs.getTimestamp("created_at")).thenReturn(null);

    InboxMessage mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.status()).isEqualTo(InboxMessage.Status.PROCESSED);
    assertThat(mapped.attempts()).isEqualTo(1);
    assertThat(mapped.outcome()).isEqualTo("prior=RUNNING new=SUCCEEDED");
    assertThat(mapped.processedAt()).isEqualTo(processed);
    assertThat(mapped.createdAt()).isNull();
  }

  @Test
  void maps_a_failed_row_with_attempts_and_last_error() throws SQLException {
    InboxMessageRowMapper mapper = new InboxMessageRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID eventId = UUID.randomUUID();
    OffsetDateTime nextAttempt =
        OffsetDateTime.ofInstant(Instant.parse("2026-01-01T00:00:30Z"), ZoneOffset.UTC);

    when(rs.getString("consumer_name")).thenReturn("agent:run:step");
    when(rs.getObject("event_id", UUID.class)).thenReturn(eventId);
    when(rs.getString("status")).thenReturn("FAILED");
    when(rs.getInt("attempts")).thenReturn(3);
    when(rs.getTimestamp("next_attempt_at"))
        .thenReturn(new Timestamp(nextAttempt.toInstant().toEpochMilli()));
    when(rs.getString("last_error")).thenReturn("TransientExecutionError: attempt 3 failed");
    when(rs.getString("outcome")).thenReturn(null);
    when(rs.getTimestamp("processed_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(null);

    InboxMessage mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.status()).isEqualTo(InboxMessage.Status.FAILED);
    assertThat(mapped.attempts()).isEqualTo(3);
    assertThat(mapped.nextAttemptAt()).isEqualTo(nextAttempt);
    assertThat(mapped.lastError()).isEqualTo("TransientExecutionError: attempt 3 failed");
    assertThat(mapped.outcome()).isNull();
    assertThat(mapped.processedAt()).isNull();
  }
}
