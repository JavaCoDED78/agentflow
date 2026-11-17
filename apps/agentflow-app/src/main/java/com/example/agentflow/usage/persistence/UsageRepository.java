package com.example.agentflow.usage.persistence;

import com.example.agentflow.usage.domain.UsageRecordDto;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Write path (and tenant-scoped investigation read) for the {@code usage_records} table the {@code
 * usage} module owns (S12-01 / {@code V3_3_24}).
 *
 * <p>The bean is only registered when a {@link DataSource} exists ({@code @ConditionalOnBean(
 * DataSource.class)}), so the application still boots without a database (S01-02); the {@link
 * com.example.agentflow.usage.application.UsageMeteringService} fails loudly when the repository is
 * absent, and the caller treats a metadata write as non-fatal observability. The {@code run_id} /
 * {@code tenant_id} columns are plain values, not foreign keys — {@code workflow_runs} is owned by
 * {@code workflowexecution} and a cross-module FK would couple the schemas. Tenant isolation
 * (invariant 1) lives on the row itself: {@code tenant_id} is copied from the verified call
 * context, and the predicate is part of every read, so another tenant's usage is indistinguishable
 * from a missing one.
 *
 * <p>The token / cost columns are nullable {@code BIGINT} / {@code NUMERIC}. They are bound with
 * {@link java.sql.PreparedStatement#setObject(int, java.lang.Object)} (not {@code setLong} / {@code
 * setBigDecimal}), so a {@code null} value is written as SQL NULL — an unknown token count is
 * stored as unknown, never coerced to {@code 0} (S12-02).
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class UsageRepository {

  private static final String TABLE = "usage_records";

  private final JdbcTemplate jdbc;
  private final RowMapper<UsageRecordDto> mapper = new UsageRowMapper();

  public UsageRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Append one measurement row. The row is metadata only (never the model's arguments or payload),
   * so the insert carries nothing that needs redaction (invariant 8); {@code created_at} is
   * assigned by the database default. The {@code metadata} column is bound as JSONB so a stored
   * object can never be misinterpreted as free text.
   *
   * @param record the measurement to persist
   */
  public void insert(UsageRecordDto record) {
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, tenant_id, run_id, step_run_id, attempt, usage_type, measurement,"
            + " provider, model, tool_name, tool_version, input_tokens, output_tokens,"
            + " estimated_cost, provider_cost, unit, currency, provider_request_id, provider_metadata)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, record.id());
          ps.setObject(2, record.tenantId());
          ps.setObject(3, record.runId());
          ps.setString(4, record.stepRunId());
          ps.setInt(5, record.attempt());
          ps.setString(6, record.usageType().name());
          ps.setString(7, record.measurement().name());
          ps.setString(8, record.provider());
          ps.setString(9, record.model());
          ps.setString(10, record.toolName());
          ps.setString(11, record.toolVersion());
          ps.setObject(12, record.inputTokens());
          ps.setObject(13, record.outputTokens());
          ps.setObject(14, record.estimatedCost());
          ps.setObject(15, record.providerCost());
          ps.setString(16, record.unit());
          ps.setString(17, record.currency());
          ps.setString(18, record.providerRequestId());
          ps.setString(19, record.providerMetadata());
        });
  }

  /**
   * Read a run's measurements in append order. The {@code tenant_id} predicate is part of the
   * {@code WHERE} clause, so a cross-tenant run id is indistinguishable from a missing one (the
   * 403/404 policy, invariant 1).
   *
   * @param tenantId the owning tenant
   * @param runId the run whose measurements are read
   * @return the measurement rows in {@code created_at, attempt} order; empty when none
   */
  public List<UsageRecordDto> findByTenantAndRun(UUID tenantId, UUID runId) {
    return jdbc.query(
        "SELECT id, tenant_id, run_id, step_run_id, attempt, usage_type, measurement, provider, model,"
            + " tool_name, tool_version, input_tokens, output_tokens, estimated_cost, provider_cost,"
            + " unit, currency, provider_request_id, provider_metadata"
            + " FROM "
            + TABLE
            + " WHERE tenant_id = ? AND run_id = ? ORDER BY created_at, attempt",
        mapper,
        tenantId,
        runId);
  }
}
