package com.example.agentflow.common.redis;

import com.example.agentflow.common.SecretRedactor;

/**
 * The single builder that assembles every Redis key and enforces the key + TTL contract ({@code
 * S12-18}, {@code ADR-021}). Everything that touches Redis writes through here, so the key format,
 * the closed {@link RedisPurpose} set, the tenant-scoping rule and the no-secret rule are expressed
 * once and cannot drift between the rate limiter, the cache and any later Redis use.
 *
 * <p>Key shape: {@code agentflow:{env}:{purpose}:{tenantId}:&hellip;}.
 *
 * <ul>
 *   <li>{@code agentflow} &mdash; a fixed prefix ({@link #KEY_PREFIX});
 *   <li>{@code env} &mdash; the environment tag the builder is constructed with (e.g. {@code local}
 *       or {@code prod}); it is a trusted, bounded config value, never caller-controlled;
 *   <li>{@code purpose} &mdash; one of the closed {@link RedisPurpose} set ({@code ratelimit},
 *       {@code cache}); anything else is rejected;
 *   <li>{@code tenantId} &mdash; present for every permitted purpose (both are tenant-scoped), so
 *       cross-tenant keys differ inside the namespace and never overlap;
 *   <li>{@code &hellip;} &mdash; the suffix segments, each scanned before it is written.
 * </ul>
 *
 * <p>Guards enforced on every build (all failures are {@link IllegalArgumentException}, so the
 * refusal is an unclassified-contract fault rather than a runtime crash): the tenant-scoped purpose
 * requires a {@code tenantId}; each suffix segment must be a short, identifier-like value with no
 * whitespace or control characters, at most {@link #MAX_SEGMENT_LENGTH} characters and free of any
 * secret signature (bearer token, API key, AWS key, prompt / document field); the assembled key at
 * most {@link #MAX_KEY_LENGTH} characters. A secret never appears in an error message, so a
 * rejected key cannot leak into a log or metric (invariant 8).
 *
 * <p>Deliberately a plain utility (the {@code Fingerprint} idiom, not a Spring bean): it holds no
 * state beyond its {@code env} and no business logic, so it lives in {@code common} as a shared
 * primitive the rate limiter ({@code S12-19}) and the cache ({@code S14-18}) share. Construct one
 * per deployment (with its {@code env}) and reuse it; build each key from a fresh {@link
 * RedisKeySpec}.
 */
public final class RedisKeyBuilder {

  /** Fixed key prefix written as the first namespace segment. */
  public static final String KEY_PREFIX = "agentflow";

  private static final int MAX_ENV_LENGTH = 24;
  private static final int MAX_SEGMENT_LENGTH = 40;
  private static final int MAX_KEY_LENGTH = 200;

  private static final String NO_TENANT_MESSAGE =
      "a tenant-scoped redis purpose must be built with a tenantId, so another tenant's key can "
          + "never be confused with a missing one";

  private final String env;

  /**
   * @param env the environment tag (e.g. {@code local}, {@code prod}); 1&ndash;24 trusted
   *     characters; it is written into the key namespace as the second segment
   * @throws IllegalArgumentException when {@code env} is blank or longer than {@link
   *     #MAX_ENV_LENGTH}
   */
  public RedisKeyBuilder(String env) {
    if (env == null || env.isBlank() || env.length() > MAX_ENV_LENGTH) {
      throw new IllegalArgumentException(
          "redis key environment tag must be 1-"
              + MAX_ENV_LENGTH
              + " characters, was '"
              + mask(env)
              + "'");
    }
    this.env = env;
  }

  /**
   * @return the environment tag this builder writes
   */
  public String env() {
    return env;
  }

  /**
   * Builds the key for a spec.
   *
   * @param spec the key description; the purpose is already resolved (unknown purposes never reach
   *     here), the tenantId is optional and the suffix segments carry the value identity
   * @return the built, immutable key
   * @throws IllegalArgumentException when the purpose is tenant-scoped without a tenantId, when any
   *     suffix segment carries whitespace, a control character, a secret or exceeds the segment
   *     bound, or when the assembled key exceeds {@link #MAX_KEY_LENGTH}
   */
  public RedisKey build(RedisKeySpec spec) {
    if (spec.purpose().isTenantScoped() && spec.tenantId() == null) {
      throw new IllegalArgumentException(NO_TENANT_MESSAGE);
    }

    StringBuilder key = new StringBuilder(64);
    key.append(KEY_PREFIX).append(':').append(env).append(':').append(spec.purpose().wireName());

    if (spec.purpose().isTenantScoped()) {
      key.append(':').append(spec.tenantId());
    }

    for (String part : spec.suffixParts()) {
      assertSegment(part);
      key.append(':').append(part);
    }

    if (key.length() > MAX_KEY_LENGTH) {
      throw new IllegalArgumentException(
          "redis key must not exceed " + MAX_KEY_LENGTH + " characters");
    }

    return new RedisKey(key.toString(), spec.purpose(), spec.tenantId(), spec.suffixParts());
  }

  /**
   * @param part a suffix segment to validate
   * @throws IllegalArgumentException when the segment carries whitespace, a control character, a
   *     secret signature, or is longer than {@link #MAX_SEGMENT_LENGTH}
   */
  private void assertSegment(String part) {
    if (part.chars().anyMatch(ch -> Character.isWhitespace(ch) || ch < 0x20 || ch == 0x7F)) {
      throw new IllegalArgumentException(
          "redis key segment must not contain whitespace or control characters");
    }
    if (part.length() > MAX_SEGMENT_LENGTH) {
      throw new IllegalArgumentException(
          "redis key segment must not exceed " + MAX_SEGMENT_LENGTH + " characters");
    }
    if (SecretRedactor.containsSecret(part)) {
      throw new IllegalArgumentException(
          "redis key segment must not carry a secret, PII, prompt or document content");
    }
  }

  /**
   * @param s the value (possibly null or long); returns a length descriptor, never the raw value,
   *     so a rejected secret cannot leak into the message
   */
  private static String mask(String s) {
    return "(length " + (s == null ? 0 : s.length()) + ")";
  }
}
