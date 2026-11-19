package com.example.agentflow.config;

import com.example.agentflow.common.RateLimitGroup;
import com.example.agentflow.common.RateLimitPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link RateLimitPolicy} (S04-16) from the {@code AgentFlowProperties.RateLimit} config
 * group. It produces only the policy &mdash; it never wires a {@link
 * com.example.agentflow.common.RateLimiter} adapter (that lives in {@code common}, produced later:
 * in-memory {@code S04-17}, Redis {@code S12-19}). Keeping the wiring here means the port stays
 * free of any Redis import, so the app boots and throttles with the in-memory adapter until the
 * Redis adapter is added (invariant 2 &mdash; a Redis outage or absence must not drop a run or
 * break startup).
 *
 * <p>The {@code (tenant, principal, group)} key is built in {@link
 * com.example.agentflow.common.RateLimitKey}; the adapter derives the per-minute budget for a group
 * from the returned {@link RateLimitPolicy}.
 */
@Configuration
class RateLimitConfiguration {

  private static final Logger log = LoggerFactory.getLogger(RateLimitConfiguration.class);

  @Bean
  RateLimitPolicy rateLimitPolicy(AgentFlowProperties properties) {
    AgentFlowProperties.RateLimit rateLimit = properties.getRateLimit();
    java.util.EnumMap<RateLimitGroup, Integer> budgets =
        new java.util.EnumMap<>(RateLimitGroup.class);
    budgets.put(RateLimitGroup.CREATE_RUN, rateLimit.getLimits().getCreateRun().getMaxPerMinute());
    budgets.put(RateLimitGroup.UPLOAD, rateLimit.getLimits().getUpload().getMaxPerMinute());
    budgets.put(
        RateLimitGroup.SEARCH_DEBUG, rateLimit.getLimits().getSearchDebug().getMaxPerMinute());
    budgets.put(RateLimitGroup.OPS, rateLimit.getLimits().getOps().getMaxPerMinute());
    log.info(
        "API rate limit {} (create-run={}, upload={}, search/debug={}, ops={})",
        rateLimit.isEnabled() ? "enabled" : "disabled",
        rateLimit.getLimits().maxPerMinute(RateLimitGroup.CREATE_RUN),
        rateLimit.getLimits().maxPerMinute(RateLimitGroup.UPLOAD),
        rateLimit.getLimits().maxPerMinute(RateLimitGroup.SEARCH_DEBUG),
        rateLimit.getLimits().maxPerMinute(RateLimitGroup.OPS));
    return new RateLimitPolicy(rateLimit.isEnabled(), budgets);
  }
}
