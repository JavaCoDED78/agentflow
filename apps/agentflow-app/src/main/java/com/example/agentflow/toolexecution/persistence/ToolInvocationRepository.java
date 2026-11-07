package com.example.agentflow.toolexecution.persistence;

import com.example.agentflow.toolexecution.domain.ToolInvocationRecord;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Write path (and tenant-scoped investigation read) for the {@code tool_invocations} table the
 * {@code toolexecution} module owns (S08-13).
 *
 * <p>Each {@link #insert} appends exactly one invocation-history row — the table is append-only
 * like the audit trail it complements. The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * (S01-02); the {@code ToolInvocationHistoryService} fails loudly when the repository is absent,
 * and the bounded executor degrades that into a logged warning so a metadata write can never change
 * a call's outcome.
 *
 * <p>The {@code run_id} column is a plain value, not a foreign key: {@code workflow_runs} is owned
 * by {@code workflowexecution}, and a cross-module FK would couple the schemas. Tenant isolation
 * (invariant 1) lives on the row itself — {@code tenant_id} is copied from the verified call
 * context — and the predicate is part of every read, so another tenant's invocation history is
 * indistinguishable from a missing one.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class ToolInvocationRepository {

  private static final String TABLE = "tool_invocations";

  private final JdbcTemplate jdbc;
  private final ToolInvocationRowMapper mapper = new ToolInvocationRowMapper();

  public ToolInvocationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Append one invocation-history row. The row is metadata only (never arguments or payload), so
   * the insert carries nothing that needs redaction (invariant 8); {@code created_at} is assigned
   * by the database default.
   *
   * @param record the concluded call's metadata row
   */
  public void insert(ToolInvocationRecord record) {
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, tenant_id, run_id, step_run_id, tool_name, tool_version, risk_class,"
            + " attempt, outcome, latency_ms, output_reference, error_class, error_summary)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, record.id());
          ps.setObject(2, record.tenantId());
          ps.setObject(3, record.runId());
          ps.setString(4, record.stepRunId());
          ps.setString(5, record.toolName());
          ps.setString(6, record.toolVersion());
          ps.setString(7, record.riskClass().name());
          ps.setInt(8, record.attempt());
          ps.setString(9, record.outcome().name());
          ps.setLong(10, record.latencyMs());
          ps.setString(11, record.outputReference());
          ps.setString(12, record.errorClass());
          ps.setString(13, record.errorSummary());
        });
  }

  /**
   * Read a run's invocation history in append order. The {@code tenant_id} predicate is part of the
   * {@code WHERE} clause, so a cross-tenant run id is indistinguishable from a missing one (the
   * 403/404 policy, invariant 1).
   *
   * @param tenantId the owning tenant
   * @param runId the run whose invocation history is read
   * @return the invocation rows in {@code created_at} append order; empty when none
   */
  public List<ToolInvocationRecord> findByTenantAndRun(UUID tenantId, UUID runId) {
    return jdbc.query(
        "SELECT id, tenant_id, run_id, step_run_id, tool_name, tool_version, risk_class,"
            + " attempt, outcome, latency_ms, output_reference, error_class, error_summary"
            + " FROM "
            + TABLE
            + " WHERE tenant_id = ? AND run_id = ? ORDER BY created_at, attempt",
        mapper,
        tenantId,
        runId);
  }
}
