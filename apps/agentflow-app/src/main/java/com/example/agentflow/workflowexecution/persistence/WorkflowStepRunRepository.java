package com.example.agentflow.workflowexecution.persistence;

import com.example.agentflow.workflowexecution.domain.Checkpoint;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
   * 6-argument transition, kept for the existing callers and tests; records no error metadata. The
   * error-recording overload is used to persist a step's {@link
   * com.example.agentflow.common.ErrorClass} and safe reason when the step fails (S07-11); both
   * share the single conditional update below.
   */
  public void transition(
      UUID runId,
      UUID tenantId,
      String stepKey,
      WorkflowStepState expectedState,
      int expectedLeaseEpoch,
      WorkflowStepState next) {
    transition(runId, tenantId, stepKey, expectedState, expectedLeaseEpoch, next, null, null);
  }

  /**
   * Transition a step from {@code expectedState} to {@code next} under a lease check and a single
   * conditional update (invariants 2 and 4), recording the error metadata of a fault.
   *
   * <p>{@code errorCode} / {@code errorSummary} are written only when non-null (COALESCE), so a
   * transition that carries no error (a retry or a success) never clobbers the error a previous
   * failed attempt left; the error is set on the faulting transition and cleared by the {@code
   * S07-14} attempt-lifecycle. This is how {@code workflowexecution} persists the {@link
   * com.example.agentflow.common.ErrorClass} token and the short redacted reason for a step that
   * failed (S07-11).
   *
   * @param errorCode the {@code ErrorClass#errorClass()} token for {@code next} (nullable)
   * @param errorSummary the short redacted description ({@code null} or blank preserves the
   *     existing value)
   */
  public void transition(
      UUID runId,
      UUID tenantId,
      String stepKey,
      WorkflowStepState expectedState,
      int expectedLeaseEpoch,
      WorkflowStepState next,
      String errorCode,
      String errorSummary) {
    int affected =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " s SET status = ?, updated_at = now()"
                + " , attempt_started_at = CASE WHEN ? THEN now() WHEN ? THEN NULL ELSE attempt_started_at END"
                + " , error_code = COALESCE(?, error_code)"
                + " , error_summary = COALESCE(?, error_summary)"
                + " FROM workflow_runs r"
                + " WHERE s.run_id = r.id AND r.tenant_id = ? AND s.run_id = ?"
                + " AND s.step_key = ? AND s.status = ? AND s.lease_epoch = ?",
            ps -> {
              boolean beginAttempt = next == WorkflowStepState.RUNNING;
              boolean completeAttempt =
                  expectedState == WorkflowStepState.RUNNING && next.isTerminal();
              ps.setString(1, next.name());
              ps.setBoolean(2, beginAttempt);
              ps.setBoolean(3, completeAttempt);
              ps.setString(4, errorCode);
              ps.setString(5, errorSummary);
              ps.setObject(6, tenantId);
              ps.setObject(7, runId);
              ps.setString(8, stepKey);
              ps.setString(9, expectedState.name());
              ps.setInt(10, expectedLeaseEpoch);
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

  /**
   * Persist a completed step's checkpoint (S07-07): in place, guarded by the parent run's {@code
   * tenant_id}, the current {@code lease_epoch} fence and the step being {@code SUCCEEDED}
   * (invariants 1 / 4 / 5). The output is written to {@code output_inline} and its schema version
   * to {@code output_schema_version}; {@code output_ref} is never written here, so it stays {@code
   * NULL} and the row satisfies the {@code output_ref / output_inline} mutual-exclusion CHECK added
   * by {@code V3_3_15__add_step_checkpoint_outputs.sql}. A zero-rows update &mdash; the step is
   * gone, the parent run is a different tenant, the step is not {@code SUCCEEDED}, or the {@code
   * lease_epoch} fence has moved &mdash; is mapped to a {@link ConflictException} (409), so a stale
   * or non-terminal worker can never persist a bogus checkpoint. The checkpoint is per {@code
   * (run_id, step_key)}, so it is never reused across runs.
   *
   * @param tenantId the owning tenant (matched against the parent run in the {@code WHERE})
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @param expectedLeaseEpoch the fence the row must still hold
   * @param checkpoint the redacted output + its schema version to store
   * @throws ConflictException if zero rows match (missing / cross-tenant / not SUCCEEDED / stale
   *     lease epoch)
   */
  public void saveCheckpoint(
      UUID tenantId, UUID runId, String stepKey, int expectedLeaseEpoch, Checkpoint checkpoint) {
    int affected =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " s SET output_schema_version = ?, output_inline = ?, updated_at = now()"
                + " FROM workflow_runs r"
                + " WHERE s.run_id = r.id AND r.tenant_id = ? AND s.run_id = ?"
                + " AND s.step_key = ? AND s.status = 'SUCCEEDED' AND s.lease_epoch = ?",
            ps -> {
              ps.setString(1, checkpoint.schemaVersion());
              ps.setString(2, checkpoint.output());
              ps.setObject(3, tenantId);
              ps.setObject(4, runId);
              ps.setString(5, stepKey);
              ps.setInt(6, expectedLeaseEpoch);
            });
    if (affected == 0) {
      throw new ConflictException(
          "step '"
              + stepKey
              + "' on run "
              + runId
              + " is not checkpointable (not SUCCEEDED) or out of date (lease epoch "
              + expectedLeaseEpoch
              + ")");
    }
  }

  /**
   * Read a completed step's stored checkpoint (S07-07), scoped to the parent run's tenant. The
   * {@code tenant_id} predicate is part of the {@code JOIN ... WHERE} clause (invariant 1), so a
   * step whose parent run belongs to another tenant returns {@link Optional#empty()} &mdash;
   * indistinguishable from a step that never completed. This backs the reuse decision: recovery
   * asks whether a matching checkpoint exists before re-executing the step.
   *
   * @param tenantId the owning tenant (matched against the parent run in the {@code WHERE})
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @return the checkpoint, or {@link Optional#empty()} when no step matches for this tenant
   */
  public Optional<CheckpointSnapshot> findCheckpoint(UUID tenantId, UUID runId, String stepKey) {
    List<CheckpointSnapshot> results =
        jdbc.query(
            "SELECT s.output_schema_version, s.output_inline FROM "
                + TABLE
                + " s JOIN workflow_runs r ON s.run_id = r.id"
                + " WHERE s.run_id = ? AND s.step_key = ? AND r.tenant_id = ?",
            (rs, rowNum) ->
                new CheckpointSnapshot(
                    rs.getString("output_schema_version"), rs.getString("output_inline")),
            runId,
            stepKey,
            tenantId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /**
   * Materialize one {@code PENDING} step row for a graph node of a run (S07-08, the worker event
   * loop). The row references the run (an FK to {@code workflow_runs.id}); tenant isolation flows
   * through that parent — the worker only calls this for a run it has already verified is owned by
   * {@code tenantId} through the tenant-scoped {@code WorkflowRunRepository#findRun} lookup
   * (invariant 1), and every read joins {@code workflow_runs} again and re-checks the parent's
   * {@code tenant_id}. The {@code unique (run_id, step_key, attempt)} guard (S05-08) makes a second
   * materialization of the same node a collision rather than a silent duplicate; the worker
   * materializes each node at most once per run. The {@code lease_epoch} is seeded with the run's
   * claimed epoch (the worker passes the fence it just acquired) so the step's own fence matches
   * the epoch the worker then sends on its transitions (invariant 5).
   *
   * @param runId the run the step belongs to
   * @param tenantId the owning tenant, verified to own {@code runId} by the caller
   * @param stepKey the graph node the step runs
   * @param leaseEpoch the run's claimed lease epoch, seeded as the step fence
   * @param createdBy the actor recorded for the audit trail (nullable)
   */
  public void createStep(
      UUID runId, UUID tenantId, String stepKey, int leaseEpoch, String createdBy) {
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, run_id, step_key, status, attempt, lease_epoch, created_by) "
            + "VALUES (?, ?, ?, 'PENDING', 1, ?, ?)",
        ps -> {
          ps.setObject(1, UUID.randomUUID());
          ps.setObject(2, runId);
          ps.setString(3, stepKey);
          ps.setInt(4, leaseEpoch);
          if (createdBy == null) {
            ps.setNull(5, Types.VARCHAR);
          } else {
            ps.setString(5, createdBy);
          }
        });
  }

  /**
   * Read every step row of a run with its status and fence, tenant-scoped (S07-08). The {@code
   * tenant_id} predicate is part of the {@code JOIN ... WHERE} clause (invariant 1), so a run owned
   * by another tenant is indistinguishable from a missing one and returns no rows. The ordering is
   * the node creation order, which for a freshly materialized run is the graph's definition order
   * (a stable, deterministic step-selection order).
   *
   * @param tenantId the owning tenant (matched against the parent run in the {@code WHERE})
   * @param runId the run whose step rows to list
   * @return the step rows ordered most-recent-first (empty when the run has no steps for this
   *     tenant)
   */
  public List<StepStatusRow> findStepStatuses(UUID tenantId, UUID runId) {
    List<StepStatusRow> results =
        jdbc.query(
            "SELECT s.step_key, s.status, s.lease_epoch FROM "
                + TABLE
                + " s "
                + "JOIN workflow_runs r ON s.run_id = r.id "
                + "WHERE s.run_id = ? AND r.tenant_id = ? ORDER BY s.created_at DESC",
            (rs, rowNum) ->
                new StepStatusRow(
                    rs.getString("step_key"),
                    WorkflowStepState.valueOf(rs.getString("status")),
                    rs.getInt("lease_epoch")),
            runId,
            tenantId);
    return results;
  }

  /**
   * Read the stored inline output of every {@code SUCCEEDED} step of a run, tenant-scoped (S07-08),
   * as a {@code step_key -> output} map the executor feeds into downstream nodes (the {@code input}
   * wiring of the DSL). The {@code tenant_id} predicate is part of the {@code JOIN ... WHERE}
   * clause (invariant 1); only {@code SUCCEEDED} steps with a non-null inline output are returned,
   * so a step that is not done (or whose output was externalized to an {@code output_ref}) is
   * absent.
   *
   * @param tenantId the owning tenant (matched against the parent run in the {@code WHERE})
   * @param runId the run whose completed outputs to read
   * @return the map, possibly empty (no completed step has an inline output yet)
   */
  public Map<String, String> findStepOutputs(UUID tenantId, UUID runId) {
    Map<String, String> outputs = new LinkedHashMap<>();
    jdbc.query(
        "SELECT s.step_key, s.output_inline FROM "
            + TABLE
            + " s "
            + "JOIN workflow_runs r ON s.run_id = r.id "
            + "WHERE s.run_id = ? AND r.tenant_id = ? AND s.status = 'SUCCEEDED' "
            + "AND s.output_inline IS NOT NULL",
        (rs, rowNum) -> outputs.put(rs.getString("step_key"), rs.getString("output_inline")),
        runId,
        tenantId);
    return outputs;
  }
}
