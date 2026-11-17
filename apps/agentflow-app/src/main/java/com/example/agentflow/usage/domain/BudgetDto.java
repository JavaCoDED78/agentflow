package com.example.agentflow.usage.domain;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.util.Assert;

/**
 * The per-tenant hard budget the {@link com.example.agentflow.usage.application.BudgetService}
 * guards (S12-04 / {@code V3_3_26}). Mirrors the {@code tenant_budgets} row: the limits a tenant
 * may spend over a {@link BudgetPeriod}, and nothing else.
 *
 * <p>The canonical constructor is the contract-failure surface: a {@code null} tenant / period, a
 * negative limit or a {@code null} / negative {@code maxCost} are rejected with an explicit {@link
 * IllegalArgumentException} - matching the value-type contract pattern used across the {@code
 * usage} module, never {@code Objects.requireNonNull} (which in JDK 25 throws {@code
 * NullPointerException} and would misclassify a contract failure as a generic fault).
 *
 * @param tenantId the owning tenant (never {@code null})
 * @param period the window the budget is measured over (never {@code null})
 * @param maxRuns the maximum number of concurrent runs (a value of {@code 0} permits none)
 * @param maxTokens the maximum estimated token count over the period (a value of {@code 0} permits
 *     none)
 * @param maxCost the maximum estimated cost over the period (never {@code null}; a value of {@code
 *     0} permits no spend; money is never a float - invariant 10)
 */
public record BudgetDto(
    UUID tenantId, BudgetPeriod period, long maxRuns, long maxTokens, BigDecimal maxCost) {

  /**
   * @param tenantId the owning tenant (never {@code null})
   * @param period the window the budget is measured over (never {@code null})
   * @param maxRuns the maximum number of concurrent runs (a value of {@code 0} permits none)
   * @param maxTokens the maximum estimated token count over the period (a value of {@code 0}
   *     permits none)
   * @param maxCost the maximum estimated cost over the period (never {@code null}; never negative)
   * @throws NullPointerException if {@code tenantId} / {@code period} / {@code maxCost} is {@code
   *     null} (inherited from {@link Assert#notNull})
   * @throws IllegalArgumentException if a limit is negative or {@code maxCost} is negative
   */
  public BudgetDto {
    Assert.notNull(tenantId, "tenantId must not be null");
    Assert.notNull(period, "period must not be null");
    Assert.isTrue(maxRuns >= 0, "maxRuns must not be negative but was " + maxRuns);
    Assert.isTrue(maxTokens >= 0, "maxTokens must not be negative but was " + maxTokens);
    Assert.notNull(maxCost, "maxCost must not be null");
    Assert.isTrue(maxCost.signum() >= 0, "maxCost must not be negative but was " + maxCost);
  }

  /**
   * The limit for a single budget dimension (S12-04). Used by the guard to compare the consumed
   * amount against the right limit: {@code maxCost} for {@link BudgetMetric#COST} and {@code
   * maxTokens} for {@link BudgetMetric#TOKEN}.
   *
   * @param metric the budget dimension (never {@code null})
   * @return the limit for that dimension
   */
  public BigDecimal maxFor(BudgetMetric metric) {
    return switch (metric) {
      case COST -> maxCost;
      case TOKEN -> new BigDecimal(maxTokens);
    };
  }
}
