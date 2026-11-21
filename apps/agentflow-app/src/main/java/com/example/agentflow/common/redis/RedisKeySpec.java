package com.example.agentflow.common.redis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * An immutable description of the key an {@link RedisKeyBuilder} assembles: the {@link
 * RedisPurpose} (resolved, so an unknown purpose cannot reach the builder), the optional {@code
 * tenantId} and the ordered suffix segments that identify the value within the purpose ({@code
 * S12-18}).
 *
 * <p>The suffix segments are the user-controlled part of the key: for a {@code ratelimit} key they
 * encode the group and a hashed principal, for a {@code cache} key the resource identity. Because
 * they are the place where a secret, a prompt or document text would sneak in, the builder scans
 * every segment before it is written.
 */
public final class RedisKeySpec {

  private final RedisPurpose purpose;
  private final UUID tenantId;
  private final List<String> suffixParts;

  private RedisKeySpec(RedisPurpose purpose, UUID tenantId, List<String> suffixParts) {
    this.purpose = purpose;
    this.tenantId = tenantId;
    this.suffixParts = Collections.unmodifiableList(new ArrayList<>(suffixParts));
  }

  /**
   * @return the resolved purpose
   */
  public RedisPurpose purpose() {
    return purpose;
  }

  /**
   * @return the tenant this key is scoped to, or {@code null} for a purpose that is not tenant
   *     scoped
   */
  public UUID tenantId() {
    return tenantId;
  }

  /**
   * @return the ordered suffix segments, immutable
   */
  public List<String> suffixParts() {
    return suffixParts;
  }

  /**
   * Starts describing a key for a specific purpose.
   *
   * @param purpose the purpose the key names
   * @return a builder for the key spec
   */
  public static Builder builder(RedisPurpose purpose) {
    return new Builder(purpose);
  }

  /** Fluent builder for a {@link RedisKeySpec}. */
  public static final class Builder {

    private final RedisPurpose purpose;
    private UUID tenantId;
    private final List<String> suffixParts = new ArrayList<>();

    private Builder(RedisPurpose purpose) {
      this.purpose = purpose;
    }

    /**
     * @param tenantId the tenant the key is scoped to; ignored for a non-tenant-scoped purpose
     * @return this builder
     */
    public Builder tenantId(UUID tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    /**
     * Appends one or more suffix segments. Each segment becomes its own {@code :}-separated key
     * segment and is scanned by the builder before the key is written.
     *
     * @param parts the suffix segments, in order
     * @return this builder
     */
    public Builder suffix(String... parts) {
      for (String part : parts) {
        if (part == null) {
          throw new IllegalArgumentException("redis key suffix segment must not be null");
        }
        suffixParts.add(part);
      }
      return this;
    }

    /**
     * @return the built spec
     */
    public RedisKeySpec build() {
      return new RedisKeySpec(purpose, tenantId, suffixParts);
    }
  }
}
