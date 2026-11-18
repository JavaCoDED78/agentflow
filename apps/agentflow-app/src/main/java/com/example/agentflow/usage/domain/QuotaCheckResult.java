package com.example.agentflow.usage.domain;

import java.util.Objects;

/**
 * The outcome of a parallel-run quota acquisition before a run starts (S12-05 / {@code V3_3_27}):
 * whether the tenant may hold one more non-terminal run ({@link #allowed()}) and, when it may not,
 * the human-readable reason. The reason names the limit and the active count without inventing a
 * number - the identifiers here are small integers, not high-cardinality metric labels (invariant
 * 9).
 *
 * @param allowed whether the run may acquire a quota slot
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
