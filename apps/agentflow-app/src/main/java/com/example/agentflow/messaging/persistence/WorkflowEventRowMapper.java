package com.example.agentflow.messaging.persistence;

import com.example.agentflow.messaging.domain.WorkflowEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps a single {@code workflow_events} row to a {@link WorkflowEvent}.
 *
 * <p>Every column is read by name against the owning module's {@code workflow_events} table. The
 * {@code step_run_id} and {@code payload_redacted} columns are nullable and read back as {@code
 * null}; the {@code occurred_at} timestamp is read as a {@link Instant} to match the database
 * {@code timestamptz} column. The {@code event_type} is stored as free-form {@code TEXT} (the
 * vocabulary is fixed by the state machine, S05-10), so it maps straight to a {@code String}.
 */
public class WorkflowEventRowMapper implements RowMapper<WorkflowEvent> {

  @Override
  public WorkflowEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new WorkflowEvent(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getObject("step_run_id", UUID.class),
        rs.getString("event_type"),
        rs.getString("payload_redacted"),
        rs.getTimestamp("occurred_at").toInstant());
  }
}
