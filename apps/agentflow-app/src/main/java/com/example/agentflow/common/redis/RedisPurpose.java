package com.example.agentflow.common.redis;

import java.util.Map;

/**
 * The closed set of Redis purposes the {@link RedisKeyBuilder} emits ({@code ADR-021}). Every key
 * names its purpose in the namespace {@code agentflow:{env}:{purpose}:{tenantId}:&hellip;}, so a
 * consumer can tell, from the key alone, whether a value is rate-limit state, a rebuildable cache
 * entry or short-lived coordination. The set is closed: a purpose outside it is rejected, so a
 * later author cannot quietly store workflow state, an idempotency key or authorization material
 * under a new purpose and erode the "Redis is only a helper" boundary.
 *
 * <p>Both permitted purposes operate on tenant data and are therefore tenant-scoped, so the builder
 * rejects a key that omits the {@code tenantId}. The {@link #isTenantScoped()} flag keeps the rule
 * expressed once on the value type rather than re-derived by every caller.
 */
public enum RedisPurpose {

  /** Distributed rate-limit state shared across API replicas ({@code S12-19}). */
  RATE_LIMIT("ratelimit", true),

  /** A rebuildable, recomputable cache of data that also lives in PostgreSQL ({@code S14-18}). */
  CACHE("cache", true);

  private static final Map<String, RedisPurpose> BY_WIRE =
      Map.of("ratelimit", RATE_LIMIT, "cache", CACHE);

  private final String wireName;
  private final boolean tenantScoped;

  RedisPurpose(String wireName, boolean tenantScoped) {
    this.wireName = wireName;
    this.tenantScoped = tenantScoped;
  }

  /**
   * @return the wire form written into the key namespace, e.g. {@code ratelimit}
   */
  public String wireName() {
    return wireName;
  }

  /**
   * @return {@code true} when a key of this purpose must carry a {@code tenantId} (both permitted
   *     purposes are tenant-scoped)
   */
  public boolean isTenantScoped() {
    return tenantScoped;
  }

  /**
   * Resolves a purpose from its wire form.
   *
   * @param name the namespace segment, e.g. {@code ratelimit}
   * @return the matching purpose
   * @throws IllegalArgumentException when {@code name} is not one of the permitted purposes — an
   *     unknown purpose is rejected at the single key-building boundary, so it can never open a new
   *     unbounded use of Redis
   */
  public static RedisPurpose fromWire(String name) {
    RedisPurpose purpose = BY_WIRE.get(name);
    if (purpose == null) {
      throw new IllegalArgumentException(
          "unknown redis key purpose '" + name + "'; allowed purposes are " + BY_WIRE.keySet());
    }
    return purpose;
  }
}
