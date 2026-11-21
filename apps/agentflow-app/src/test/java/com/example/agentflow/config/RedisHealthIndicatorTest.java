package com.example.agentflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Unit tests for {@link RedisHealthIndicator} (S12-17).
 *
 * <p>These exercise the indicator's decision logic directly against a mocked {@link
 * RedisConnectionFactory}, so they run without a Redis server and cover both the reachable path (a
 * bare {@code PING} returns {@code PONG} -> {@link Status#UP}) and the unreachable path (the {@code
 * PING} fails -> {@link Status#DOWN}). The connection is always closed in a {@code finally}, so
 * both paths verify the resource is released.
 */
class RedisHealthIndicatorTest {

  private final RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
  private final RedisConnection connection = mock(RedisConnection.class);

  @Test
  void a_reachable_redis_reports_UP_with_the_ping_response() {
    when(factory.getConnection()).thenReturn(connection);
    when(connection.ping()).thenReturn("PONG");

    RedisHealthIndicator indicator = new RedisHealthIndicator(factory);
    // Call health() once so the single check (and its single close) is exercised exactly.
    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails().get("message")).isEqualTo("PONG");
    verify(connection).close();
  }

  @Test
  void an_unreachable_redis_reports_DOWN_on_a_ping_failure() {
    when(factory.getConnection()).thenReturn(connection);
    when(connection.ping()).thenThrow(new IllegalStateException("connection refused"));

    RedisHealthIndicator indicator = new RedisHealthIndicator(factory);
    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    // The connection is still closed after the failed check (the finally block runs).
    verify(connection).close();
  }
}
