package com.example.agentflow.common.redis;

import java.util.Optional;

/**
 * The Redis access port ({@code S12-18}): every Redis interaction goes through it, so the format,
 * tenant-scoping and TTL contract lives in the value/key types and this single interface instead of
 * being repeated and drift-prone across the rate limiter ({@code S12-19}) and the cache ({@code
 * S14-18}).
 *
 * <p>The port is intentionally minimal — put (with a mandatory positive {@link RedisTtl}) and get.
 * There is no {@code put} without a TTL, so "write without TTL" is impossible, and the key is
 * always a built {@link RedisKey} (never a hand-assembled string), so the key contract cannot be
 * bypassed. The implementation ({@code S12-19}) is the concrete Redis client.
 *
 * <p>Reads return {@link Optional}, so "Redis has no entry" is distinct from an empty string, and a
 * cache miss never masquerades as a stored value.
 */
public interface RedisKeyValueStore {

  /**
   * Stores {@code value} under {@code key}, expiring after {@code ttl}.
   *
   * @param key the built key; its format, tenant-scoping and no-secret rules are enforced by the
   *     {@link RedisKeyBuilder}
   * @param value the encoded value; its size and no-secret rules are enforced by the {@link
   *     RedisValueCodec}
   * @param ttl the mandatory, positive time-to-live
   */
  void put(RedisKey key, RedisValue value, RedisTtl ttl);

  /**
   * Reads the raw payload stored under {@code key}.
   *
   * @param key the built key
   * @return the stored payload, or empty when there is no entry
   */
  Optional<byte[]> get(RedisKey key);
}
