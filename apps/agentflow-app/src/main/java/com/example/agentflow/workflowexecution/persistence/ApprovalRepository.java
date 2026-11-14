package com.example.agentflow.workflowexecution.persistence;

import com.example.agentflow.workflowexecution.domain.ConflictException;
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
 * The insert path for the {@code approvals} table (S11-02 / S11-03), owned by the {@code
 * workflowexecution} module.
 *
 * <p>A row is created at a {@code HUMAN_APPROVAL} pause (S11-03): it records <em>what</em> needs
 * approving (a short, redacted {@code action} summary), the payload it pins ({@code
 * payload_fingerprint}, the digest the decision is bound to), and <em>who</em> requested it — never
 * the secret payload itself (invariant 8). At most one {@code PENDING} row per {@code (run_id,
 * step_key)} is allowed (the partial unique index in {@code V3_3_22__create_approvals.sql},
 * S11-02), so a duplicate pause on the same step is a unique violation rather than a conflicting
 * active approval (at-least-once, ADR-003).
 *
 * <p>Every write is tenant-scoped: {@code tenant_id} is written with the row and matched by the
 * parent run's tenant in every lookup, so another tenant's approval is indistinguishable from a
 * missing one (invariant 1).
 *
 * <p>The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * (S01-02).
 *
 * <p>The decision path (S11-04) is the approve endpoint's half of the table: a tenant-scoped read
 * ({@link #find}), the conditional decision write guarded on {@code status = 'PENDING'} and the
 * optimistic-lock {@code version} ({@link #recordApprove} — a zero-rows outcome is a {@link
 * ConflictException}, so two competing decisions never silently overwrite each other, invariant 4),
 * and the keyed lookup ({@link #findByIdempotencyKey}) the idempotent replay answers from. The
 * idempotency columns are {@code V3_3_23} (S11-04).
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class ApprovalRepository {

  private static final String TABLE = "approvals";

  private static final String COLUMNS =
      "id, run_id, step_key, status, expiry, version, decision_actor, decision_at,"
          + " decision_comment, idempotency_key, idempotency_fingerprint";

  private final JdbcTemplate jdbc;

  public ApprovalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Insert a new {@code PENDING} approval (S11-03) and return its identity.
   *
   * @param tenantId the owning tenant (the run it gates is already verified to belong to it)
   * @param runId the run the approval gates (its tenant isolation flows through the parent run)
   * @param stepKey the graph node / step the request is gating (matches {@code
   *     workflow_step_runs.step_key})
   * @param action a short, human-readable summary of the gated side effect (never a secret —
   *     invariant 8)
   * @param payloadFingerprint the lowercase-hex SHA-256 digest the decision is bound to (S11-06
   *     refines it to the exact tool arguments / tool / version; it is a digest, never the
   *     arguments)
   * @param requester the actor who triggered the gated side effect (nullable when begun without
   *     one)
   * @param createdBy the actor that created the request (nullable when begun without one)
   * @return the created {@link ApprovalRecord} (its id, run and step key)
   */
  public ApprovalRecord create(
      UUID tenantId,
      UUID runId,
      String stepKey,
      String action,
      String payloadFingerprint,
      String requester,
      String createdBy) {
    UUID id = java.util.UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, tenant_id, run_id, step_key, status, action, payload_fingerprint, requester, created_by)"
            + " VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setObject(3, runId);
          ps.setString(4, stepKey);
          ps.setString(5, action);
          ps.setString(6, payloadFingerprint);
          bindNullableString(ps, 7, requester);
          bindNullableString(ps, 8, createdBy);
        });
    return new ApprovalRecord(id, runId, stepKey);
  }

  /**
   * The tenant-scoped read behind the approve endpoint (S11-04).
   *
   * <p>Scoped on the full triple {@code (tenant_id, run_id, id)}: an approval of another tenant —
   * or of another run under a guessed id — is indistinguishable from a missing one and answers
   * {@link Optional#empty()} (invariant 1), so the caller renders a {@code 404}, never a leak.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param runId the run named in the endpoint path
   * @param approvalId the approval record named in the endpoint path
   * @return the decision-relevant row, or {@link Optional#empty()} when no row matches the triple
   */
  public Optional<ApprovalRow> find(UUID tenantId, UUID runId, UUID approvalId) {
    return queryOne(
        "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE tenant_id = ? AND run_id = ? AND id = ?",
        tenantId,
        runId,
        approvalId);
  }

  /**
   * The keyed lookup the idempotent replay answers from (S11-04, {@code V3_3_23}): the row already
   * decided under {@code idempotencyKey} for this tenant, if any.
   *
   * <p>Tenant-scoped exactly like the create-run idempotency lookup (S06-06): another tenant's row
   * under the same key is indistinguishable from absent, so a key can never replay a decision
   * across tenants (invariant 1).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param idempotencyKey the client key to resolve
   * @return the row decided under the key, or {@link Optional#empty()} when none exists for this
   *     tenant
   */
  public Optional<ApprovalRow> findByIdempotencyKey(UUID tenantId, UUID idempotencyKey) {
    return queryOne(
        "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE tenant_id = ? AND idempotency_key = ?",
        tenantId,
        idempotencyKey);
  }

  /**
   * Record an {@code APPROVED} decision on a {@code PENDING} approval (S11-04), the conditional
   * write half of the single decision path.
   *
   * <p>The update is guarded on {@code status = 'PENDING'} and the optimistic-lock {@code version};
   * a zero-rows outcome — the row moved (another decision won the race), the version bumped, or the
   * row is gone — is a {@link ConflictException}, so two competing decisions never silently
   * overwrite each other (invariant 4, at-least-once ADR-003). The idempotency pair is stored with
   * the decision when the caller chose a key; the {@code approvals_tenant_idempotency_key} unique
   * guard ({@code V3_3_23}) makes a concurrent decision under the same key a duplicate-key error
   * the service answers as a replay/conflict rather than a second row.
   *
   * @param tenantId the owning tenant
   * @param runId the run the approval gates
   * @param approvalId the approval record to decide
   * @param decisionActor the deciding actor (never a token)
   * @param decisionAt the decision instant from the injectable {@code Clock} (invariant 10)
   * @param decisionComment the free-text note, or {@code null}
   * @param idempotencyKey the client key, or {@code null} for an unkeyed decision
   * @param idempotencyFingerprint the digest of the canonical decision payload, or {@code null}
   * @param expectedVersion the version the read-path observed (optimistic lock)
   * @throws ConflictException if zero rows match (the decision raced or the row moved)
   */
  public void recordApprove(
      UUID tenantId,
      UUID runId,
      UUID approvalId,
      String decisionActor,
      OffsetDateTime decisionAt,
      String decisionComment,
      UUID idempotencyKey,
      String idempotencyFingerprint,
      int expectedVersion) {
    int affected =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " SET status = 'APPROVED', decision_actor = ?, decision_at = ?,"
                + " decision_comment = ?, idempotency_key = ?, idempotency_fingerprint = ?,"
                + " version = version + 1, updated_at = ?"
                + " WHERE id = ? AND tenant_id = ? AND run_id = ? AND status = 'PENDING'"
                + " AND version = ?",
            ps -> {
              ps.setString(1, decisionActor);
              ps.setObject(2, decisionAt);
              bindNullableString(ps, 3, decisionComment);
              if (idempotencyKey == null) {
                ps.setNull(4, Types.OTHER);
              } else {
                ps.setObject(4, idempotencyKey);
              }
              bindNullableString(ps, 5, idempotencyFingerprint);
              ps.setObject(6, decisionAt);
              ps.setObject(7, approvalId);
              ps.setObject(8, tenantId);
              ps.setObject(9, runId);
              ps.setInt(10, expectedVersion);
            });
    if (affected == 0) {
      throw new ConflictException(
          "approval "
              + approvalId
              + " on run "
              + runId
              + " is out of date (version "
              + expectedVersion
              + ") or no longer PENDING — another decision won the race");
    }
  }

  /**
   * Runs a lookup expected to return at most one row and maps it to {@link ApprovalRow}.
   *
   * @param sql the parameterized lookup (tenant-scoped by every caller)
   * @param args the bind arguments
   * @return the single row, or {@link Optional#empty()} when none matches
   */
  private Optional<ApprovalRow> queryOne(String sql, Object... args) {
    List<ApprovalRow> results =
        jdbc.query(
            sql,
            (rs, rowNum) ->
                new ApprovalRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("run_id", UUID.class),
                    rs.getString("step_key"),
                    rs.getString("status"),
                    rs.getObject("expiry", OffsetDateTime.class),
                    rs.getInt("version"),
                    rs.getString("decision_actor"),
                    rs.getObject("decision_at", OffsetDateTime.class),
                    rs.getString("decision_comment"),
                    rs.getObject("idempotency_key", UUID.class),
                    rs.getString("idempotency_fingerprint")),
            args);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
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
