package com.example.agentflow.common.redis;

import java.time.Duration;

/**
 * A mandatory, positive Redis time-to-live ({@code S12-18}). TTL is part of the Redis contract
 * ({@code ADR-021}): every written key expires, so a Redis outage can never leave a stale value
 * pinned forever. The {@code RedisKeyValueStore} port takes a {@link RedisTtl} on write, and there
 * is no {@code put} without one, so "write without TTL" is impossible.
 *
 * <p>All TTLs are bounded by the {@code max} factory (the rate limiter ({@code S12-19}) and cache
 * ({@code S14-18}) each pick a value within that bound), so no caller can write an unbounded or
 * zero TTL.
 */
public final class RedisTtl {

  /** The upper bound any caller may request. Values are rejected above this ({@code max}). */
  public static final long MAX_SECONDS = 300L;

  private final Duration duration;

  private RedisTtl(Duration duration) {
    this.duration = duration;
  }

  /**
   * @param seconds the TTL in seconds; must be positive and no greater than {@link #MAX_SECONDS}
   * @return the TTL
   * @throws IllegalArgumentException when {@code seconds} is not a positive value within {@link
   *     #MAX_SECONDS}
   */
  public static RedisTtl of(long seconds) {
    Duration d = Duration.ofSeconds(seconds);
    if (d.isZero() || d.isNegative()) {
      throw new IllegalArgumentException("redis ttl must be positive, was " + d);
    }
    if (d.getSeconds() > MAX_SECONDS) {
      throw new IllegalArgumentException(
          "redis ttl must be at most " + MAX_SECONDS + " seconds, was " + d);
    }
    return new RedisTtl(d);
  }

  /**
   * @param duration the TTL; must be positive and no greater than {@link #MAX_SECONDS}
   * @return the TTL
   * @throws IllegalArgumentException when {@code duration} is null, zero, negative, or exceeds
   *     {@link #MAX_SECONDS}
   */
  public static RedisTtl of(Duration duration) {
    requirePositive(duration);
    if (duration.getSeconds() > MAX_SECONDS) {
      throw new IllegalArgumentException(
          "redis ttl must be at most " + MAX_SECONDS + " seconds, was " + duration);
    }
    return new RedisTtl(duration);
  }

  /**
   * @return the TTL as a positive {@link Duration}
   */
  public Duration duration() {
    return duration;
  }

  private static void requirePositive(Duration duration) {
    if (duration == null) {
      throw new IllegalArgumentException("redis ttl must not be null");
    }
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("redis ttl must be positive, was " + duration);
    }
  }
}
