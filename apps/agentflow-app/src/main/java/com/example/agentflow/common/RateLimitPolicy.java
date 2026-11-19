package com.example.agentflow.common;

import java.util.Map;

/**
 * The resolved per-group rate-limit policy ({@code S04-16}): a storage-agnostic value the adapter
 * uses to derive the budget for a {@link RateLimitGroup}. It is produced from the {@code
 * AgentFlowProperties.RateLimit} configuration group by {@code RateLimitConfiguration} and consumed
 * by the adapter (in-memory {@code S04-17}, Redis {@code S12-19}). A group with no entry is
 * unthrottled, and the whole policy can be disabled; the map is immutable so the resolved limits
 * cannot be mutated after binding.
 */
public record RateLimitPolicy(boolean enabled, Map<RateLimitGroup, Integer> maxPerMinute) {

  public RateLimitPolicy {
    maxPerMinute = Map.copyOf(maxPerMinute);
  }

  /**
   * @param group the group
   * @return the per-minute budget for the group, or {@code 0} when the group has no configured
   *     budget
   */
  public int maxPerMinute(RateLimitGroup group) {
    return maxPerMinute.getOrDefault(group, 0);
  }

  /**
   * @param group the group
   * @return {@code true} only when the policy is enabled and a budget is configured for the group
   */
  public boolean isThrottled(RateLimitGroup group) {
    return enabled && maxPerMinute.containsKey(group);
  }
}
