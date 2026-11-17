package com.example.agentflow.usage.application;

import com.example.agentflow.usage.domain.BudgetMetric;
import java.math.BigDecimal;

/**
 * Thrown by {@link BudgetService#reserve} when a reservation would exceed the tenant's remaining
 * budget, so the operation that triggered it is blocked <em>before</em> it starts (S12-04). It
 * carries the exhausted {@link BudgetMetric} and the remaining headroom against the limit, so the
 * caller can stop the operation and observability can show how far the budget was overdrawn without
 * inventing a cost.
 *
 * <p>The identifiers it names (the remaining / limit amounts) are monetary values, not
 * high-cardinality metric labels, so they belong in traces and errors only (invariant 9).
 */
public class BudgetExhaustedException extends RuntimeException {

  private final BudgetMetric metric;
  private final BigDecimal remaining;
  private final BigDecimal limit;

  /**
   * @param metric the budget dimension that was exhausted (never {@code null})
   * @param remaining the headroom still available when the reservation was refused (never negative)
   * @param limit the limit the reservation would have exceeded
   * @param message the human-readable refusal reason
   */
  public BudgetExhaustedException(
      BudgetMetric metric, BigDecimal remaining, BigDecimal limit, String message) {
    super(message);
    this.metric = metric;
    this.remaining = remaining;
    this.limit = limit;
  }

  /**
   * @return the budget dimension that was exhausted
   */
  public BudgetMetric metric() {
    return metric;
  }

  /**
   * @return the remaining headroom (limit minus the already consumed amount) when the refusal
   */
  public BigDecimal remaining() {
    return remaining;
  }

  /**
   * @return the limit the reservation would have exceeded
   */
  public BigDecimal limit() {
    return limit;
  }
}
