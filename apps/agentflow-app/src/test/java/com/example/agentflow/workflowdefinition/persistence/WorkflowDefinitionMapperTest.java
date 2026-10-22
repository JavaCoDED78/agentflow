package com.example.agentflow.workflowdefinition.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies the {@link WorkflowDefinitionMapper} row-to-record mapping, which is the "mapping rule"
 * half of the persistence contract (ADR-009). DB-free: the {@link ResultSet} is a mock.
 */
class WorkflowDefinitionMapperTest {

  @Test
  void mapRow_maps_every_column_to_the_record() throws SQLException {
    UUID id = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-10-22T00:00:00Z");
    int version = 3;

    ResultSet rs = Mockito.mock(ResultSet.class);
    Mockito.when(rs.getObject("id", UUID.class)).thenReturn(id);
    Mockito.when(rs.getObject("tenant_id", UUID.class)).thenReturn(tenant);
    Mockito.when(rs.getString("name")).thenReturn("deploy");
    Mockito.when(rs.getString("description")).thenReturn("prod deploy");
    Mockito.when(rs.getString("status")).thenReturn(WorkflowDefinition.DRAFT);
    Mockito.when(rs.getInt("version")).thenReturn(version);
    Mockito.when(rs.getString("created_by")).thenReturn("alice");
    Mockito.when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));

    WorkflowDefinition wf = new WorkflowDefinitionMapper().mapRow(rs, 1);

    assertThat(wf.id()).isEqualTo(id);
    assertThat(wf.tenantId()).isEqualTo(tenant);
    assertThat(wf.name()).isEqualTo("deploy");
    assertThat(wf.description()).isEqualTo("prod deploy");
    assertThat(wf.status()).isEqualTo(WorkflowDefinition.DRAFT);
    assertThat(wf.version()).isEqualTo(version);
    assertThat(wf.createdBy()).isEqualTo("alice");
    assertThat(wf.createdAt()).isEqualTo(createdAt);
  }
}
