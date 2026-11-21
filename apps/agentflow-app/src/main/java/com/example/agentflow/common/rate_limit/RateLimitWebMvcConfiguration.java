package com.example.agentflow.common.rate_limit;

import com.example.agentflow.common.RateLimitPolicy;
import com.example.agentflow.common.RateLimiter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the rate-limit {@link RateLimiter} adapter and registers the {@link RateLimitInterceptor}
 * for the API.
 *
 * <p>The adapter is produced by exactly one of two mutually exclusive, exhaustive conditions on
 * {@code agentflow.redis.enabled}: the in-memory adapter ({@code S04-17}, always available, the
 * skeleton default) when {@code redis.enabled=false}, and the Redis adapter ({@code S12-19}) when
 * {@code redis.enabled=true} (see {@code RateLimitConfiguration}). Neither is a
 * {@code @ConditionalOnMissingBean}: the conditions are explicit and order-independent, so one
 * adapter is always produced and none is ever produced twice.
 *
 * <p>The in-memory adapter is a plain fixed-window store: it needs no database or Redis, so the app
 * boots with the port satisfied even in the skeleton state. The interceptor is registered for
 * {@code /api/v1/**}; health, liveness, and readiness under {@code /actuator/**} are excluded
 * explicitly, and every non-matched path (GET, documentation, unknown) is skipped by the resolver.
 */
@Configuration
public class RateLimitWebMvcConfiguration {

  /**
   * Builds the in-memory adapter bound to the resolved policy and the fixed-key store size.
   *
   * @param policy the resolved per-group policy; the value the adapter uses to derive budgets
   * @return the in-memory {@link RateLimiter} adapter
   */
  @Bean
  @ConditionalOnProperty(prefix = "agentflow.redis", name = "enabled", havingValue = "false")
  public RateLimiter inMemoryRateLimiter(RateLimitPolicy policy) {
    return new InMemoryRateLimiter(policy, Clock.systemUTC(), InMemoryRateLimiter.MAX_KEYS);
  }

  /**
   * Registers the interceptor for the API surface.
   *
   * @param interceptor the rate-limit interceptor
   * @return a {@link WebMvcConfigurer} that registers the interceptor for {@code /api/v1/**} and
   *     excludes health endpoints
   */
  @Bean
  public WebMvcConfigurer rateLimitInterceptorRegistration(RateLimitInterceptor interceptor) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry
            .addInterceptor(interceptor)
            .addPathPatterns("/api/v1/**")
            .excludePathPatterns(
                "/actuator",
                "/actuator/**",
                "/actuator/health",
                "/actuator/health/**",
                "/actuator/readiness",
                "/actuator/readiness/**",
                "/actuator/liveness",
                "/actuator/liveness/**");
      }
    };
  }
}
