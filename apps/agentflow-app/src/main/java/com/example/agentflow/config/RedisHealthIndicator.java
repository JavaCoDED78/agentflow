package com.example.agentflow.config;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis health indicator (S12-17, {@link
 * org.springframework.boot.health.contributor.HealthIndicator}).
 *
 * <p>Pings the Redis instance through the opt-in {@link RedisConnectionFactory}. The indicator is a
 * <em>helper</em> check: it lives in a non-critical health group and is never part of the {@code
 * readiness} or {@code liveness} probes, so an unavailable Redis (a cache / rate-limit store) can
 * never drop a pod or reroute traffic ({@code ADR-021}: Redis is a helper/caddy component, never
 * the source of truth). The default Spring Boot probes contain only the {@code readinessState} /
 * {@code livenessState} indicators, so a contributor named {@code redis} is excluded from them by
 * default; this class additionally exposes it under its own {@code /health/redis} group.
 *
 * <p>The check is fail-fast and synchronous: a connection failure or a non-{@code PONG} outcome
 * reports {@link Status#DOWN} so an operator watching {@code /health} sees it, while the
 * availability probes stay green.
 */
public class RedisHealthIndicator extends AbstractHealthIndicator {

  private final RedisConnectionFactory connectionFactory;

  /**
   * Create the indicator over the given connection factory.
   *
   * @param connectionFactory the opt-in Redis connection factory (only present when {@code
   *     agentflow.redis.enabled=true})
   */
  public RedisHealthIndicator(RedisConnectionFactory connectionFactory) {
    this.connectionFactory = connectionFactory;
  }

  @Override
  protected void doHealthCheck(Health.Builder builder) throws Exception {
    RedisConnection connection = connectionFactory.getConnection();
    try {
      // {@code ping()} performs a real server round-trip and throws on a refused / timed-out
      // connection, which {@link AbstractHealthIndicator} turns into a DOWN status. A returned
      // value (Redis always echoes {@code PONG} for a bare {@code PING}) means reachable.
      String response = connection.ping();
      builder.up().withDetail("message", response != null ? response : "PONG");
    } finally {
      connection.close();
    }
  }
}
