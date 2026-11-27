package com.example.agentflow.workflowexecution.domain;

import java.util.Objects;

/**
 * The outcome vocabulary of the RunQuotaGuard port owned by this module (S12-05): the outcome of a
 * parallel-run quota acquisition before a run starts (S12-05 / {@code V3_3_27}).
 *
 * <p>The reason names the exhausted dimension and the remaining headroom without inventing a cost -
 * the identifiers here are small amounts, not high-cardinality metric labels (invariant 9).
 *
 * @param allowed whether the operation may proceed
 * @param reason the refusal reason, or {@code null} when {@link #allowed()} is {@code true}
 */
public record QuotaCheckResult(boolean allowed, String reason) {

  /**
   * @return an allowed acquisition (no reason)
   */
  public static QuotaCheckResult allow() {
    return new QuotaCheckResult(true, null);
  }

  /**
   * @param reason the refusal reason
   * @return a denied acquisition carrying the reason
   */
  public static QuotaCheckResult reject(String reason) {
    return new QuotaCheckResult(false, Objects.requireNonNull(reason, "reason must not be null"));
  }

  /**
   * @return {@code true} while the tenant holds fewer non-terminal runs than its limit
   */
  public boolean allowed() {
    return allowed;
  }
}
