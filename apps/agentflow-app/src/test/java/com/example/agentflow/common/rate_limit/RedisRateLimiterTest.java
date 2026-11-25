package com.example.agentflow.common.rate_limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.agentflow.common.RateLimitGroup;
import com.example.agentflow.common.RateLimitKey;
import com.example.agentflow.common.RateLimitPolicy;
import com.example.agentflow.common.RateLimiter;
import com.example.agentflow.common.redis.RedisKeyBuilder;
import com.example.agentflow.common.redis.RedisTtl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Unit tests for {@link RedisRateLimiter}. The {@link StringRedisTemplate} is a Mockito mock, so
 * these run offline (no Redis): they prove the budget math, the tenant-scoped deterministic key,
 * the fail-open behaviour and the meter — the real Redis integration lives in {@code
 * RedisRateLimiterIT}.
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {

  private static final Instant BASE = Instant.ofEpochMilli(1_700_000_000_000L);
  private static final ZoneId UTC = ZoneId.of("UTC");
  private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-000000000001");
  private static final String PRINCIPAL = "actor";

  @Mock StringRedisTemplate template;
  @Captor ArgumentCaptor<List<String>> keysCaptor;

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final RedisKeyBuilder keyBuilder = new RedisKeyBuilder("local");
  private final RateLimiter failover =
      new InMemoryRateLimiter(policy(3, RateLimitGroup.CREATE_RUN), Clock.fixed(BASE, UTC));
  private RedisRateLimiter limiter;

  @BeforeEach
  void setUp() {
    limiter =
        new RedisRateLimiter(
            policy(3, RateLimitGroup.CREATE_RUN),
            template,
            failover,
            Clock.fixed(BASE, UTC),
            registry,
            keyBuilder);
  }

  private static RateLimitPolicy policy(int budget, RateLimitGroup... groups) {
    EnumMap<RateLimitGroup, Integer> budgets = new EnumMap<>(RateLimitGroup.class);
    for (RateLimitGroup group : groups) {
      budgets.put(group, budget);
    }
    return new RateLimitPolicy(true, budgets);
  }

  private static RateLimitKey key(RateLimitGroup group) {
    return new RateLimitKey(TENANT, PRINCIPAL, group);
  }

  @Test
  @DisplayName("serves exactly the budget per window, then rejects")
  void serves_exactly_the_budget_per_window_then_rejects() {
    // INCR returns 1, 2, 3, 4 for budget = 3 -> remaining 2, 1, 0, -1.
    doReturn(1L)
        .doReturn(2L)
        .doReturn(3L)
        .doReturn(4L)
        .when(template)
        .execute(any(RedisScript.class), anyList(), anyString());

    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(2);
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(1);
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(0);
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(-1);
  }

  @Test
  @DisplayName("the per-minute budget is derived from the policy")
  void the_per_minute_budget_is_derived_from_the_policy() {
    limiter =
        new RedisRateLimiter(
            policy(5, RateLimitGroup.CREATE_RUN),
            template,
            failover,
            Clock.fixed(BASE, UTC),
            registry,
            keyBuilder);
    doReturn(1L).when(template).execute(any(RedisScript.class), anyList(), anyString());
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(4); // budget 5 - used 1
  }

  @Test
  @DisplayName("the key is tenant-scoped and deterministic (same input -> same key)")
  void the_key_is_tenant_scoped_and_deterministic() {
    doReturn(1L).when(template).execute(any(RedisScript.class), anyList(), anyString());

    limiter.remaining(key(RateLimitGroup.CREATE_RUN));
    limiter.remaining(key(RateLimitGroup.CREATE_RUN));
    verify(template, times(2)).execute(any(RedisScript.class), keysCaptor.capture(), anyString());

    List<String> first = keysCaptor.getAllValues().get(0);
    List<String> second = keysCaptor.getAllValues().get(1);
    assertThat(first).hasSize(1);
    assertThat(first).isEqualTo(second);
    assertThat(first.get(0)).doesNotContain(PRINCIPAL); // the principal is hashed, never raw
  }

  @Test
  @DisplayName("different tenants never share a budget")
  void different_tenants_never_share_a_budget() {
    doReturn(1L).when(template).execute(any(RedisScript.class), anyList(), anyString());
    limiter.remaining(key(RateLimitGroup.CREATE_RUN));
    RateLimitKey other = new RateLimitKey(UUID.randomUUID(), PRINCIPAL, RateLimitGroup.CREATE_RUN);
    limiter.remaining(other);

    verify(template, times(2)).execute(any(RedisScript.class), keysCaptor.capture(), anyString());
    List<String> first = keysCaptor.getAllValues().get(0);
    List<String> second = keysCaptor.getAllValues().get(1);

    assertThat(first.get(0)).isNotEqualTo(second.get(0));
  }

  @Test
  @DisplayName("a throttled group consumes the counter; the meter records each fail-over")
  void a_throttled_group_consumes_the_counter_and_the_meter_records_each_failover() {
    // Redis never answers the way the mock would; force the failure path.
    org.mockito.Mockito.when(template.execute(any(RedisScript.class), anyList(), anyString()))
        .thenThrow(new IllegalStateException("redis down"));

    // Fail open: the in-memory failover (budget 3) returns 2 for a fresh window.
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(2);
    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(1);

    assertThat(registry.get("agentflow.redis.rate_limit.fallback_open").counter().count())
        .isEqualTo(2.0);
  }

  @Test
  @DisplayName("an unthrottled group is open and never touches Redis")
  void an_unthrottled_group_is_open_and_never_touches_redis() {
    limiter =
        new RedisRateLimiter(
            new RateLimitPolicy(false, Map.of()),
            template,
            failover,
            Clock.fixed(BASE, UTC),
            registry,
            keyBuilder);

    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).isEmpty();
    verify(template, never()).execute(any(RedisScript.class), anyList(), anyString());
  }

  @Test
  @DisplayName("the counter key is written with a bounded, non-zero TTL")
  void the_counter_key_is_written_with_a_bounded_non_zero_ttl() {
    doReturn(1L).when(template).execute(any(RedisScript.class), anyList(), anyString());
    limiter.remaining(key(RateLimitGroup.CREATE_RUN));

    // The TTL is the varargs (third) argument of the script call; capturing each element gives the
    // single TTL value the script received.
    ArgumentCaptor<String> ttlCaptor = ArgumentCaptor.forClass(String.class);
    verify(template).execute(any(RedisScript.class), anyList(), ttlCaptor.capture());
    assertThat(ttlCaptor.getAllValues()).containsExactly(Long.toString(120_000L));
    assertThat(120_000L).isLessThanOrEqualTo(RedisTtl.MAX_SECONDS * 1000);
  }

  @Test
  @DisplayName("a successful command records a succeeded latency sample and an allowed decision")
  void a_successful_command_records_a_succeeded_latency_sample_and_an_allowed_decision() {
    doReturn(1L).when(template).execute(any(RedisScript.class), anyList(), anyString());

    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(2);

    assertThat(
            registry
                .get("agentflow.redis.command.latency.seconds")
                .tags("operation", "rate_limit", "result", "succeeded")
                .timer()
                .count())
        .isEqualTo(1L);
    assertThat(
            registry
                .get("agentflow.redis.rate_limit.decision.total")
                .tags("group", "create_run", "result", "allowed")
                .counter()
                .count())
        .isEqualTo(1.0);
    // The error counter is never registered on the success path (no meter at all).
    assertThat(registry.find("agentflow.redis.command.errors.total").meter()).isNull();
  }

  @Test
  @DisplayName("an exceeded budget records a limited decision")
  void an_exceeded_budget_records_a_limited_decision() {
    // INCR returns 4 -> budget 3 -> remaining -1 -> the interceptor rejects (S04-16).
    doReturn(4L).when(template).execute(any(RedisScript.class), anyList(), anyString());

    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(-1);

    assertThat(
            registry
                .get("agentflow.redis.rate_limit.decision.total")
                .tags("group", "create_run", "result", "limited")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("a failed command records a failed latency sample, an error and a fallback decision")
  void a_failed_command_records_a_failed_latency_sample_an_error_and_a_fallback_decision() {
    org.mockito.Mockito.when(template.execute(any(RedisScript.class), anyList(), anyString()))
        .thenThrow(new IllegalStateException("redis down"));

    assertThat(limiter.remaining(key(RateLimitGroup.CREATE_RUN))).contains(2);

    assertThat(
            registry
                .get("agentflow.redis.command.latency.seconds")
                .tags("operation", "rate_limit", "result", "failed")
                .timer()
                .count())
        .isEqualTo(1L);
    assertThat(
            registry
                .get("agentflow.redis.command.errors.total")
                .tags("operation", "rate_limit")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .get("agentflow.redis.rate_limit.decision.total")
                .tags("group", "create_run", "result", "fallback")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(registry.get("agentflow.redis.rate_limit.fallback_open").counter().count())
        .isEqualTo(1.0);
  }
}
