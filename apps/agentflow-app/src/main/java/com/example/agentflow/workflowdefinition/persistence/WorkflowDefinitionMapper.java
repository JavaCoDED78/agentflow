package com.example.agentflow.workflowdefinition.persistence;

import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps a single {@code workflow_definitions} row to a {@link WorkflowDefinition}.
 *
 * <p>Every column is read by name against the owning module's {@code workflow_definitions} table
 * (ADR-009). The {@code created_at} timestamp is read as a {@link Instant} to match the database
 * {@code timestamptz} column (see {@code docs/architecture/database-conventions.md}).
 */
public class WorkflowDefinitionMapper implements RowMapper<WorkflowDefinition> {

  @Override
  public WorkflowDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new WorkflowDefinition(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("status"),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant());
  }
}
