package com.example.agentflow.common;

import java.util.Optional;

/**
 * Storage-agnostic rate limiter port ({@code S04-16}). Implementations enforce the per-group budget
 * and are produced later in the sprint: {@code S04-17} (in-memory) and {@code S12-19} (Redis,
 * shared across API replicas). Neither is required for the app to boot.
 *
 * <p>The port returns the remaining budget for a key, or an empty {@link Optional} when the limiter
 * is absent or unavailable. An empty result is a <em>fail-open</em> decision: the caller proceeds
 * without throttling, because a missing or down limiter must never drop a legitimate request
 * (invariant 2 &mdash; correctness never depends on the cache) nor fail on a Redis outage ({@code
 * ADR-021}). The decision to reject a request (throw {@link RateLimitExceededException}) is made by
 * the caller that also knows the policy budget, so the port stays a pure read of limiter state and
 * stays free of any persistence import.
 */
public interface RateLimiter {

  /**
   * @param key the {@code (tenant, principal, group)} identity
   * @return the remaining budget for the current window, or {@link Optional#empty()} when the
   *     limiter is absent or unavailable (fail open &mdash; never throttle a request for that
   *     reason)
   */
  Optional<Integer> remaining(RateLimitKey key);
}
