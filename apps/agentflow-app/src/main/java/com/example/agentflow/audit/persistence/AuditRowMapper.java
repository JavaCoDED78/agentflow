package com.example.agentflow.audit.persistence;

import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditEvent;
import com.example.agentflow.audit.domain.AuditOutcome;
import com.example.agentflow.audit.domain.AuditResourceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps a single {@code audit_events} row to a {@link AuditEvent}.
 *
 * <p>Every column is read by name against the owning module's {@code audit_events} table. The enum
 * columns are {@code TEXT} at rest (database-conventions), so the {@code action}, {@code
 * resource_type} and {@code outcome} values are read back by name into their enums. The {@code
 * created_at} timestamp is read as a {@link Instant} to match the database {@code timestamptz}
 * column.
 */
public class AuditRowMapper implements RowMapper<AuditEvent> {

  @Override
  public AuditEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new AuditEvent(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("actor_subject"),
        AuditAction.valueOf(rs.getString("action")),
        AuditResourceType.valueOf(rs.getString("resource_type")),
        rs.getObject("resource_id", UUID.class),
        AuditOutcome.valueOf(rs.getString("outcome")),
        rs.getObject("correlation_id", UUID.class),
        rs.getString("metadata_redacted"),
        rs.getTimestamp("created_at").toInstant());
  }
}
