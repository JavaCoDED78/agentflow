package com.example.agentflow.common.rate_limit;

import com.example.agentflow.common.RateLimitKey;
import com.example.agentflow.common.RateLimitPolicy;
import com.example.agentflow.common.RateLimiter;
import com.example.agentflow.common.redis.RedisKey;
import com.example.agentflow.common.redis.RedisKeyBuilder;
import com.example.agentflow.common.redis.RedisKeySpec;
import com.example.agentflow.common.redis.RedisPurpose;
import com.example.agentflow.common.redis.RedisTtl;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis-backed {@link RateLimiter} ({@code S12-19}), the distributed adapter for the {@link
 * RateLimiter} port resolved in {@code S04-16}.
 *
 * <p>The port is satisfied by one adapter at a time (see {@code RateLimitConfiguration} and {@code
 * RateLimitWebMvcConfiguration}); this class is the Redis one, produced only when {@code
 * agentflow.redis.enabled=true}. It is a plain class (never a {@code @Component}) so that, when
 * Redis is disabled, the app boots with the in-memory adapter and no Redis import leaks into the
 * skeleton.
 *
 * <p>Failure mode ({@code ADR-021}): Redis is a caddy / helper component. A Redis outage or command
 * timeout never rejects every request, so this adapter <b>fails open</b> to a bounded in-memory
 * limiter (the same window the Redis one uses). A permanently down Redis therefore throttles the
 * local JVM like the in-memory adapter, not like a hard 429.
 *
 * <p>Every write has a TTL, so a Redis outage can never pin a counter forever: the {@link #SHARED}
 * script {@code INCR}s the key and, on the first writer of the window, {@code PEXPIRE}s it for
 * {@link #TTL}. The {@link RedisTtl} bound ({@code RedisTtl#MAX_SECONDS}) is enforced at the
 * builder.
 *
 * <p>Correctness contract:
 *
 * <ul>
 *   <li>the per-minute budget is derived from the {@link RateLimitPolicy} of the group, exactly
 *       like the in-memory adapter;
 *   <li>the key is {@code
 *       agentflow:{env}:rate-limit:{tenantId}:{group}:{windowSeconds}:{windowIndex}:{hashedPrincipal}}
 *       &mdash; tenant-scoped, deterministic, and bounded by {@link
 *       RedisKeyBuilder#MAX_SEGMENT_LENGTH};
 *   <li>the increment is atomic (the {@link #SHARED} script), so several workers / instances on one
 *       Redis observe the same global limit;
 *   <li>the counter is per window (the window index is part of the key), so it resets on every
 *       window boundary, not on expiry alone.
 * </ul>
 */
public final class RedisRateLimiter implements RateLimiter {

  /**
   * SHA-256 of the principal, hex-encoded (first 96 bits): identifier-like, no whitespace, ≤ 40.
   */
  private static final int HASH_HEX_BYTES = 12;

  /** Fixed 60s sliding window; the window index is part of the key (see the Javadoc). */
  private static final int WINDOW_SECONDS = 60;

  /**
   * The atomic INCR + first-writer PEXPIRE; the write is never TTL-less (invariant: write → TTL).
   */
  private static final String SHARED =
      "local used = redis.call('INCR', KEYS[1])\n"
          + "if used == 1 then\n"
          + "  redis.call('PEXPIRE', KEYS[1], ARGV[1])\n"
          + "end\n"
          + "return used";

  /** The {@link RedisScript} with a {@link Long} output (the script returns the counter). */
  private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(SHARED, Long.class);

  /**
   * TTL of the counter key: two windows, comfortably within the {@link RedisTtl#MAX_SECONDS} bound.
   */
  private static final RedisTtl TTL = RedisTtl.of(2 * WINDOW_SECONDS);

  private final RateLimitPolicy policy;
  private final StringRedisTemplate redis;
  private final RateLimiter failover;
  private final Clock clock;
  private final RedisKeyBuilder keyBuilder;
  private final Counter failoverMeter;

  /**
   * @param policy the resolved per-group policy; the value the adapter uses to derive budgets
   * @param redis the template driving the atomic {@link #SCRIPT}
   * @param failover the in-memory limiter to fall back to on Redis unavailability; mandatory, never
   *     {@code null}
   * @param clock the clock driving the window (UTC); {@link Clock#systemUTC()} in production
   * @param meterRegistry the registry for the fail-open meter ({@code null} is tolerated, the meter
   *     is then skipped)
   * @param keyBuilder the key builder (per deployment env); the adapter builds the tenant-scoped
   *     key
   * @throws IllegalArgumentException when {@code failover} is {@code null}
   */
  public RedisRateLimiter(
      RateLimitPolicy policy,
      StringRedisTemplate redis,
      RateLimiter failover,
      Clock clock,
      MeterRegistry meterRegistry,
      RedisKeyBuilder keyBuilder) {
    this.policy = policy;
    this.redis = redis;
    if (failover == null) {
      throw new IllegalArgumentException("a Redis rate limiter needs a failover limiter");
    }
    this.failover = failover;
    this.clock = clock;
    this.keyBuilder = keyBuilder;
    this.failoverMeter =
        meterRegistry != null
            ? Counter.builder("agentflow.redis.rate_limit.fallback_open").register(meterRegistry)
            : null;
  }

  @Override
  public Optional<Integer> remaining(RateLimitKey key) {
    if (!policy.isThrottled(key.group())) {
      // Nothing to throttle: fail open to an empty result (the interceptor is the guard).
      return Optional.empty();
    }
    try {
      long now = clock.millis();
      int budget = policy.maxPerMinute(key.group());
      RedisKey redisKey =
          keyBuilder.build(
              RedisKeySpec.builder(RedisPurpose.RATE_LIMIT)
                  .tenantId(key.tenantId())
                  .suffix(
                      key.group().configKey(),
                      Long.toString(WINDOW_SECONDS),
                      Long.toString(windowIndex(now)),
                      hashedPrincipal(key.principal()))
                  .build());
      long used =
          redis.execute(
              SCRIPT, List.of(redisKey.value()), Long.toString(TTL.duration().toMillis()));
      return Optional.of(budget - (int) used);
    } catch (RuntimeException exception) {
      // Redis unavailable or command failed (connection failure, command timeout, execution error):
      // fail open to the bounded in-memory limiter (ADR-021), never reject every request. The meter
      // (no high-cardinality labels) records the failover.
      if (failoverMeter != null) {
        failoverMeter.increment();
      }
      return failover.remaining(key);
    }
  }

  private long windowIndex(long nowMillis) {
    return Math.floorDiv(nowMillis, WINDOW_SECONDS * 1000L);
  }

  private static String hashedPrincipal(String principal) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(principal.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(HASH_HEX_BYTES * 2);
      for (int i = 0; i < HASH_HEX_BYTES; i++) {
        sb.append(String.format("%02x", digest[i]));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 message digest unavailable", exception);
    }
  }
}
