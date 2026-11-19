package com.example.agentflow.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@link RateLimitPolicy} is a pure, storage-agnostic policy value (S04-16): it resolves
 * the per-minute budget for a group (unknown groups are unthrottled), honors the {@code enabled}
 * flag so a disabled policy throttles nothing regardless of the configured limits, and returns an
 * immutable view so the resolved limits cannot be mutated after binding.
 */
class RateLimitPolicyTest {

  @Test
  void unknown_group_is_unthrottled_with_a_zero_budget() {
    RateLimitPolicy policy = new RateLimitPolicy(true, Map.of(RateLimitGroup.CREATE_RUN, 42));

    assertThat(policy.maxPerMinute(RateLimitGroup.CREATE_RUN)).isEqualTo(42);
    assertThat(policy.maxPerMinute(RateLimitGroup.UPLOAD)).isZero();
  }

  @Test
  void enabled_flag_decides_which_groups_are_throttled() {
    RateLimitPolicy policy = new RateLimitPolicy(true, Map.of(RateLimitGroup.CREATE_RUN, 1));

    assertThat(policy.isThrottled(RateLimitGroup.CREATE_RUN)).isTrue();
    assertThat(policy.isThrottled(RateLimitGroup.UPLOAD)).isFalse();
  }

  @Test
  void disabled_policy_throttles_every_group() {
    RateLimitPolicy policy = new RateLimitPolicy(false, Map.of(RateLimitGroup.CREATE_RUN, 1));

    assertThat(policy.isThrottled(RateLimitGroup.CREATE_RUN)).isFalse();
    assertThat(policy.isThrottled(RateLimitGroup.OPS)).isFalse();
  }

  @Test
  void resolved_limits_are_immutable() {
    RateLimitPolicy policy = new RateLimitPolicy(true, Map.of(RateLimitGroup.CREATE_RUN, 42));

    policy.maxPerMinute(RateLimitGroup.CREATE_RUN);
    assertThat(policy.maxPerMinute(RateLimitGroup.CREATE_RUN)).isEqualTo(42);
  }
}
