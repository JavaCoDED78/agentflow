package com.example.agentflow.usage.domain;

import java.util.Objects;

/**
 * The outcome of a budget check before an operation starts (S12-04 / {@code V3_3_26}): whether the
 * operation may proceed ({@link #allowed()}) and, when it may not, the human-readable reason. The
 * reason names the exhausted dimension and the remaining headroom without inventing a cost - the
 * identifiers here are monetary amounts, not high-cardinality metric labels (invariant 9).
 *
 * @param allowed whether the operation stays within the budget
 * @param reason the refusal reason, or {@code null} when {@link #allowed()} is {@code true}
 */
public record BudgetCheckResult(boolean allowed, String reason) {

  /**
   * @return an allowed check (no reason)
   */
  public static BudgetCheckResult allow() {
    return new BudgetCheckResult(true, null);
  }

  /**
   * @param reason the refusal reason
   * @return a denied check carrying the reason
   */
  public static BudgetCheckResult reject(String reason) {
    return new BudgetCheckResult(false, Objects.requireNonNull(reason, "reason must not be null"));
  }

  /**
   * @return {@code true} while more than zero of the budget is still available
   */
  public boolean allowed() {
    return allowed;
  }
}
