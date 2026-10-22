package com.example.agentflow.workflowdefinition.persistence;

import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps a single {@code workflow_definition_versions} row to a {@link WorkflowVersion} (S03-09).
 *
 * <p>The {@code graph_json} JSONB column is read as its JSON text and parsed back into a {@link
 * WorkflowGraph} by the {@link WorkflowGraphCodec} so the graph round-trips through JSONB and
 * preserves its meaningful fields (node id, type and config). The remaining columns map to the
 * {@link WorkflowVersion} fields, with {@code created_at} read as a {@link Instant} to match the
 * {@code timestamptz} column (see {@code docs/architecture/database-conventions.md}).
 *
 * <p>Note: {@code org.springframework.jdbc.core.ColumnMapper} was removed in Spring Framework 7, so
 * the graph column is parsed inline here rather than via a {@code ColumnMapper}; the {@link
 * WorkflowGraphCodec} is the single place that owns the JSONB round-trip.
 */
public class WorkflowVersionRowMapper implements RowMapper<WorkflowVersion> {

  private final WorkflowGraphCodec codec = new WorkflowGraphCodec(new ObjectMapper());

  @Override
  public WorkflowVersion mapRow(ResultSet rs, int rowNum) throws SQLException {
    String graphJson = rs.getString("graph_json");
    WorkflowGraph graph = graphJson == null ? null : codec.deserialize(graphJson);
    return new WorkflowVersion(
        rs.getObject("id", UUID.class),
        rs.getObject("workflow_id", UUID.class),
        rs.getObject("version", Integer.class),
        graph,
        rs.getString("checksum"),
        rs.getString("status"),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant());
  }
}
