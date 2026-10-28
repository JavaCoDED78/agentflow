package com.example.agentflow.workflowexecution.persistence;

import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped read / conditional-write path for the {@code workflow_step_runs} table, owned by
 * the {@code workflowexecution} module.
 *
 * <p>The step table stores no {@code tenant_id} and no optimistic-lock {@code version}: tenant
 * isolation flows through the parent run (a step references it through {@code run_id}, an FK to
 * {@code workflow_runs.id}), and the {@code lease_epoch} column is the fencing primitive instead of
 * a version (per {@code V3_3_6__create_workflow_runs_and_steps.sql}). So every read and every write
 * here joins {@code workflow_runs} and filters by the parent's {@code tenant_id} (invariant 1): a
 * cross-tenant step is indistinguishable from a missing one.
 *
 * <p>Every write is a conditional update guarded by the current {@code status} AND the current
 * {@code lease_epoch} (invariant 4 / fencing). A zero-rows update &mdash; the step is gone, the
 * parent run is a different tenant, the state no longer matches the domain gate, or the {@code
 * lease_epoch} fence has moved (another worker renewed the lease) &mdash; is mapped to a {@link
 * ConflictException} (409), so two competing step updates never silently overwrite each other.
 *
 * <p>The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * (S01-02); the transition service fails loudly (reports {@code 503}) when the repository is
 * absent.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class WorkflowStepRunRepository {

  private static final String TABLE = "workflow_step_runs";

  private final JdbcTemplate jdbc;

  public WorkflowStepRunRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Read a step's transition decision data by its pair {@code (run_id, step_key)} scoped to the
   * parent run's tenant. The {@code tenant_id} predicate is part of the {@code JOIN ... WHERE}
   * clause (invariant 1), so a step whose parent run belongs to another tenant returns {@link
   * Optional#empty()} &mdash; indistinguishable from a missing step.
   *
   * @param tenantId the owning tenant (matched against the parent run in the {@code WHERE})
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @return the snapshot, or {@link Optional#empty()} when no step matches for this tenant
   */
  public Optional<StepStateSnapshot> findState(UUID tenantId, UUID runId, String stepKey) {
    List<StepStateSnapshot> results =
        jdbc.query(
            "SELECT s.id, s.status, s.lease_epoch FROM "
                + TABLE
                + " s JOIN workflow_runs r ON s.run_id = r.id"
                + " WHERE s.run_id = ? AND s.step_key = ? AND r.tenant_id = ?",
            (rs, rowNum) ->
                new StepStateSnapshot(
                    rs.getString("id"),
                    WorkflowStepState.valueOf(rs.getString("status")),
                    rs.getInt("lease_epoch")),
            runId,
            stepKey,
            tenantId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /**
   * Move a step to {@code next} in place, guarded by the parent run's {@code tenant_id}, the
   * current {@code status} and the current {@code lease_epoch} so the call is a conditional update
   * (invariant 4 / fencing). A zero-rows update &mdash; the step is gone, the parent run is a
   * different tenant, the state no longer matches the domain gate, or the {@code lease_epoch} fence
   * has moved &mdash; is mapped to a {@link ConflictException} (409). The parent's {@code
   * tenant_id} is re-checked in the {@code JOIN WHERE}, so a cross-tenant update is
   * indistinguishable from a missing row.
   *
   * @param runId the run the step belongs to
   * @param tenantId the owning tenant (matched against the parent run in the {@code WHERE})
   * @param stepKey the graph node the step runs
   * @param expectedState the state the row must still be in for the update to apply (the domain
   *     gate's {@code from} state)
   * @param expectedLeaseEpoch the fence the row must still hold
   * @param next the state to move the step to
   * @throws ConflictException if zero rows match (missing / cross-tenant / wrong state / stale
   *     lease epoch)
   */
  public void transition(
      UUID runId,
      UUID tenantId,
      String stepKey,
      WorkflowStepState expectedState,
      int expectedLeaseEpoch,
      WorkflowStepState next) {
    int affected =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " s SET status = ?, updated_at = now()"
                + " FROM workflow_runs r"
                + " WHERE s.run_id = r.id AND r.tenant_id = ? AND s.run_id = ?"
                + " AND s.step_key = ? AND s.status = ? AND s.lease_epoch = ?",
            ps -> {
              ps.setString(1, next.name());
              ps.setObject(2, tenantId);
              ps.setObject(3, runId);
              ps.setString(4, stepKey);
              ps.setString(5, expectedState.name());
              ps.setInt(6, expectedLeaseEpoch);
            });
    if (affected == 0) {
      throw new ConflictException(
          "step '"
              + stepKey
              + "' on run "
              + runId
              + " is out of date (lease epoch "
              + expectedLeaseEpoch
              + ") or no longer in state '"
              + expectedState
              + "'");
    }
  }
}
