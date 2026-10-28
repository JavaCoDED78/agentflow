package com.example.agentflow.workflowexecution.persistence;

import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped read / conditional-write path for the {@code workflow_runs} table, owned by the
 * {@code workflowexecution} module.
 *
 * <p>This is the single write path for a run's state. Every read is tenant-filtered in the SQL
 * itself (invariant 1): a cross-tenant id is indistinguishable from a missing row. Every write is a
 * conditional update guarded by the current {@code status} AND the optimistic-lock {@code version}
 * (invariant 4): a zero-rows update &mdash; the run is gone, the tenant is wrong, the state no
 * longer matches the domain gate, or the {@code version} is stale because another writer won the
 * transition &mdash; is mapped to a {@link ConflictException} (HTTP 409), so two competing updates
 * never silently overwrite each other. The {@code version} is bumped inside the same statement
 * ({@code version = version + 1}).
 *
 * <p>The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * (S01-02); the transition service fails loudly (reports {@code 503}) when the repository is
 * absent.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class WorkflowRunRepository {

  private static final String TABLE = "workflow_runs";

  private final JdbcTemplate jdbc;

  public WorkflowRunRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Read a run's transition decision data by its pair {@code (tenant_id, id)} &mdash; the {@link
   * WorkflowRunState} to run the domain gate against and the optimistic-lock {@code version}. A
   * cross-tenant id is indistinguishable from a missing one: the {@code tenant_id} predicate is
   * part of the {@code WHERE} clause, so the query returns {@link Optional#empty()} for the wrong
   * tenant (invariant 1).
   *
   * @param tenantId the owning tenant
   * @param runId the run whose state to read
   * @return the snapshot, or {@link Optional#empty()} when no run matches {@code (tenant_id,
   *     runId)}
   */
  public Optional<RunStateSnapshot> findState(UUID tenantId, UUID runId) {
    List<RunStateSnapshot> results =
        jdbc.query(
            "SELECT status, version FROM " + TABLE + " WHERE id = ? AND tenant_id = ?",
            (rs, rowNum) ->
                new RunStateSnapshot(
                    WorkflowRunState.valueOf(rs.getString("status")), rs.getInt("version")),
            runId,
            tenantId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /**
   * Move a run to {@code next} in place, guarded by the current {@code status} AND the optimistic
   * {@code version} so the call is a conditional update (invariant 4). A zero-rows update &mdash;
   * the run is gone, the tenant is wrong, the state no longer matches the domain gate, or the
   * {@code version} is stale (another writer changed the run since it was read) &mdash; is mapped
   * to a {@link ConflictException} (409): one of two competing updates never silently overwrites
   * the other. The {@code version} is bumped inside the same statement.
   *
   * <p>The expected {@code tenantId}, {@code status} and {@code version} come from the snapshot a
   * tenant-scoped {@link #findState} lookup returned (invariant 1), and are re-checked in the
   * {@code WHERE}, so a cross-tenant or stale update is indistinguishable from a missing row.
   *
   * @param runId the run to move
   * @param tenantId the owning tenant (re-checked in the {@code WHERE})
   * @param expectedState the state the row must still be in for the update to apply (the domain
   *     gate's {@code from} state)
   * @param expectedVersion the optimistic-lock counter the row must still hold
   * @param next the state to move the run to
   * @throws ConflictException if zero rows match (missing / cross-tenant / wrong state / stale
   *     version)
   */
  public void transition(
      UUID runId,
      UUID tenantId,
      WorkflowRunState expectedState,
      int expectedVersion,
      WorkflowRunState next) {
    int affected =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " SET status = ?, version = version + 1, updated_at = now()"
                + " WHERE id = ? AND tenant_id = ? AND status = ? AND version = ?",
            ps -> {
              ps.setString(1, next.name());
              ps.setObject(2, runId);
              ps.setObject(3, tenantId);
              ps.setString(4, expectedState.name());
              ps.setInt(5, expectedVersion);
            });
    if (affected == 0) {
      throw new ConflictException(
          "run "
              + runId
              + " is out of date (optimistic version "
              + expectedVersion
              + ") or no longer in state '"
              + expectedState
              + "'");
    }
  }
}
