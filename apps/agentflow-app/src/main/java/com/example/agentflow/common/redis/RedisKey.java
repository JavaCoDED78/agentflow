package com.example.agentflow.common.redis;

import java.util.List;
import java.util.UUID;

/**
 * A built, immutable Redis key ({@code S12-18}): the assembled {@link #value()} string plus the
 * parsed parts it encodes, so a caller (or a test) can inspect the structure without re-parsing.
 * The value always has the form {@code agentflow:{env}:{purpose}:{tenantId}:&hellip;}, with the
 * {@code tenantId} in the namespace (never a post-filter) so cross-tenant keys never overlap
 * ({@code ADR-021}).
 */
public record RedisKey(
    String value, RedisPurpose purpose, UUID tenantId, List<String> suffixParts) {

  /**
   * @return the assembled key string, {@code agentflow:{env}:{purpose}:{tenantId}:&hellip;}
   */
  @Override
  public String value() {
    return value;
  }
}
