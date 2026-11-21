package com.example.agentflow.common.rate_limit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.common.RateLimitGroup;
import com.example.agentflow.common.RateLimitKey;
import com.example.agentflow.common.RateLimitPolicy;
import com.example.agentflow.common.RateLimiter;
import com.example.agentflow.common.redis.RedisKeyBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link RedisRateLimiter} against a real Redis (Testcontainers, the {@code
 * redis:8.10.2} image used by Compose). The {@code @Testcontainers}/{@code @Container} extension
 * skips these when Docker is unavailable (see {@code EnabledIfDockerAvailable}), so {@code
 * ./gradlew check} stays green offline. The real Redis proves what the Mockito unit test cannot:
 *
 * <ul>
 *   <li>two limiter instances on one Redis observe a <b>single</b> global limit (the counter lives
 *       in Redis, incremented atomically);
 *   <li>a Redis that becomes unreachable fails open to the in-memory limiter instead of throwing.
 * </ul>
 */
@Testcontainers
class RedisRateLimiterIT {

  @Container
  static GenericContainer<?> redis = new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);

  private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-000000000001");

  private final RedisKeyBuilder keyBuilder = new RedisKeyBuilder("test");
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final List<RedisConnectionFactory> factories = new ArrayList<>();

  private RedisConnectionFactory factory() {
    LettuceConnectionFactory connection =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("localhost", redis.getMappedPort(6379)));
    connection.afterPropertiesSet();
    factories.add(connection);
    return connection;
  }

  private RedisConnectionFactory deadFactory() throws IOException {
    int deadPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      deadPort = socket.getLocalPort();
    }
    LettuceConnectionFactory connection =
        new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", deadPort));
    connection.afterPropertiesSet();
    factories.add(connection);
    return connection;
  }

  private RedisRateLimiter limiter(
      int budget, RateLimitGroup group, RedisConnectionFactory factory) {
    StringRedisTemplate template = new StringRedisTemplate(factory);
    RateLimitPolicy policy = policy(budget, group);
    RateLimiter failover = new InMemoryRateLimiter(policy, Clock.systemUTC());
    return new RedisRateLimiter(
        policy, template, failover, Clock.systemUTC(), registry, keyBuilder);
  }

  private static RateLimitPolicy policy(int budget, RateLimitGroup... groups) {
    EnumMap<RateLimitGroup, Integer> budgets = new EnumMap<>(RateLimitGroup.class);
    for (RateLimitGroup group : groups) {
      budgets.put(group, budget);
    }
    return new RateLimitPolicy(true, budgets);
  }

  @AfterEach
  void closeFactories() {
    factories.forEach(connection -> ((LettuceConnectionFactory) connection).destroy());
  }

  @Test
  @DisplayName("two limiter instances on one Redis observe a single global limit")
  void globalLimitIsGlobalAcrossTwoInstances() {
    // Two independent limiter instances, both wired to the same Redis.
    RedisRateLimiter a = limiter(60, RateLimitGroup.CREATE_RUN, factory());
    RedisRateLimiter b = limiter(60, RateLimitGroup.CREATE_RUN, factory());

    int served = 0;
    for (int i = 0; i < 120; i++) {
      RedisRateLimiter instance = (i % 2 == 0) ? a : b;
      if (instance.remaining(key()).orElse(-1) >= 0) {
        served++;
      }
    }
    // The budget is 60: exactly the first 60 calls are served, the rest are rejected, shared across
    // both instances.
    assertThat(served).isEqualTo(60);
  }

  @Test
  @DisplayName("fails open to the in-memory limiter when Redis is unreachable")
  void failsOpenWhenRedisIsUnavailable() throws IOException {
    RedisRateLimiter limiter = limiter(60, RateLimitGroup.CREATE_RUN, deadFactory());

    // The in-memory failover (budget 60) returns 59, then 58 for two calls to the same key.
    assertThat(limiter.remaining(key())).contains(59);
    assertThat(limiter.remaining(key())).contains(58);
    assertThat(registry.get("agentflow.redis.rate_limit.fallback_open").counter().count())
        .isEqualTo(2.0);
  }

  private static RateLimitKey key() {
    return new RateLimitKey(TENANT, "actor", RateLimitGroup.CREATE_RUN);
  }
}
