package com.example.agentflow.common.rate_limit;

import com.example.agentflow.common.RateLimitKey;
import com.example.agentflow.common.RateLimitPolicy;
import com.example.agentflow.common.RateLimiter;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory fixed-window implementation of the {@link RateLimiter} port.
 *
 * <p>A request consumes one slot from its group's per-minute budget. The adapter groups every key
 * into a one-minute {@linkplain #windowOf(long) fixed window} derived from the injected {@link
 * Clock}, so time-dependent tests can freeze the clock and assert deterministic behaviour.
 *
 * <p>The number of tracked keys is bounded. The backing map is an access-ordered {@link
 * LinkedHashMap} that removes the least-recently-accessed key once {@link #MAX_KEYS} is exceeded,
 * so memory stays bounded even when traffic fans out across many tenants or actors.
 *
 * <p>This adapter keeps the port contract: it performs no persistence import and never throws a
 * rejection. It consumes one slot on each call and returns the remaining budget for the current
 * window, counted after this call (so {@code budget} slots return {@code budget - 1} for the first
 * request). The interceptor rejects when this value is negative, which means a budget of {@code N}
 * serves exactly {@code N} requests per window and rejects the {@code (N + 1)}th. It returns an
 * empty {@link Optional} when nothing is configured for the key's group or when throttling is
 * disabled (fail open).
 */
public final class InMemoryRateLimiter implements RateLimiter {

  /**
   * Upper bound on the number of live key windows. Older, least-recently accessed keys are evicted.
   */
  public static final int MAX_KEYS = 10_000;

  private final RateLimitPolicy policy;
  private final Clock clock;
  private final int maxKeys;
  private final Map<RateLimitKey, Window> store =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RateLimitKey, Window> eldest) {
          return size() > maxKeys;
        }
      };

  /**
   * Builds the adapter for Spring wiring.
   *
   * @param policy the resolved policy; the value the adapter uses to derive the budget per group
   * @param clock the clock that fixes the window boundaries (system clock in production)
   */
  public InMemoryRateLimiter(RateLimitPolicy policy, Clock clock) {
    this(policy, clock, MAX_KEYS);
  }

  /**
   * Builds the adapter with an explicit key bound. Used by tests to exercise eviction with a small
   * limit.
   */
  public InMemoryRateLimiter(RateLimitPolicy policy, Clock clock, int maxKeys) {
    this.policy = policy;
    this.clock = clock;
    this.maxKeys = maxKeys;
  }

  @Override
  public Optional<Integer> remaining(RateLimitKey key) {
    if (policy == null || !policy.isThrottled(key.group())) {
      // Nothing configured for this group (budget absent, or throttling disabled): fail open.
      return Optional.empty();
    }
    int budget = policy.maxPerMinute(key.group());
    long window = windowOf(clock.millis());
    synchronized (store) {
      Window current = store.get(key);
      if (current == null) {
        current = new Window(window);
        store.put(key, current);
      }
      if (current.window != window) {
        // A new minute has started; reset the per-minute counter for this key.
        current.window = window;
        current.used = 0;
      }
      current.used += 1;
      // Remaining budget for the window, counted after this request. Non-negative means the
      // request was served; negative means the budget was exceeded, so the interceptor rejects.
      return Optional.of(budget - current.used);
    }
  }

  /** Maps epoch millis into a one-minute window index so all keys share window boundaries. */
  private static long windowOf(long epochMillis) {
    return epochMillis / 60_000L;
  }

  /** A single fixed-window counter. Guarded by the store monitor. */
  private static final class Window {
    private long window;
    private int used;

    Window(long window) {
      this.window = window;
    }
  }
}
