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
 * SQL itself (invariant 1), and a zero-rows conditional update must become a conflict (invariant
 * 4). The conditional update is also an optimistic-lock update (S03-10): the expected version is
 * checked in the {@code WHERE} and bumped in the {@code SET}, so a stale update (another writer
 * changed the row first) affects zero rows and surfaces as a conflict. DB-free: the {@link
 * JdbcTemplate} is a mock, so the SQL and bound arguments are asserted directly.
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
    WorkflowDefinition def = WorkflowDefinition.draft(UUID.randomUUID(), "x", "x", "alice");
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

    assertThatThrownBy(() -> repo.updateMetadata(def, WorkflowDefinition.DRAFT, "x"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void updateMetadata_is_a_tenant_status_and_version_conditional_update() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowDefinitionRepository repo = new WorkflowDefinitionRepository(jdbc);
    WorkflowDefinition def = WorkflowDefinition.draft(UUID.randomUUID(), "x", "x", "alice");
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    repo.updateMetadata(def, WorkflowDefinition.DRAFT, "updated");

    verify(jdbc).update(sql.capture(), setter.capture());
    // tenant + status guard (conditional update) and the optimistic-version guard in WHERE, plus
    // the version bump in SET
    assertThat(sql.getValue())
        .contains("tenant_id")
        .contains("status = ?")
        .contains("version = version + 1")
        .contains("AND version = ?");
    // SET description, SET status, SET version(+1) | WHERE id, WHERE tenant_id, WHERE status,
    // WHERE version
    setter.getValue().setValues(ps);
    verify(ps).setString(1, "updated");
    verify(ps).setString(2, WorkflowDefinition.DRAFT);
    verify(ps).setObject(3, def.id());
    verify(ps).setObject(4, def.tenantId());
    verify(ps).setString(5, WorkflowDefinition.DRAFT);
    verify(ps).setInt(6, def.version());
  }

  @Test
  void updateMetadata_bumps_the_optimistic_version_on_success() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowDefinitionRepository repo = new WorkflowDefinitionRepository(jdbc);
    WorkflowDefinition def = WorkflowDefinition.draft(UUID.randomUUID(), "x", "x", "alice");
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    repo.updateMetadata(def, WorkflowDefinition.DRAFT, "updated");

    verify(jdbc).update(sql.capture(), setter.capture());
    assertThat(sql.getValue()).contains("SET description = ?, status = ?, version = version + 1");
    // drive the mocked setter so the bound arguments are actually recorded on the mock
    setter.getValue().setValues(ps);
    // the expected version read from the definition is exactly what the WHERE clause checks
    verify(ps).setInt(6, def.version());
  }

  @Test
  void updateMetadata_stale_version_becomes_a_conflict() {
    // Two writers read the same row (version 0); the second update sees version 1 on the row, so
    // its conditional UPDATE affects zero rows and must not silently overwrite the first writer's
    // change (S03-10: one of two competing updates does not silently overwrite the other).
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowDefinitionRepository repo = new WorkflowDefinitionRepository(jdbc);
    WorkflowDefinition stale = WorkflowDefinition.draft(UUID.randomUUID(), "x", "x", "alice");
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

    assertThatThrownBy(() -> repo.updateMetadata(stale, WorkflowDefinition.DRAFT, "x"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("optimistic version");
  }

  @Test
  @SuppressWarnings("unchecked")
  void listByTenant_binds_the_tenant_orders_by_created_at_id_and_bounds_the_page()
      throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowDefinitionRepository repo = new WorkflowDefinitionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    int limit = 25;
    int offset = 50;
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenReturn(Collections.emptyList());

    repo.listByTenant(tenant, limit, offset);

    verify(jdbc).query(sql.capture(), setter.capture(), any(RowMapper.class));
    // tenant isolation: tenant_id is in the WHERE clause; stable total order created_at, id;
    // and the page bounds are in the SQL
    assertThat(sql.getValue())
        .contains("WHERE tenant_id = ?")
        .contains("ORDER BY created_at DESC, id DESC")
        .contains("LIMIT ?")
        .contains("OFFSET ?");
    setter.getValue().setValues(ps);
    verify(ps).setObject(1, tenant);
    verify(ps).setInt(2, limit);
    verify(ps).setInt(3, offset);
  }

  @Test
  void countByTenant_binds_the_tenant_and_reads_the_count_as_a_long() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowDefinitionRepository repo = new WorkflowDefinitionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    // the mock returns the pre-built rows directly (the mapper lambda is never invoked in this
    // DB-free test — only the SQL string and the tenant bind are asserted)
    when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenReturn(Collections.singletonList(42L));

    long count = repo.countByTenant(tenant);

    verify(jdbc).query(sql.capture(), setter.capture(), any(RowMapper.class));
    assertThat(sql.getValue()).contains("COUNT(*)").contains("WHERE tenant_id = ?");
    setter.getValue().setValues(ps);
    verify(ps).setObject(1, tenant);
    assertThat(count).isEqualTo(42L);
  }
}
