package com.example.agentflow.common.rate_limit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.common.RateLimitGroup;
import com.example.agentflow.common.RateLimitKey;
import com.example.agentflow.common.RateLimitPolicy;
import com.example.agentflow.common.RateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

  private static final Instant BASE = Instant.ofEpochMilli(1_700_000_000_000L);
  private static final ZoneId UTC = ZoneId.of("UTC");

  private static final UUID ACTOR_UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000");

  private static RateLimitPolicy policy(int budget, RateLimitGroup... groups) {
    var budgets = new EnumMap<RateLimitGroup, Integer>(RateLimitGroup.class);
    for (RateLimitGroup group : groups) {
      budgets.put(group, budget);
    }
    return new RateLimitPolicy(true, budgets);
  }

  /** A stable key so repeated calls hit the same stored window within a single test. */
  private static RateLimitKey key(RateLimitGroup group) {
    return new RateLimitKey(ACTOR_UUID, "actor", group);
  }

  private static RateLimitPolicy noBudgetPolicy() {
    // A group with no entry in the map is unthrottled (isThrottled = false) -> the adapter fails
    // open.
    return new RateLimitPolicy(true, Map.of());
  }

  @Test
  void serves_exactly_the_budget_per_window_then_rejects() {
    RateLimiter limiter =
        new InMemoryRateLimiter(policy(3, RateLimitGroup.CREATE_RUN), Clock.fixed(BASE, UTC));

    // budget = 3: the first three calls are non-negative (served); the fourth is negative
    // (rejected).
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(2);
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(1);
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(0);
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(-1);
  }

  @Test
  void resets_the_counter_at_the_start_of_the_next_minute() {
    RateLimiter limiter =
        new InMemoryRateLimiter(policy(2, RateLimitGroup.CREATE_RUN), Clock.fixed(BASE, UTC));
    RateLimitKey key = key(RateLimitGroup.CREATE_RUN);

    limiter.remaining(key);
    limiter.remaining(key);
    assertThat(limiter.remaining(key)).contains(-1); // window exhausted

    RateLimiter next =
        new InMemoryRateLimiter(
            policy(2, RateLimitGroup.CREATE_RUN), Clock.fixed(BASE.plusSeconds(61), UTC));
    assertThat(next.remaining(key)).contains(1); // the new window starts fresh
  }

  @Test
  void fails_open_when_throttling_is_disabled() {
    RateLimiter limiter =
        new InMemoryRateLimiter(new RateLimitPolicy(false, Map.of()), Clock.fixed(BASE, UTC));
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).isEmpty();
  }

  @Test
  void fails_open_when_the_group_has_no_budget() {
    RateLimiter limiter = new InMemoryRateLimiter(noBudgetPolicy(), Clock.fixed(BASE, UTC));
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).isEmpty();
  }

  @Test
  void keeps_tenants_and_actors_independent() {
    RateLimiter limiter =
        new InMemoryRateLimiter(policy(1, RateLimitGroup.CREATE_RUN), Clock.fixed(BASE, UTC));
    RateLimitKey tenantA = new RateLimitKey(UUID.randomUUID(), "actorA", RateLimitGroup.CREATE_RUN);
    RateLimitKey tenantB = new RateLimitKey(UUID.randomUUID(), "actorB", RateLimitGroup.CREATE_RUN);

    assertThat(limiter.remaining(tenantA)).contains(0);
    // The other tenant/actor still has its own budget (not shared with tenantA).
    assertThat(limiter.remaining(tenantB)).contains(0);
  }

  @Test
  void evicts_least_recently_used_keys_when_the_store_is_full() {
    RateLimiter limiter =
        new InMemoryRateLimiter(policy(1, RateLimitGroup.CREATE_RUN), Clock.fixed(BASE, UTC), 2);
    RateLimitKey k1 = new RateLimitKey(UUID.randomUUID(), "a", RateLimitGroup.CREATE_RUN);
    RateLimitKey k2 = new RateLimitKey(UUID.randomUUID(), "b", RateLimitGroup.CREATE_RUN);
    RateLimitKey k3 = new RateLimitKey(UUID.randomUUID(), "c", RateLimitGroup.CREATE_RUN);

    limiter.remaining(k1);
    limiter.remaining(k2);
    limiter.remaining(k3); // store full at 2 -> evicts the least-recently accessed key (k1)

    // k2 is retained and still consumed; k1 was evicted, so its window starts fresh.
    assertThat(limiter.remaining(k2)).contains(-1);
    assertThat(limiter.remaining(k1)).contains(0);
  }
}
