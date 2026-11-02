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
        .contains("AND s.status = ? AND s.lease_epoch = ?")
        // the attempt_started_at marker is managed in the same guarded write (invariant 6 /
        // ADR-011)
        .contains("attempt_started_at = CASE");
    // drive the mocked setter so the bound arguments are recorded on the mock, then assert the
    // order
    setter.getValue().setValues(ps);
    // SET next | begin attempt | complete attempt | WHERE parent tenant | WHERE run | WHERE
    // step_key | WHERE expected status | WHERE lease
    verify(ps).setString(1, WorkflowStepState.SUCCEEDED.name());
    verify(ps).setBoolean(2, false);
    verify(ps).setBoolean(3, true);
    verify(ps).setObject(4, tenant);
    verify(ps).setObject(5, runId);
    verify(ps).setString(6, stepKey);
    verify(ps).setString(7, WorkflowStepState.RUNNING.name());
    verify(ps).setInt(8, 5);
  }

  /**
   * Asserts that {@code transition} binds the {@code attempt_started_at} marker for the {@code from
   * &rarr; to} pair. Bind 2 is {@code beginAttempt} (set when the step enters {@code RUNNING}),
   * bind 3 is {@code completeAttempt} (set when a {@code RUNNING} attempt reaches a terminal
   * outcome); the marker is otherwise left unchanged.
   */
  @SuppressWarnings("unchecked")
  private void assertMarker(
      WorkflowStepState from, WorkflowStepState to, boolean beginAttempt, boolean completeAttempt)
      throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowStepRunRepository repo = new WorkflowStepRunRepository(jdbc);
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    repo.transition(UUID.randomUUID(), UUID.randomUUID(), "tool_call", from, 1, to);
    verify(jdbc).update(anyString(), setter.capture());
    setter.getValue().setValues(ps);
    verify(ps).setBoolean(2, beginAttempt);
    verify(ps).setBoolean(3, completeAttempt);
  }

  @Test
  void transition_into_running_records_the_attempt_begin_marker() throws SQLException {
    // PENDING -> READY -> RUNNING: the attempt begins at READY -> RUNNING, before the side effect
    assertMarker(WorkflowStepState.READY, WorkflowStepState.RUNNING, true, false);
  }

  @Test
  void transition_from_retry_wait_to_running_restarts_the_attempt_marker() throws SQLException {
    // RETRY_WAIT -> RUNNING starts a fresh attempt (the attempt number is bumped, S07-14 history)
    assertMarker(WorkflowStepState.RETRY_WAIT, WorkflowStepState.RUNNING, true, false);
  }

  @Test
  void transition_to_success_clears_the_attempt_marker() throws SQLException {
    assertMarker(WorkflowStepState.RUNNING, WorkflowStepState.SUCCEEDED, false, true);
  }

  @Test
  void transition_to_failed_clears_the_attempt_marker() throws SQLException {
    assertMarker(WorkflowStepState.RUNNING, WorkflowStepState.FAILED, false, true);
  }

  @Test
  void transition_to_cancelled_clears_the_attempt_marker() throws SQLException {
    assertMarker(WorkflowStepState.RUNNING, WorkflowStepState.CANCELLED, false, true);
  }

  @Test
  void transition_running_to_retry_wait_keeps_the_attempt_marker() throws SQLException {
    // RUNNING -> RETRY_WAIT is not a terminal outcome: the marker stays, so recovery still sees the
    // attempt as begun until it returns to RUNNING or reaches a terminal state
    assertMarker(WorkflowStepState.RUNNING, WorkflowStepState.RETRY_WAIT, false, false);
  }

  @Test
  void transition_running_to_waiting_for_approval_keeps_the_attempt_marker() throws SQLException {
    assertMarker(WorkflowStepState.RUNNING, WorkflowStepState.WAITING_FOR_APPROVAL, false, false);
  }

  @Test
  void transition_from_pending_never_sets_the_attempt_marker() throws SQLException {
    // not started: the marker must stay NULL on the PENDING -> READY edge
    assertMarker(WorkflowStepState.PENDING, WorkflowStepState.READY, false, false);
  }
}
