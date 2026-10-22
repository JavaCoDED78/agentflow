package com.example.agentflow.workflowdefinition.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link WorkflowVersionRowMapper} full-row mapping (S03-09), including the {@code
 * graph_json} JSONB round-trip. DB-free: the {@link ResultSet} is a mock.
 */
class WorkflowVersionRowMapperTest {

  @Test
  @SuppressWarnings("unchecked")
  void mapRow_reads_every_column_and_roundTripsGraph() throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID workflowId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-10-22T00:00:00Z");
    String graphJson =
        "{\"nodes\":[{\"id\":\"n1\",\"type\":\"TOOL_CALL\",\"config\":{\"tool\":\"document.search\"}}]}";

    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("workflow_id", UUID.class)).thenReturn(workflowId);
    when(rs.getObject("version", Integer.class)).thenReturn(3);
    when(rs.getString("graph_json")).thenReturn(graphJson);
    when(rs.getString("checksum")).thenReturn("deadbeef");
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getString("created_by")).thenReturn("alice");
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));

    WorkflowVersion version = new WorkflowVersionRowMapper().mapRow(rs, 1);

    assertThat(version.id()).isEqualTo(id);
    assertThat(version.workflowId()).isEqualTo(workflowId);
    assertThat(version.version()).isEqualTo(3);
    assertThat(version.graph().nodes()).hasSize(1);
    assertThat(version.graph().nodes().get(0).type()).isEqualTo("TOOL_CALL");
    assertThat(version.graph().nodes().get(0).config()).containsEntry("tool", "document.search");
    assertThat(version.checksum()).isEqualTo("deadbeef");
    assertThat(version.status()).isEqualTo("ACTIVE");
    assertThat(version.createdBy()).isEqualTo("alice");
    assertThat(version.createdAt()).isEqualTo(createdAt);
  }
}
