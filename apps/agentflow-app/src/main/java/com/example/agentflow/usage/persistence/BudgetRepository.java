package com.example.agentflow.usage.persistence;

import com.example.agentflow.usage.domain.BudgetDto;
import com.example.agentflow.usage.domain.BudgetMetric;
import com.example.agentflow.usage.domain.BudgetReservation;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read / write path for the durable per-tenant budget the {@code usage} module owns (S12-04 /
 * {@code V3_3_26}): {@code tenant_budgets} (the limits) and {@code budget_reservations} (the
 * reservations an operation makes against them).
 *
 * <p>The bean is only registered when a {@link DataSource} exists ({@code @ConditionalOnBean(
 * DataSource.class)}), so the application still boots without a database (S01-02); the {@link
 * com.example.agentflow.usage.application.BudgetService} fails loudly when the repository is
 * absent. {@code tenant_id} / {@code run_id} are plain, tenant-scoped values, not foreign keys -
 * {@code tenants} / {@code workflow_runs} are owned by other modules, and a cross-module FK would
 * couple the schemas. Tenant isolation (invariant 1) lives on the row itself and in every
 * predicate, so another tenant's budget is indistinguishable from a missing one.
 *
 * <p>The reservation is the durable source of the consumed amount: {@link #consumed(tenantId,
 * metric)} sums the unsettled reservations' best-known amount ({@code actual_cost} once settled,
 * otherwise {@code reserved_amount}), so a reservation counts toward the limit the moment it is
 * made. Settlement is idempotent - the {@code settled_at} guard makes a second settle a no-op - and
 * the {@code reservation_id} primary key makes a duplicate reservation a no-op too (invariant 3).
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class BudgetRepository {

  private static final String BUDGET_TABLE = "tenant_budgets";
  private static final String RESERVATIONS_TABLE = "budget_reservations";

  private final JdbcTemplate jdbc;
  private final RowMapper<BudgetDto> budgetMapper = new BudgetRowMapper();

  public BudgetRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Read the tenant's budget limits.
   *
   * @param tenantId the owning tenant
   * @return the budget, or {@link Optional#empty()} when the tenant has no budget configured (a
   *     tenant with no budget is unlimited, never blocked)
   */
  public Optional<BudgetDto> readBudget(UUID tenantId) {
    return jdbc
        .query(
            "SELECT tenant_id, period, max_runs, max_tokens, max_cost FROM "
                + BUDGET_TABLE
                + " WHERE tenant_id = ?",
            budgetMapper,
            tenantId)
        .stream()
        .findFirst();
  }

  /**
   * Lock the tenant's budget row for the duration of the current transaction (the default {@code
   * READ COMMITTED}), so the check-then-reserve in {@link
   * com.example.agentflow.usage.application.BudgetService#reserve} is serialized per tenant and two
   * concurrent reservations cannot both pass the headroom check.
   *
   * @param tenantId the owning tenant
   */
  public void lockBudgetRow(UUID tenantId) {
    jdbc.query(
        "SELECT 1 FROM " + BUDGET_TABLE + " WHERE tenant_id = ? FOR UPDATE", row -> {}, tenantId);
  }

  /**
   * @param tenantId the owning tenant
   * @param metric the budget dimension
   * @return the amount already consumed for {@code metric} by the tenant's reservations: an
   *     unsettled reservation counts at its {@code reserved_amount}, a settled one at its {@code
   *     actual_cost} (the accrual sum, never filtered by {@code settled_at} - a settled row still
   *     counts, now at its actual cost, so the budget stays meaningful once everything settled)
   *     (never {@code null}; zero when nothing is consumed)
   */
  public BigDecimal consumed(UUID tenantId, BudgetMetric metric) {
    Object sum =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(COALESCE(actual_cost, reserved_amount)), 0) FROM "
                + RESERVATIONS_TABLE
                + " WHERE tenant_id = ? AND metric = ?",
            Object.class,
            tenantId,
            metric.name());
    return sum == null ? BigDecimal.ZERO : new BigDecimal(sum.toString());
  }

  /**
   * Look up an existing reservation by its idempotency key.
   *
   * @param reservationId the reservation id
   * @return the reservation, or {@link Optional#empty()} when none exists
   */
  public Optional<BudgetReservation> findReservation(UUID reservationId) {
    return jdbc
        .query(
            "SELECT reservation_id, tenant_id, run_id, metric, reserved_amount FROM "
                + RESERVATIONS_TABLE
                + " WHERE reservation_id = ?",
            BudgetRepository::toReservation,
            reservationId)
        .stream()
        .findFirst();
  }

  /**
   * Insert a reservation. The {@code ON CONFLICT (reservation_id) DO NOTHING} makes a repeated id a
   * no-op, so a duplicate delivery or a crash-retry of the same reservation never double-charges
   * (invariant 3).
   *
   * @return the number of rows inserted ({@code 0} when the reservation already existed)
   */
  public int insertReservation(
      UUID reservationId,
      UUID tenantId,
      UUID runId,
      BudgetMetric metric,
      BigDecimal reservedAmount) {
    return jdbc.update(
        "INSERT INTO "
            + RESERVATIONS_TABLE
            + " (reservation_id, tenant_id, run_id, metric, reserved_amount) "
            + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (reservation_id) DO NOTHING",
        ps -> {
          ps.setObject(1, reservationId);
          ps.setObject(2, tenantId);
          ps.setObject(3, runId);
          ps.setString(4, metric.name());
          ps.setObject(5, reservedAmount);
        });
  }

  /**
   * Reconcile a reservation to its actual cost, once. The {@code settled_at IS NULL} guard makes a
   * second settle of the same reservation a no-op (idempotent settlement - invariant 3).
   *
   * @return the number of rows updated ({@code 0} when the reservation was already settled)
   */
  public int settleReservation(UUID reservationId, BigDecimal actualCost) {
    return jdbc.update(
        "UPDATE "
            + RESERVATIONS_TABLE
            + " SET actual_cost = ?, settled_at = now() WHERE reservation_id = ? AND settled_at IS NULL",
        ps -> {
          ps.setObject(1, actualCost);
          ps.setObject(2, reservationId);
        });
  }

  private static BudgetReservation toReservation(ResultSet rs, int rowNum) throws SQLException {
    return new BudgetReservation(
        rs.getObject("reservation_id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        BudgetMetric.valueOf(rs.getString("metric")),
        new BigDecimal(rs.getString("reserved_amount")));
  }
}
