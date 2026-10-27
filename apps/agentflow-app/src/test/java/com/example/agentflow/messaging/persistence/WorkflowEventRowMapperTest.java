package com.example.agentflow.messaging.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.WorkflowEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowEventRowMapperTest {

  @Test
  void maps_run_level_event_all_columns() throws SQLException {
    WorkflowEventRowMapper mapper = new WorkflowEventRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    Instant occurred = Instant.now();
    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("tenant_id", UUID.class)).thenReturn(tenant);
    when(rs.getObject("run_id", UUID.class)).thenReturn(run);
    when(rs.getObject("step_run_id", UUID.class)).thenReturn(null);
    when(rs.getString("event_type")).thenReturn("RUNNING");
    when(rs.getString("payload_redacted")).thenReturn("prior=QUEUED new=RUNNING");
    when(rs.getTimestamp("occurred_at")).thenReturn(new Timestamp(occurred.toEpochMilli()));

    WorkflowEvent mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.id()).isEqualTo(id);
    assertThat(mapped.tenantId()).isEqualTo(tenant);
    assertThat(mapped.runId()).isEqualTo(run);
    assertThat(mapped.stepRunId()).isNull();
    assertThat(mapped.eventType()).isEqualTo("RUNNING");
    assertThat(mapped.payloadRedacted()).isEqualTo("prior=QUEUED new=RUNNING");
    // occurred_at is read as a Timestamp at rest and converted back to an Instant, so nanos are
    // truncated on the roundtrip — compare at millisecond precision.
    assertThat(mapped.occurredAt().toEpochMilli()).isEqualTo(occurred.toEpochMilli());
  }

  @Test
  void maps_step_level_event_with_step_and_null_payload() throws SQLException {
    WorkflowEventRowMapper mapper = new WorkflowEventRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID step = UUID.randomUUID();
    Instant occurred = Instant.now();
    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("tenant_id", UUID.class)).thenReturn(tenant);
    when(rs.getObject("run_id", UUID.class)).thenReturn(run);
    when(rs.getObject("step_run_id", UUID.class)).thenReturn(step);
    when(rs.getString("event_type")).thenReturn("STEP_RUNNING");
    when(rs.getString("payload_redacted")).thenReturn(null);
    when(rs.getTimestamp("occurred_at")).thenReturn(new Timestamp(occurred.toEpochMilli()));

    WorkflowEvent mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.id()).isEqualTo(id);
    assertThat(mapped.tenantId()).isEqualTo(tenant);
    assertThat(mapped.stepRunId()).isEqualTo(step);
    assertThat(mapped.eventType()).isEqualTo("STEP_RUNNING");
    assertThat(mapped.payloadRedacted()).isNull();
    assertThat(mapped.occurredAt().toEpochMilli()).isEqualTo(occurred.toEpochMilli());
  }
}
