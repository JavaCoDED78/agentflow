package com.example.agentflow.usage.persistence;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read / write path for the durable per-tenant parallel-run quota slots the {@code usage} module
 * owns (S12-05 / {@code V3_3_27}): {@code tenant_run_slots}, one row per non-terminal run a tenant
 * holds.
 *
 * <p>The bean is only registered when a {@link DataSource} exists ({@code @ConditionalOnBean(
 * DataSource.class)}), so the application still boots without a database (S01-02); the {@link
 * com.example.agentflow.usage.application.QuotaService} fails loudly when the repository is absent.
 * {@code tenant_id} / {@code run_id} are plain, tenant-scoped values, not foreign keys - {@code
 * tenants} / {@code workflow_runs} are owned by other modules, and a cross-module FK would couple
 * the schemas. Tenant isolation (invariant 1) lives on the composite primary key and in every
 * predicate, so another tenant's slots are indistinguishable from missing ones.
 *
 * <p>The count is the quota: {@link #countActive(UUID)} is the number of non-terminal runs the
 * tenant currently holds, read under the tenant's {@code tenant_budgets} row lock by {@link
 * com.example.agentflow.usage.application.QuotaService#acquireRunSlot} so concurrent acquisitions
 * are serialized per tenant. {@link #insertSlot} is an {@code ON CONFLICT DO NOTHING} write, so a
 * duplicate acquisition for the same {@code (tenant_id, run_id)} never creates a second row
 * (invariant 3); {@link #deleteSlot} is the release, idempotent by definition (a {@code DELETE} of
 * an absent row changes nothing). All state is durable in PostgreSQL; Redis is never the store for
 * quota state (invariant 2).
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class RunSlotRepository {

  private static final String TABLE = "tenant_run_slots";

  private final JdbcTemplate jdbc;

  public RunSlotRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Whether the run already holds a slot for this tenant - the idempotent re-acquisition check.
   *
   * @param tenantId the owning tenant
   * @param runId the run
   * @return {@code true} when the slot row exists
   */
  public boolean holdsSlot(UUID tenantId, UUID runId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + TABLE + " WHERE tenant_id = ? AND run_id = ?",
            Integer.class,
            tenantId,
            runId);
    return count != null && count > 0;
  }

  /**
   * The number of non-terminal runs the tenant currently holds a slot for - the quota consumption
   * read under the tenant's {@code tenant_budgets} row lock during acquisition, so it cannot race a
   * concurrent acquisition.
   *
   * @param tenantId the owning tenant
   * @return the active slot count (never negative; zero when the tenant has no active run)
   */
  public long countActive(UUID tenantId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + TABLE + " WHERE tenant_id = ?", Long.class, tenantId);
    return count == null ? 0 : count;
  }

  /**
   * Insert the run's slot. The {@code ON CONFLICT (tenant_id, run_id) DO NOTHING} composite-key
   * guard makes a repeated acquisition a no-op, so a duplicate delivery or a crash-retry never
   * counts the run twice (invariant 3).
   *
   * @return the number of rows inserted ({@code 0} when the slot already existed)
   */
  public int insertSlot(UUID tenantId, UUID runId) {
    return jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (tenant_id, run_id) VALUES (?, ?) "
            + "ON CONFLICT (tenant_id, run_id) DO NOTHING",
        tenantId,
        runId);
  }

  /**
   * Release the run's slot at its terminal transition. The {@code DELETE} is idempotent by
   * definition: releasing a run that holds no slot (already terminal, never acquired) changes
   * nothing, so an at-least-once repeat never double-releases (invariant 3).
   *
   * @return the number of rows deleted ({@code 0} when the run held no slot)
   */
  public int deleteSlot(UUID tenantId, UUID runId) {
    return jdbc.update(
        "DELETE FROM " + TABLE + " WHERE tenant_id = ? AND run_id = ?", tenantId, runId);
  }
}
