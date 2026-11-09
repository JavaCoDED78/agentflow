package com.example.agentflow.ai.application;

/**
 * The bounded-repair descriptor for a malformed-plan attempt (S09-08).
 *
 * <p>A value object that captures <em>how many</em> corrective retries a model attempt that did not
 * produce a usable plan may take. It is the descriptor half of the repair model: {@link
 * PlannerFailure} decides <em>whether</em> a failure is repairable (a retry can fix it) and this
 * policy bounds <em>how many</em> such retries are allowed. The retry is additionally gated on
 * budget availability (see {@link PlannerService#propose}); the two together guarantee the S09-08
 * acceptance — a malformed plan is repaired with at most one corrective retry, never an infinite
 * loop of model calls.
 *
 * <p>It is a pure, dependency-free value object with value equality, so it can be compared and
 * passed around like a constant. A parameter change produces a new value.
 *
 * <p><b>Bounded.</b> A retry is allowed only while fewer than {@link #maxRetries()} retries have
 * run; the last allowed retry's failure is terminal, so a model attempt cannot retry forever
 * (invariant: an LLM call is not exactly-once and must not drive an unbounded loop).
 *
 * @param maxRetries the maximum number of corrective retries allowed after the initial attempt, at
 *     least {@code 0} (a value of {@code 0} means: never retry)
 */
public record PlannerRepairPolicy(int maxRetries) {

  /**
   * Canonical-constructor validation. A policy with {@code maxRetries < 0} is a configuration
   * error, so the constructor rejects it rather than producing a nonsensical policy.
   */
  public PlannerRepairPolicy {
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be >= 0 but was " + maxRetries);
    }
  }

  /**
   * @param maxRetries the maximum number of corrective retries allowed (at least {@code 0})
   * @return a validated policy, or {@link IllegalArgumentException} if {@code maxRetries} is
   *     negative
   */
  public static PlannerRepairPolicy of(int maxRetries) {
    return new PlannerRepairPolicy(maxRetries);
  }

  /**
   * @return the production default: at most one corrective retry, so a malformed plan is given a
   *     single second chance but never drives an infinite model-call loop (S09-08)
   */
  public static PlannerRepairPolicy ofDefaults() {
    // At most one corrective retry: a malformed plan gets a single second chance, never an infinite
    // loop of model calls (S09-08).
    return new PlannerRepairPolicy(1);
  }
}
