package com.example.agentflow.config;

import com.example.agentflow.common.RateLimitGroup;
import com.example.agentflow.common.RateLimitPolicy;
import com.example.agentflow.common.RateLimiter;
import com.example.agentflow.common.rate_limit.InMemoryRateLimiter;
import com.example.agentflow.common.rate_limit.RedisRateLimiter;
import com.example.agentflow.common.redis.RedisKeyBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the {@link RateLimitPolicy} (S04-16) from the {@code AgentFlowProperties.RateLimit} config
 * group and produces the {@link com.example.agentflow.common.RateLimiter} adapter for the enabled
 * environment.
 *
 * <p>Exactly one adapter is produced, by explicit and mutually exclusive conditions on {@code
 * agentflow.redis.enabled}: the in-memory adapter ({@code S04-17}) when Redis is disabled, and the
 * Redis adapter ({@code S12-19}, {@link RedisRateLimiter}) when Redis is enabled. The Redis adapter
 * fails open to the in-memory limiter on a Redis outage (ADR-021), so the app always throttles.
 *
 * <p>The {@code (tenant, principal, group)} key is built in {@link
 * com.example.agentflow.common.RateLimitKey}; the adapter derives the per-minute budget for a group
 * from the returned {@link RateLimitPolicy}.
 */
@Configuration
class RateLimitConfiguration {

  private static final Logger log = LoggerFactory.getLogger(RateLimitConfiguration.class);

  /**
   * The {@code env} tag of the Redis keys is the active Spring profile (the deployment marker this
   * app already uses: {@code local}, {@code kubernetes}, {@code test}). It is a trusted, bounded
   * config value (never caller-controlled), so the same Redis namespace is reused across restarts
   * of the same deployment; it defaults to {@code local} when no profile is active.
   */
  private static String envTag(Environment environment) {
    String[] profiles = environment.getActiveProfiles();
    if (profiles.length > 0 && !profiles[0].isBlank()) {
      return profiles[0];
    }
    return "local";
  }

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

  /**
   * The Redis {@link com.example.agentflow.common.RateLimiter} adapter ({@code S12-19}). Produced
   * only when {@code agentflow.redis.enabled=true}, when the connection factory and the Redis
   * auto-configuration beans exist. It fails open to a bounded in-memory limiter on a Redis outage
   * (the same window it uses), so a down Redis throttles the JVM, not every request (ADR-021).
   *
   * @param connectionFactory the Redis connection (present only when Redis is enabled)
   * @param policy the resolved per-group policy; the value the adapter uses to derive budgets
   * @param meterRegistry the registry for the fail-open meter (no high-cardinality labels)
   * @param environment the Spring environment; its active profile becomes the Redis key {@code env}
   * @return the Redis {@link com.example.agentflow.common.RateLimiter} adapter
   */
  @Bean
  @ConditionalOnProperty(prefix = "agentflow.redis", name = "enabled", havingValue = "true")
  com.example.agentflow.common.RateLimiter redisRateLimiter(
      RedisConnectionFactory connectionFactory,
      RateLimitPolicy policy,
      MeterRegistry meterRegistry,
      Environment environment) {
    StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
    RedisKeyBuilder keyBuilder = new RedisKeyBuilder(envTag(environment));
    RateLimiter failover =
        new InMemoryRateLimiter(policy, Clock.systemUTC(), InMemoryRateLimiter.MAX_KEYS);
    return new RedisRateLimiter(
        policy, redis, failover, Clock.systemUTC(), meterRegistry, keyBuilder);
  }
}
