package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
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
 * DB-free tests for the {@code workflow_runs} read / conditional-write path (S05-11, the single
 * write path the run state travels through). They prove the two persistence-layer claims the
 * transition service depends on: the {@code tenant_id} filter is in the SQL itself (invariant 1 — a
 * cross-tenant id is indistinguishable from a missing row), and a zero-rows conditional update
 * becomes a {@link ConflictException} (invariant 4 — optimistic lock, one of two competing updates
 * never silently overwrites the other). DB-free: the {@link JdbcTemplate} is a mock, so the SQL and
 * the bound arguments are asserted directly, matching the {@code workflowdefinition} repository
 * test convention (a stubbed {@code JdbcTemplate} never runs the binds it is passed, so the setter
 * is driven against a real {@link PreparedStatement}).
 */
class WorkflowRunRepositoryTest {

  @Test
  @SuppressWarnings("unchecked")
  void findState_binds_the_tenant_in_the_where_clause() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(Collections.emptyList());

    repo.findState(tenant, runId);

    verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
    // the tenant filter is part of the WHERE clause, so a cross-tenant id returns no row (invariant
    // 1)
    assertThat(sql.getValue()).contains("WHERE id = ? AND tenant_id = ?");
    // the id is bound first (the aggregate id), the tenant second (the scope)
    assertThat(args.getValue()[0]).isEqualTo(runId);
    assertThat(args.getValue()[1]).isEqualTo(tenant);
  }

  @Test
  void findState_returns_empty_for_a_cross_tenant_run() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(Collections.emptyList());

    assertThat(repo.findState(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
  }

  @Test
  void transition_zero_rows_becomes_a_conflict() {
    // Another writer already moved the run (stale optimistic version): the guarded UPDATE affects
    // no
    // row and must not silently overwrite the current state (invariant 4).
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

    assertThatThrownBy(
            () ->
                repo.transition(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    WorkflowRunState.QUEUED,
                    0,
                    WorkflowRunState.RUNNING))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("out of date");
  }

  @Test
  @SuppressWarnings("unchecked")
  void transition_is_a_tenant_status_and_version_conditional_update() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    repo.transition(runId, tenant, WorkflowRunState.QUEUED, 0, WorkflowRunState.RUNNING);

    verify(jdbc).update(sql.capture(), setter.capture());
    // SET status + optimistic-version bump, WHERE id + tenant + status + version (conditional
    // update)
    assertThat(sql.getValue())
        .contains("SET status = ?, version = version + 1, updated_at = now()")
        .contains("WHERE id = ? AND tenant_id = ? AND status = ? AND version = ?");
    // SET next | WHERE id | WHERE tenant | WHERE expected status | WHERE expected version
    // drive the mocked setter so the bound arguments are recorded on the mock, then assert the
    // order
    setter.getValue().setValues(ps);
    verify(ps).setString(1, WorkflowRunState.RUNNING.name());
    verify(ps).setObject(2, runId);
    verify(ps).setObject(3, tenant);
    verify(ps).setString(4, WorkflowRunState.QUEUED.name());
    verify(ps).setInt(5, 0);
  }
}
