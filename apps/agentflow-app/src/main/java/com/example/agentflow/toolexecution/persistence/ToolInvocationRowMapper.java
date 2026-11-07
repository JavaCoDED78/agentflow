package com.example.agentflow.toolexecution.persistence;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.toolexecution.domain.ToolInvocationOutcome;
import com.example.agentflow.toolexecution.domain.ToolInvocationRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps one {@code tool_invocations} row to the immutable {@link ToolInvocationRecord} (S08-13). The
 * read is the mirror of the repository's insert: same column order, same nullable handling, the
 * enums restored from their stored names.
 */
class ToolInvocationRowMapper implements RowMapper<ToolInvocationRecord> {

  @Override
  public ToolInvocationRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new ToolInvocationRecord(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getString("step_run_id"),
        rs.getString("tool_name"),
        rs.getString("tool_version"),
        ToolRiskClass.valueOf(rs.getString("risk_class")),
        rs.getInt("attempt"),
        ToolInvocationOutcome.valueOf(rs.getString("outcome")),
        rs.getLong("latency_ms"),
        rs.getString("output_reference"),
        rs.getString("error_class"),
        rs.getString("error_summary"));
  }
}
