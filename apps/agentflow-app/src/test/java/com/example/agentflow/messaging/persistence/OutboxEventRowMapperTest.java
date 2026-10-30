package com.example.agentflow.messaging.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.OutboxEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for {@link OutboxEventRowMapper} (S06-07): a mock {@link ResultSet} drives the
 * mapper, mirroring {@code WorkflowEventRowMapperTest}. The timestamptz columns are read as {@code
 * Timestamp} at rest and converted to {@code OffsetDateTime}; NULLs (a fresh {@code PENDING} row
 * has {@code next_attempt_at = NULL}) map to {@code null}, never a coerced value.
 */
class OutboxEventRowMapperTest {

  @Test
  void maps_claimed_event_with_all_columns_and_a_null_retry_instant() throws SQLException {
    OutboxEventRowMapper mapper = new OutboxEventRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    Instant created = Instant.now();

    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("event_id", UUID.class)).thenReturn(eventId);
    when(rs.getObject("tenant_id", UUID.class)).thenReturn(tenant);
    when(rs.getString("aggregate_type")).thenReturn("workflow_run");
    when(rs.getObject("aggregate_id", UUID.class)).thenReturn(run);
    when(rs.getString("topic")).thenReturn("agentflow.workflow.run-requested.v1");
    when(rs.getString("event_key")).thenReturn(tenant + ":" + run);
    when(rs.getString("payload")).thenReturn("{\"runId\":\"" + run + "\"}");
    when(rs.getString("headers")).thenReturn(null);
    when(rs.getString("status")).thenReturn("CLAIMED");
    when(rs.getInt("publish_attempts")).thenReturn(1);
    when(rs.getTimestamp("next_attempt_at")).thenReturn(null); // due-again PENDING/first CLAIMED
    when(rs.getString("last_error")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(new Timestamp(created.toEpochMilli()));
    when(rs.getTimestamp("published_at")).thenReturn(null); // not SENT yet

    OutboxEvent mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.id()).isEqualTo(id);
    assertThat(mapped.eventId()).isEqualTo(eventId);
    assertThat(mapped.tenantId()).isEqualTo(tenant);
    assertThat(mapped.aggregateType()).isEqualTo("workflow_run");
    assertThat(mapped.aggregateId()).isEqualTo(run);
    assertThat(mapped.topic()).isEqualTo("agentflow.workflow.run-requested.v1");
    assertThat(mapped.eventKey()).isEqualTo(tenant + ":" + run);
    assertThat(mapped.payloadJson()).isEqualTo("{\"runId\":\"" + run + "\"}");
    assertThat(mapped.headersJson()).isNull();
    assertThat(mapped.status()).isEqualTo(OutboxEvent.State.CLAIMED);
    assertThat(mapped.publishAttempts()).isEqualTo(1);
    assertThat(mapped.nextAttemptAt()).isNull();
    assertThat(mapped.lastError()).isNull();
    assertThat(mapped.createdAt().toInstant().toEpochMilli()).isEqualTo(created.toEpochMilli());
    assertThat(mapped.publishedAt()).isNull();
  }

  @Test
  void maps_a_delivered_event_with_a_sent_status_and_published_at() throws SQLException {
    OutboxEventRowMapper mapper = new OutboxEventRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    Instant published = Instant.parse("2026-01-01T00:00:02Z");

    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("event_id", UUID.class)).thenReturn(eventId);
    when(rs.getObject("tenant_id", UUID.class)).thenReturn(tenant);
    when(rs.getString("aggregate_type")).thenReturn("workflow_run");
    when(rs.getObject("aggregate_id", UUID.class)).thenReturn(run);
    when(rs.getString("topic")).thenReturn("agentflow.workflow.run-requested.v1");
    when(rs.getString("event_key")).thenReturn(tenant + ":" + run);
    when(rs.getString("payload")).thenReturn("{\"runId\":\"" + run + "\"}");
    when(rs.getString("headers")).thenReturn(null);
    when(rs.getString("status")).thenReturn("SENT");
    when(rs.getInt("publish_attempts")).thenReturn(1);
    when(rs.getTimestamp("next_attempt_at")).thenReturn(null);
    when(rs.getString("last_error")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(new Timestamp(created.toEpochMilli()));
    when(rs.getTimestamp("published_at")).thenReturn(new Timestamp(published.toEpochMilli()));

    OutboxEvent mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.status()).isEqualTo(OutboxEvent.State.SENT);
    assertThat(mapped.publishedAt().toInstant().toEpochMilli()).isEqualTo(published.toEpochMilli());
  }
}
