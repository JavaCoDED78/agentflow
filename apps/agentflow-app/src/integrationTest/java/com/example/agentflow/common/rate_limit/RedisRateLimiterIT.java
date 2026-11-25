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
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
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
  private ServerSocket slowServerSocket;
  private Thread slowServerThread;
  private final List<Socket> slowAcceptedSockets = new ArrayList<>();

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

  /**
   * Wire a factory whose server accepts the TCP connection but never replies - a slow / hanging
   * Redis. The command timeout bounds how long a call can hang, so the request returns within the
   * configured bound instead of pinning a thread forever (S14-08).
   */
  private RedisConnectionFactory slowFactory(int commandTimeoutSeconds) throws IOException {
    slowServerSocket = new ServerSocket(0);
    int port = slowServerSocket.getLocalPort();
    slowServerThread =
        new Thread(
            () -> {
              try {
                while (!Thread.currentThread().isInterrupted()) {
                  slowAcceptedSockets.add(slowServerSocket.accept());
                }
              } catch (IOException ignored) {
                // The test owns the socket; a closed socket ends the accept loop.
              }
            });
    slowServerThread.setDaemon(true);
    slowServerThread.start();
    LettuceConnectionFactory connection =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("localhost", port),
            LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(commandTimeoutSeconds))
                .build());
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
    // Data isolation: drop every key the test wrote through the shared container so each test
    // starts
    // from a fresh namespace (the fixed TENANT + key is identical across the class). A fresh live
    // connection is used so this runs even when the test wired a dead one.
    try {
      LettuceConnectionFactory live =
          new LettuceConnectionFactory(
              new RedisStandaloneConfiguration("localhost", redis.getMappedPort(6379)));
      live.afterPropertiesSet();
      try {
        StringRedisTemplate flush = new StringRedisTemplate(live);
        flush.execute(
            (RedisCallback<Object>)
                connection -> {
                  connection.flushDb();
                  return null;
                });
      } finally {
        live.destroy();
      }
    } catch (RuntimeException ignored) {
      // Best-effort flush; the test-owned factories are still destroyed below.
    }
    factories.forEach(connection -> ((LettuceConnectionFactory) connection).destroy());
    slowAcceptedSockets.forEach(
        socket -> {
          try {
            socket.close();
          } catch (IOException ignored) {
          }
        });
    slowAcceptedSockets.clear();
    if (slowServerThread != null) {
      slowServerThread.interrupt();
      slowServerThread = null;
    }
    if (slowServerSocket != null) {
      try {
        slowServerSocket.close();
      } catch (IOException ignored) {
      }
      slowServerSocket = null;
    }
  }

  @Test
  @DisplayName("two limiter instances on one Redis observe a single global limit")
  void two_limiter_instances_observe_a_single_global_limit() {
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
  void fails_open_to_the_in_memory_limiter_when_redis_is_unreachable() throws IOException {
    RedisRateLimiter limiter = limiter(60, RateLimitGroup.CREATE_RUN, deadFactory());

    // The in-memory failover (budget 60) returns 59, then 58 for two calls to the same key.
    assertThat(limiter.remaining(key())).contains(59);
    assertThat(limiter.remaining(key())).contains(58);
    assertThat(registry.get("agentflow.redis.rate_limit.fallback_open").counter().count())
        .isEqualTo(2.0);
    // The S13-22 meters record the same outage: a failed latency sample, one error, one fallback
    // decision — bounded labels only.
    assertThat(
            registry
                .get("agentflow.redis.command.latency.seconds")
                .tags("operation", "rate_limit", "result", "failed")
                .timer()
                .count())
        .isEqualTo(2L);
    assertThat(
            registry
                .get("agentflow.redis.command.errors.total")
                .tags("operation", "rate_limit")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(
            registry
                .get("agentflow.redis.rate_limit.decision.total")
                .tags("group", "create_run", "result", "fallback")
                .counter()
                .count())
        .isEqualTo(2.0);
  }

  @Test
  @DisplayName(
      "a slow Redis is bounded by the command timeout and fails open to the in-memory limiter")
  void slow_responses_are_bounded_by_the_command_timeout_and_fail_open() throws IOException {
    // A reachable but hanging Redis: the connection is accepted, the command never gets a reply.
    RedisRateLimiter limiter = limiter(60, RateLimitGroup.CREATE_RUN, slowFactory(1));

    long start = System.nanoTime();
    Optional<Integer> remaining = limiter.remaining(key());
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    // The failover is the in-memory budget (60): 59 for the first call. The bounded command timeout
    // must return a decision (never a hang) — at least the 1 s timeout actually elapsed, and an
    // unbounded pin is impossible: the assertion leaves generous headroom over the 1 s test bound
    // (the production bound is 5 s) while proving nothing close to an unbounded hang.
    assertThat(remaining).contains(59);
    assertThat(elapsedMs).isGreaterThanOrEqualTo(900);
    assertThat(elapsedMs).isLessThan(5_000);
    assertThat(registry.get("agentflow.redis.rate_limit.fallback_open").counter().count())
        .isEqualTo(1.0);
  }

  private static RateLimitKey key() {
    return new RateLimitKey(TENANT, "actor", RateLimitGroup.CREATE_RUN);
  }
}
