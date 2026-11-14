package com.example.agentflow.workflowexecution.persistence;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
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
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class ApprovalRepository {

  private static final String TABLE = "approvals";

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
