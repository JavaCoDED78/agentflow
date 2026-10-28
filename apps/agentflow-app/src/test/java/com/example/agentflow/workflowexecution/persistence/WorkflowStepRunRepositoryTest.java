package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
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
 * DB-free tests for the {@code workflow_step_runs} read / conditional-write path (S05-11). A step
 * stores no {@code tenant_id}: tenant isolation flows through the parent run, so every read and
 * write joins {@code workflow_runs} and filters by the parent's {@code tenant_id} in the SQL
 * (invariant 1 — a cross-tenant step is indistinguishable from a missing one). The step fence is
 * {@code lease_epoch} rather than an optimistic-lock version, so the conditional update guards by
 * status + lease_epoch and a zero-rows update becomes a {@link ConflictException} (invariant 4 /
 * fencing). DB-free: the {@link JdbcTemplate} is a mock, so the SQL and the bound arguments are
 * asserted directly.
 */
class WorkflowStepRunRepositoryTest {

  @Test
  @SuppressWarnings("unchecked")
  void findState_joins_the_parent_run_and_scopes_by_tenant() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowStepRunRepository repo = new WorkflowStepRunRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    String stepKey = "llm_plan";
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(Collections.emptyList());

    repo.findState(tenant, runId, stepKey);

    verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
    // tenant isolation is a JOIN + WHERE predicate, not a post-filter (invariant 1)
    assertThat(sql.getValue())
        .contains("JOIN workflow_runs r ON s.run_id = r.id")
        .contains("WHERE s.run_id = ? AND s.step_key = ? AND r.tenant_id = ?");
    assertThat(args.getValue()[0]).isEqualTo(runId);
    assertThat(args.getValue()[1]).isEqualTo(stepKey);
    assertThat(args.getValue()[2]).isEqualTo(tenant);
  }

  @Test
  void findState_returns_empty_for_a_cross_tenant_step() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowStepRunRepository repo = new WorkflowStepRunRepository(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(Collections.emptyList());

    assertThat(repo.findState(UUID.randomUUID(), UUID.randomUUID(), "tool_call")).isEmpty();
  }

  @Test
  void transition_zero_rows_becomes_a_conflict() {
    // The lease fence moved (another worker renewed) or the parent run is a different tenant: the
    // guarded UPDATE affects no row and must not silently overwrite the current owner's state.
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowStepRunRepository repo = new WorkflowStepRunRepository(jdbc);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

    assertThatThrownBy(
            () ->
                repo.transition(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "tool_call",
                    WorkflowStepState.RUNNING,
                    5,
                    WorkflowStepState.SUCCEEDED))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("lease epoch");
  }

  @Test
  @SuppressWarnings("unchecked")
  void transition_joins_the_parent_and_binds_status_lease_and_tenant() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowStepRunRepository repo = new WorkflowStepRunRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    String stepKey = "tool_call";
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    repo.transition(
        runId, tenant, stepKey, WorkflowStepState.RUNNING, 5, WorkflowStepState.SUCCEEDED);

    verify(jdbc).update(sql.capture(), setter.capture());
    assertThat(sql.getValue())
        .contains("FROM workflow_runs r")
        .contains("AND r.tenant_id = ? AND s.run_id = ? AND s.step_key = ?")
        .contains("AND s.status = ? AND s.lease_epoch = ?");
    // drive the mocked setter so the bound arguments are recorded on the mock, then assert the
    // order
    setter.getValue().setValues(ps);
    // SET next | WHERE parent tenant | WHERE run | WHERE step_key | WHERE expected status | WHERE
    // lease
    verify(ps).setString(1, WorkflowStepState.SUCCEEDED.name());
    verify(ps).setObject(2, tenant);
    verify(ps).setObject(3, runId);
    verify(ps).setString(4, stepKey);
    verify(ps).setString(5, WorkflowStepState.RUNNING.name());
    verify(ps).setInt(6, 5);
  }
}
