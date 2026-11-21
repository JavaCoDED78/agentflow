package com.example.agentflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-Redis smoke test (S12-17) over a {@code redis:8.10.2} Testcontainers instance - the same
 * image Compose runs ({@code infra/compose/docker-compose.yml}). It exercises the exact client the
 * app uses ({@link LettuceConnectionFactory}, built the same way {@link
 * RedisConnectionConfiguration} wires it for the local profile: plain TCP, no auth, no TLS) so the
 * fixture proves the real Redis round trips, not a mock or an in-memory stand-in (AGENTS.md). Three
 * things are asserted: a {@code SET}/{@code GET} cache value, a {@code TTL} that counts down within
 * its window, and the bare {@code PING} that {@link RedisHealthIndicator} relies on to report
 * reachability.
 *
 * <p>The typed {@link RedisTemplate} (String keys/values) is used throughout - the non-deprecated
 * replacement for the byte[] {@code RedisConnection} API - so the smoke path mirrors application
 * usage rather than a deprecated low-level call.
 *
 * <p>This test lives in the {@code integrationTest} source set, so it runs with {@code ./gradlew
 * integrationTest} (the Docker-backed suite) and not the fast {@code check} gate, matching every
 * other Testcontainers test in the project.
 */
@Testcontainers
@DisplayName("Redis connection (smoke, real Redis via Testcontainers)")
class RedisSmokeTest {

  @Container
  static GenericContainer<?> redis = new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);

  private LettuceConnectionFactory factory;
  private RedisTemplate<String, String> template;

  @BeforeEach
  void connect() {
    // Plain host/port wiring - matches the local profile (no auth, no TLS). The kubernetes profile
    // additionally reads an optional password from the environment; the local smoke path has none.
    this.factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    // A freshly constructed LettuceConnectionFactory refuses to serve connections until it has been
    // started (its client initializes on the first start()); Spring triggers this through the bean
    // lifecycle, so start it here and stop it after each test.
    this.factory.start();

    this.template = new RedisTemplate<>();
    RedisSerializer<String> stringSerializer = new StringRedisSerializer();
    template.setConnectionFactory(factory);
    template.setKeySerializer(stringSerializer);
    template.setValueSerializer(stringSerializer);
    template.setDefaultSerializer(stringSerializer);
    template.afterPropertiesSet();
  }

  @AfterEach
  void disconnect() {
    this.factory.stop();
  }

  @Test
  @DisplayName("a cache value round-trips through SET then GET")
  void cache_value_round_trips_through_set_and_get() {
    template.opsForValue().set("greeting", "agentflow");
    assertThat(template.opsForValue().get("greeting")).isEqualTo("agentflow");
  }

  @Test
  @DisplayName("an entry carries a TTL that counts down but does not expire inside its window")
  void entry_carries_a_ttl() {
    template.opsForValue().set("session", "abc");
    template.expire("session", Expiration.seconds(30));
    Long ttl = template.getExpire("session", TimeUnit.SECONDS);
    assertThat(ttl).isNotNull();
    assertThat(ttl).isGreaterThan(0L);
    assertThat(ttl).isLessThanOrEqualTo(30L);
  }

  @Test
  @DisplayName("the bare PING the health indicator depends on returns PONG")
  void health_indicator_ping_returns_pong() {
    // The health indicator pings the connection directly; assert the live server echoes PONG.
    try (RedisConnection conn = factory.getConnection()) {
      assertThat(conn.ping()).isEqualTo("PONG");
    }
  }
}
