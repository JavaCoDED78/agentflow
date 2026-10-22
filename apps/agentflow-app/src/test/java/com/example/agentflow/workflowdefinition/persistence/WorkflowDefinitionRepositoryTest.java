package com.example.agentflow.workflowdefinition.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.workflowdefinition.domain.ConflictException;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

/**
 * Verifies the "mapping rules" of the persistence layer (ADR-009): the tenant filter must be in the
 * SQL itself (invariant 1) and a zero-rows conditional update must become a conflict (invariant 4).
 * DB-free: the {@link JdbcTemplate} is a mock, so the SQL and bound arguments are asserted
 * directly.
 */
class WorkflowDefinitionRepositoryTest {

  @Test
  @SuppressWarnings("unchecked")
  void findByTenantAndId_binds_the_tenant_in_the_where_clause() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowDefinitionRepository repo = new WorkflowDefinitionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenReturn(Collections.emptyList());

    repo.findByTenantAndId(tenant, id);

    verify(jdbc).query(sql.capture(), setter.capture(), any(RowMapper.class));
    assertThat(sql.getValue()).contains("tenant_id");
    setter.getValue().setValues(ps);
    verify(ps).setObject(1, id);
    verify(ps).setObject(2, tenant);
  }

  @Test
  void updateMetadata_zero_rows_becomes_a_conflict() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowDefinitionRepository repo = new WorkflowDefinitionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

    assertThatThrownBy(() -> repo.updateMetadata(tenant, id, WorkflowDefinition.DRAFT, "x"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void updateMetadata_is_a_tenant_and_status_conditional_update() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowDefinitionRepository repo = new WorkflowDefinitionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    repo.updateMetadata(tenant, id, WorkflowDefinition.DRAFT, "updated");

    verify(jdbc).update(sql.capture(), setter.capture());
    assertThat(sql.getValue()).contains("tenant_id").contains("status = ?");
    // SET description, SET status, WHERE id, WHERE tenant_id, WHERE status
    setter.getValue().setValues(ps);
    verify(ps).setString(1, "updated");
    verify(ps).setString(2, WorkflowDefinition.DRAFT);
    verify(ps).setObject(3, id);
    verify(ps).setObject(4, tenant);
    verify(ps).setString(5, WorkflowDefinition.DRAFT);
  }
}
