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
