package com.example.agentflow.common.redis;

import com.example.agentflow.common.SecretRedactor;
import java.nio.charset.StandardCharsets;

/**
 * Encodes and decodes {@link RedisValue}s ({@code S12-18}): wraps content as a {@code 0.1}
 * envelope, guards the value's size and guarantees the no-secret rule, and reverses the envelope on
 * read.
 *
 * <p>Value guards, enforced on every {@link #encode}, mirror the key guards:
 *
 * <ul>
 *   <li>a secret, PII, prompt or document field is rejected (no secret, PII or prompt is written to
 *       Redis, invariant 8);
 *   <li>a value is rejected above {@link #MAX_VALUE_BYTES} (Redis stores helpers, never large
 *       payloads — per {@code ADR-021}, large payloads go to Garage);
 *   <li>{@code null} content is rejected (a missing value is an absent key, not an empty one).
 * </ul>
 *
 * <p>Decode is lossless (UTF-8); the version stamp is preserved so a future format bump can branch
 * there. A {@code null} value is not decoded — the port should treat it as "absent".
 *
 * <p>A plain utility, not a Spring bean: no state, no business logic, so it shares in {@code
 * common} like the {@link RedisKeyBuilder}.
 */
public final class RedisValueCodec {

  /** The value envelope format stamp accepted on encode; round-trips as UTF-8. */
  public static final String FORMAT_VERSION = RedisValue.FORMAT_VERSION_0_1;

  private static final int MAX_VALUE_BYTES = 1024;

  /** Builds a codec. The codec is stateless, so one shared instance is fine. */
  public RedisValueCodec() {}

  /**
   * Wraps {@code content} in a size- and secret-guarded {@link RedisValue}.
   *
   * @param content the value to store
   * @return the envelope
   * @throws IllegalArgumentException when {@code content} is null, carries a secret/PII/prompt/
   *     document field, or exceeds {@link #MAX_VALUE_BYTES} when UTF-8 encoded
   */
  public RedisValue encode(String content) {
    if (content == null) {
      throw new IllegalArgumentException("redis value content must not be null");
    }
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_VALUE_BYTES) {
      throw new IllegalArgumentException(
          "redis value must not exceed " + MAX_VALUE_BYTES + " UTF-8 bytes");
    }
    if (SecretRedactor.containsSecret(content)) {
      throw new IllegalArgumentException(
          "redis value must not carry a secret, PII, prompt or document content");
    }
    return RedisValue.wrap(bytes);
  }

  /**
   * Decodes a value's payload to UTF-8.
   *
   * @param value the envelope
   * @return the decoded content
   * @throws IllegalArgumentException when {@code value} is null
   */
  public String decode(RedisValue value) {
    if (value == null) {
      throw new IllegalArgumentException("redis value must not be null");
    }
    return new String(value.payload(), StandardCharsets.UTF_8);
  }
}
