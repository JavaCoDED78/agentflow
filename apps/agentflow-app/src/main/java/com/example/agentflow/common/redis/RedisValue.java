package com.example.agentflow.common.redis;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * An immutable Redis value ({@code S12-18}): a {@link #formatVersion()} stamp and a defensive copy
 * of the payload bytes. Callers never hold a reference to the backing array, so a store or cache
 * can mutate or reuse its own storage without exposing one client's bytes to another.
 *
 * <p>The version stamp is the seam for a future, lossy Redis value schema (compression, encoding,
 * format bump). Until then every value is {@code 0.1} and round-trips as UTF-8; see {@link
 * RedisValueCodec}.
 */
public final class RedisValue {

  /** The value envelope format stamp. Bump only on a backward-incompatible decode change. */
  public static final String FORMAT_VERSION_0_1 = "0.1";

  private final String formatVersion;
  private final byte[] payload;

  private RedisValue(String formatVersion, byte[] payload) {
    this.formatVersion = formatVersion;
    this.payload = Arrays.copyOf(payload, payload.length);
  }

  /**
   * Wraps {@code content} as a {@code 0.1} envelope with a defensive copy of its UTF-8 bytes.
   *
   * @param content the value; {@code null} is rejected so a missing value is read as an absent key,
   *     not an empty one
   * @return the wrapped value
   */
  public static RedisValue wrap(String content) {
    return new RedisValue(FORMAT_VERSION_0_1, content.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Wraps raw bytes as a {@code 0.1} envelope with a defensive copy.
   *
   * @param payload the bytes
   * @return the wrapped value
   */
  public static RedisValue wrap(byte[] payload) {
    return new RedisValue(FORMAT_VERSION_0_1, Arrays.copyOf(payload, payload.length));
  }

  /**
   * @return the envelope format version, e.g. {@code 0.1}
   */
  public String formatVersion() {
    return formatVersion;
  }

  /**
   * @return the payload bytes
   */
  public byte[] payload() {
    return Arrays.copyOf(payload, payload.length);
  }
}
