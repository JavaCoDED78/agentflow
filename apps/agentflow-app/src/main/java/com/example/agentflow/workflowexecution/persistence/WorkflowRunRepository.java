package com.example.agentflow.workflowexecution.persistence;

import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
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
 * conditional update guarded by the current {@code status}, the optimistic-lock {@code version} AND
 * the run's {@code lease_epoch} fence (invariant 4 / ADR-011): a zero-rows update &mdash; the run
 * is gone, the tenant is wrong, the state no longer matches the domain gate, the {@code version} is
 * stale, or the {@code lease_epoch} has moved (another worker re-claimed the run) &mdash; is mapped
 * to a {@link ConflictException} (HTTP 409), so two competing updates never silently overwrite each
 * other. The {@code version} is bumped inside the same statement ({@code version = version + 1}).
 *
 * <p>The run-level lease fence is the stale-owner guard (S07-05): the row must still hold the
 * {@code lease_epoch} the caller read in the tenant-scoped {@link #findState} lookup, so a worker
 * whose lease was re-taken by another worker &mdash; its epoch is now behind &mdash; matches zero
 * rows and is refused instead of overwriting the new owner. This is the same fencing primitive the
 * step write already uses (`WorkflowStepRunRepository`), now applied to the run write (ADR-011).
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
            "SELECT status, version, lease_epoch FROM " + TABLE + " WHERE id = ? AND tenant_id = ?",
            (rs, rowNum) ->
                new RunStateSnapshot(
                    WorkflowRunState.valueOf(rs.getString("status")),
                    rs.getInt("version"),
                    rs.getInt("lease_epoch")),
            runId,
            tenantId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /**
   * Move a run to {@code next} in place, guarded by the current {@code status}, the optimistic
   * {@code version} AND the current {@code lease_epoch} so the call is a conditional update
   * (invariant 4 / ADR-011). A zero-rows update &mdash; the run is gone, the tenant is wrong, the
   * state no longer matches the domain gate, the {@code version} is stale, or the {@code
   * lease_epoch} fence has moved (another worker re-claimed the run) &mdash; is mapped to a {@link
   * ConflictException} (409): one of two competing updates never silently overwrites the other. The
   * {@code version} is bumped inside the same statement.
   *
   * <p>The expected {@code tenantId}, {@code status}, {@code version} and {@code lease_epoch} come
   * from the snapshot a tenant-scoped {@link #findState} lookup returned (invariant 1), and are
   * re-checked in the {@code WHERE}, so a cross-tenant, stale or stale-epoch update is
   * indistinguishable from a missing row (S07-05, the stale-owner guard).
   *
   * @param runId the run to move
   * @param tenantId the owning tenant (re-checked in the {@code WHERE})
   * @param expectedState the state the row must still be in for the update to apply (the domain
   *     gate's {@code from} state)
   * @param expectedVersion the optimistic-lock counter the row must still hold
   * @param expectedLeaseEpoch the run's {@code lease_epoch} the row must still hold (invariant 5);
   *     a mismatch means the run was re-claimed by another worker and this write is refused
   * @param next the state to move the run to
   * @throws ConflictException if zero rows match (missing / cross-tenant / wrong state / stale
   *     version / stale lease epoch)
   */
  public void transition(
      UUID runId,
      UUID tenantId,
      WorkflowRunState expectedState,
      int expectedVersion,
      int expectedLeaseEpoch,
      WorkflowRunState next) {
    int affected =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " SET status = ?, version = version + 1, updated_at = now()"
                + " WHERE id = ? AND tenant_id = ? AND status = ? AND version = ? AND lease_epoch = ?",
            ps -> {
              ps.setString(1, next.name());
              ps.setObject(2, runId);
              ps.setObject(3, tenantId);
              ps.setString(4, expectedState.name());
              ps.setInt(5, expectedVersion);
              ps.setInt(6, expectedLeaseEpoch);
            });
    if (affected == 0) {
      throw new ConflictException(
          "run "
              + runId
              + " is out of date (optimistic version "
              + expectedVersion
              + ", lease epoch "
              + expectedLeaseEpoch
              + ") or no longer in state '"
              + expectedState
              + "'");
    }
  }

  /**
   * Atomically claim a run for the current worker (S07-03, the ADR-011 lease model).
   *
   * <p>The whole operation is one conditional {@code UPDATE ... RETURNING lease_epoch}: it sets
   * {@code lease_owner} to {@code workerId}, {@code lease_until} to {@code leaseUntil} and bumps
   * {@code lease_epoch} (the fencing counter, ADR-011) in a single statement. The {@code WHERE}
   * makes it atomic and tenant-scoped (invariant 1) and only matches a run that is claimable — not
   * in a terminal state and whose lease is absent or expired:
   *
   * <pre>{@code
   * UPDATE workflow_runs
   * SET lease_owner = ?, lease_until = ?, lease_epoch = lease_epoch + 1
   * WHERE id = ? AND tenant_id = ?
   *   AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
   *   AND (lease_owner IS NULL OR lease_until < now())
   * RETURNING lease_epoch
   * }</pre>
   *
   * <p>Because the conditional update holds the row lock for its duration, two workers racing on
   * the same run end up with exactly one winner &mdash; the loser re-evaluates against the
   * committed {@code lease_owner}/{@code lease_until} and matches zero rows (invariant 4, so the
   * append-only history can never record two owners for one run). A zero-row match &mdash; the run
   * is terminal, owned by a live worker, or a cross-tenant id &mdash; returns {@link
   * Optional#empty()} &mdash; indistinguishable from a missing run.
   *
   * @param tenantId the owning tenant, re-checked in the {@code WHERE} (invariant 1)
   * @param runId the run to claim
   * @param workerId this worker's instance id, stored on {@code lease_owner}
   * @param leaseUntil the instant the lease expires without a heartbeat (already computed through
   *     the injected {@link java.time.Clock} by the caller)
   * @return the new {@code lease_epoch} the winner must use as its fence, or {@link
   *     Optional#empty()} when no claimable row matched
   */
  public Optional<Integer> claim(
      UUID tenantId, UUID runId, UUID workerId, OffsetDateTime leaseUntil) {
    List<Integer> epochs =
        jdbc.query(
            "UPDATE "
                + TABLE
                + " SET lease_owner = ?, lease_until = ?, lease_epoch = lease_epoch + 1"
                + " WHERE id = ? AND tenant_id = ?"
                + " AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')"
                + " AND (lease_owner IS NULL OR lease_until < now())"
                + " RETURNING lease_epoch",
            (rs, rowNum) -> rs.getInt("lease_epoch"),
            workerId,
            leaseUntil,
            runId,
            tenantId);
    return epochs.isEmpty() ? Optional.empty() : Optional.of(epochs.get(0));
  }

  /**
   * Extend a run's lease by one lifetime, fenced so only the current owner can renew it (S07-04,
   * the ADR-011 lease model).
   *
   * <p>The whole operation is one conditional {@code UPDATE ... WHERE} that sets {@code
   * lease_until} to {@code leaseUntil} &mdash; already computed through the injected {@link
   * java.time.Clock} by the caller &mdash; but only when the row still belongs to {@code tenantId}
   * (invariant 1), is still owned by {@code workerId}, and still holds {@code lease_epoch =
   * expectedEpoch} (invariant 5, fencing). A heartbeat never bumps the epoch (only the claim does)
   * and never clears the lease, so a live worker simply pushes {@code lease_until} into the future
   * every cadence.
   *
   * <p>A zero-row match &mdash; the run was re-claimed (owner moved and/or {@code lease_epoch}
   * bumped), the run is gone, or a cross-tenant id &mdash; returns {@code 0}: an old owner cannot
   * renew its lease after it is taken (S07-04 acceptance criterion). The caller treats a {@code 0}
   * as a rejected heartbeat.
   *
   * @param tenantId the owning tenant, re-checked in the {@code WHERE} (invariant 1)
   * @param runId the run whose lease to extend
   * @param workerId the current owner this heartbeat must still match ({@code lease_owner})
   * @param expectedEpoch the {@code lease_epoch} the run must still hold for the write to apply
   * @param leaseUntil the new expiry, {@code now + duration-seconds} computed through the injected
   *     {@link java.time.Clock}
   * @return the number of rows matched ({@code 0} or {@code 1}); {@code 1} means the lease was
   *     renewed, {@code 0} means the heartbeat was rejected (stale owner / re-claimed / missing)
   */
  public int heartbeat(
      UUID tenantId, UUID runId, UUID workerId, int expectedEpoch, OffsetDateTime leaseUntil) {
    return jdbc.update(
        "UPDATE "
            + TABLE
            + " SET lease_until = ?"
            + " WHERE id = ? AND tenant_id = ?"
            + " AND lease_owner = ? AND lease_epoch = ?",
        ps -> {
          ps.setObject(1, leaseUntil);
          ps.setObject(2, runId);
          ps.setObject(3, tenantId);
          ps.setObject(4, workerId);
          ps.setInt(5, expectedEpoch);
        });
  }

  /**
   * Create a new run in status {@code QUEUED} with {@code attempt = 1} (S06-05, idempotent create
   * keyed by {@code idempotencyKey} in S06-06).
   *
   * <p>The run is created against the already-verified {@code workflowVersionId} (the pinned active
   * version, invariant 13) and {@code tenantId} (invariant 1, verified by the caller before this
   * call). {@code idempotency_key}, {@code idempotency_fingerprint} and {@code created_by} are
   * written with the row: a keyed create stores the client's {@code idempotency_key} and the
   * SHA-256 of the request payload, so a repeated create for the same {@code (tenant_id,
   * idempotency_key)} collides on the {@code UNIQUE (tenant_id, idempotency_key)} constraint
   * instead of producing a duplicate run. {@code idempotency_key} / {@code idempotency_fingerprint}
   * are {@code null} when the request carried no key, so only keyed creates participate in dedup
   * and mismatch detection.
   *
   * @param runId the run id (also the returned identity)
   * @param tenantId the owning tenant
   * @param workflowVersionId the pinned active version the run executes
   * @param idempotencyKey the client's idempotency key, or {@code null} for an unkeyed create
   * @param payloadFingerprint the lowercase-hex SHA-256 of the request payload, or {@code null}
   * @param createdBy the actor, recorded for the audit trail (nullable)
   * @return the created run id
   */
  public UUID create(
      UUID runId,
      UUID tenantId,
      UUID workflowVersionId,
      UUID idempotencyKey,
      String payloadFingerprint,
      String createdBy) {
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, tenant_id, workflow_version_id, status, attempt, "
            + "idempotency_key, idempotency_fingerprint, created_by) "
            + "VALUES (?, ?, ?, 'QUEUED', 1, ?, ?, ?)",
        ps -> {
          ps.setObject(1, runId);
          ps.setObject(2, tenantId);
          ps.setObject(3, workflowVersionId);
          bindNullableKey(ps, 4, idempotencyKey);
          bindNullableString(ps, 5, payloadFingerprint);
          bindNullableString(ps, 6, createdBy);
        });
    return runId;
  }

  /**
   * Read the row stored under a {@code (tenant_id, idempotency_key)} pair &mdash; the single source
   * of truth for a keyed create-run replay (S06-06). The {@code tenant_id} predicate is part of the
   * {@code WHERE} clause (invariant 1), so a cross-tenant key is indistinguishable from a missing
   * one.
   *
   * @param tenantId the owning tenant
   * @param idempotencyKey the key to look up
   * @return the stored {@link IdempotentRunView}, or {@link Optional#empty()} when no run matches
   */
  public Optional<IdempotentRunView> findByIdempotencyKey(UUID tenantId, UUID idempotencyKey) {
    List<IdempotentRunView> results =
        jdbc.query(
            "SELECT id, workflow_version_id, status, attempt, created_at, idempotency_fingerprint "
                + "FROM "
                + TABLE
                + " WHERE tenant_id = ? AND idempotency_key = ?",
            (rs, rowNum) ->
                new IdempotentRunView(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("workflow_version_id"),
                    rs.getString("status"),
                    rs.getInt("attempt"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getString("idempotency_fingerprint")),
            tenantId,
            idempotencyKey);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /**
   * Read a run's identity + pinned version + status by its pair {@code (tenant_id, id)} — the
   * tenant-scoped lookup the worker event loop uses to load the pinned version before it claims and
   * schedules the run (S07-08). The {@code tenant_id} predicate is part of the {@code WHERE} clause
   * (invariant 1), so a cross-tenant id is indistinguishable from a missing run.
   *
   * @param tenantId the owning tenant
   * @param runId the run to load
   * @return the {@link RunView} status snapshot, or {@link Optional#empty()} when no run matches
   *     {@code (tenant_id, runId)}
   */
  public Optional<RunView> findRun(UUID tenantId, UUID runId) {
    List<RunView> results =
        jdbc.query(
            "SELECT id, workflow_version_id, status, attempt, created_at FROM "
                + TABLE
                + " WHERE id = ? AND tenant_id = ?",
            (rs, rowNum) ->
                new RunView(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("workflow_version_id"),
                    WorkflowRunState.valueOf(rs.getString("status")),
                    rs.getInt("attempt"),
                    rs.getObject("created_at", OffsetDateTime.class)),
            runId,
            tenantId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /**
   * List the ids of a tenant's runs that are not in a terminal state (invariant 1 — scoped to the
   * tenant by {@code tenant_id}, most-recent-first). This is the read-only scan the worker's
   * scheduler uses to pick runs to make progress on ({@code WorkflowWorker#determineReadyRuns});
   * the actual claim happens in {@link #claim} inside the processing call, so the scan never
   * reserves a run.
   *
   * @param tenantId the owning tenant
   * @return the non-terminal run ids ordered most-recent-first (empty when none)
   */
  public List<UUID> findClaimableRuns(UUID tenantId) {
    List<UUID> results =
        jdbc.query(
            "SELECT id FROM "
                + TABLE
                + " WHERE tenant_id = ? AND status NOT IN "
                + "('SUCCEEDED', 'FAILED', 'CANCELLED') ORDER BY created_at DESC",
            (rs, rowNum) -> (UUID) rs.getObject("id"),
            tenantId);
    return results;
  }

  /**
   * The stale-run recovery scan (S07-09, ADR-011): read the ids + tenants of runs whose run-level
   * lease has <em>expired</em> — {@code lease_owner} is still set (some worker owned the run) but
   * {@code lease_until} is already past (that worker stopped heartbeating — crashed, drained or
   * terminated) — and the run is not in a terminal state (a terminal run is not recoverable). The
   * results are ordered oldest-expiry-first so the most stale runs are reclaimed first, and bounded
   * by {@code limit} (the recovery pass concurrency bound, S07-09) so one scan re-claims a limited
   * batch rather than table-scanning every run.
   *
   * <p>This is a <b>platform-wide lease scan</b>, not a single-tenant read: the recovery scheduler
   * is a background worker-infrastructure operation, not a user request, so there is no per-tenant
   * security context to scope it to. It selects runs by their lease state, and every re-claim is
   * still scoped to the run's own tenant through {@code RunLeaseService#claim(tenantId, runId)}
   * (invariant 1 — the re-claim's {@code WHERE} re-checks {@code tenant_id}, so a cross-tenant id
   * is indistinguishable from a missing run). The scan is backed by the {@code lease_until} index
   * (S07-02); without it this would be a Seq Scan over every run, which the operational
   * consequences in ADR-011 forbid.
   *
   * @param limit the maximum number of expired-lease runs to return (the recovery pass bound,
   *     S07-09)
   * @return at most {@code limit} {@link ExpiredLeaseRun} entries, oldest-expiry-first (empty when
   *     none is expired)
   */
  public List<ExpiredLeaseRun> findExpiredLeaseRuns(int limit) {
    List<ExpiredLeaseRun> results =
        jdbc.query(
            "SELECT id, tenant_id FROM "
                + TABLE
                + " WHERE lease_owner IS NOT NULL AND lease_until < now()"
                + " AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')"
                + " ORDER BY lease_until ASC LIMIT ?",
            (rs, rowNum) ->
                new ExpiredLeaseRun((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id")),
            limit);
    return results;
  }

  /** Binds a nullable {@code UUID} so an absent key is stored as SQL {@code NULL}. */
  private static void bindNullableKey(PreparedStatement ps, int index, UUID value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.OTHER);
    } else {
      ps.setObject(index, value);
    }
  }

  /** Binds a nullable {@code String} so an absent value is stored as SQL {@code NULL}. */
  private static void bindNullableString(PreparedStatement ps, int index, String value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }
}
