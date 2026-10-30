package com.example.agentflow.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Opaque request-payload digests (S06-06).
 *
 * <p>A <em>fingerprint</em> is the lowercase-hex SHA-256 of a request payload. It is a one-way
 * digest, so it proves two requests carry the <em>same</em> payload without ever recovering the
 * payload from the digest &mdash; it is therefore <b>opaque</b> (invariant 8): a fingerprint is
 * never a secret, a full prompt or a full document, and it is never used as an authorization
 * source. It is what {@code POST /api/v1/runs} stores alongside a keyed run (in {@code
 * workflow_runs.idempotency_fingerprint}) so a repeat under the same {@code Idempotency-Key} can be
 * told apart from a payload change on that key.
 */
public final class Fingerprint {

  private Fingerprint() {}

  /**
   * The lowercase-hex SHA-256 of the UTF-8 bytes of {@code text}, the same primitive the version
   * checksum and the ingestion pipeline reuse.
   *
   * @param payload the request payload whose digest to compute
   * @return the 64-character lowercase-hex SHA-256 digest; an empty string for an empty input
   */
  public static String sha256Hex(String payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
      return toHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is required by the JRE specification; it is never missing on a supported platform.
      throw new IllegalStateException("SHA-256 is required to fingerprint the request payload", e);
    }
  }

  /** Encodes bytes as a lowercase hexadecimal string (twice the byte length). */
  private static String toHex(byte[] hash) {
    char[] hex = new char[hash.length * 2];
    for (int i = 0; i < hash.length; i++) {
      int value = hash[i] & 0xFF;
      hex[i * 2] = "0123456789abcdef".charAt(value >> 4);
      hex[i * 2 + 1] = "0123456789abcdef".charAt(value & 0x0F);
    }
    return new String(hex);
  }
}
