package com.example.agentflow.knowledgebase.domain;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The closed allowlist of content types an upload may carry, {@code S10-03}. The set is small and
 * fixed on purpose: the accepted type is never taken from an untrusted source, so a hostile or
 * prompt-injected {@code content-type} cannot smuggle in a dangerous type (for example
 * {@code application/javascript} or an executable). Each entry also carries the <b>magic bytes</b>
 * the payload must start with, so the declared type is verified against the real bytes of the
 * payload (not just the client-supplied header) — a payload that misrepresents its own type is
 * rejected.
 *
 * <p>Three categories of entry:
 *
 * <ul>
 *   <li><b>Binary, magic-byte enforced</b> — {@code application/pdf}, {@code image/png}, {@code
 *       image/jpeg}, {@code image/gif}, {@code image/bmp}: the payload must begin with one of the
 *       declared signatures.
 *   <li><b>RIFF/WAVE media</b> — {@code audio/wav}: the {@code RIFF}…{@code WAVE} container
 *       structure is checked (and its duration is separately bounded by {@code
 *       DocumentUploadLimits#getMaxMediaDurationSeconds()}).
 *   <li><b>Text-like</b> — {@code text/*}, {@code application/json}, {@code application/xml}: there
 *       is no reliable magic signature for text, so the check is light — the payload is UTF-8 with
 *       no {@code NUL} byte and, where it matters, starts with the expected structural character
 *       ({@code {}/{@code [} for JSON, {@code <} for XML). The allowlist, not the bytes, is the
 *       primary defence for text; text is never executed (invariant 7).
 * </ul>
 *
 * <p>Instances are immutable value objects. Use {@link #from(String)} to resolve a declared type
 * against the allowlist; it rejects anything not on it (a {@code 400} at the HTTP layer).
 */
public final class DocumentContentType {

  /** The canonical, lowercase content type, for example {@code application/pdf}. */
  private final String contentType;

  /** The optional extension this type maps to, appended as a key hint (for example {@code pdf}). */
  private final String extension;

  /** Whether this type is media whose decoded duration is bounded (currently only {@code wav}). */
  private final boolean media;

  /**
   * The magic-byte signatures this type must start with. Empty for the text-like types, which are
   * checked by {@link #textLikeMatches(byte[])} instead.
   */
  private final List<byte[]> signatures;

  /** Whether the type is a RIFF/WAVE container with its own {@code RIFF}…{@code WAVE} structure. */
  private final boolean riffWave;

  /** The structural leading character required by the text-like types ({@code null} for binary). */
  private final String textPrefix;

  private static final Map<String, DocumentContentType> BY_TYPE = new LinkedHashMap<>();

  static {
    BY_TYPE.put(
        "application/pdf",
        new DocumentContentType("application/pdf", "pdf", false, sigs("%PDF-"), false, null));
    BY_TYPE.put(
        "image/png",
        new DocumentContentType(
            "image/png",
            "png",
            false,
            List.of(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}),
            false,
            null));
    BY_TYPE.put(
        "image/jpeg",
        new DocumentContentType(
            "image/jpeg",
            "jpeg",
            false,
            List.of(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            false,
            null));
    BY_TYPE.put(
        "image/gif",
        new DocumentContentType("image/gif", "gif", false, sigs("GIF87a", "GIF89a"), false, null));
    BY_TYPE.put(
        "image/bmp", new DocumentContentType("image/bmp", "bmp", false, sigs("BM"), false, null));
    BY_TYPE.put(
        "audio/wav", new DocumentContentType("audio/wav", "wav", true, sigs("RIFF"), true, null));
    BY_TYPE.put(
        "text/plain", new DocumentContentType("text/plain", "txt", false, List.of(), false, null));
    BY_TYPE.put(
        "text/markdown",
        new DocumentContentType("text/markdown", "md", false, List.of(), false, null));
    BY_TYPE.put(
        "text/csv", new DocumentContentType("text/csv", "csv", false, List.of(), false, null));
    BY_TYPE.put(
        "text/richtext",
        new DocumentContentType("text/richtext", "rtf", false, List.of(), false, null));
    BY_TYPE.put(
        "application/json",
        new DocumentContentType("application/json", "json", false, List.of(), false, "{[}"));
    BY_TYPE.put(
        "application/xml",
        new DocumentContentType("application/xml", "xml", false, List.of(), false, "<"));
    BY_TYPE.put(
        "text/xml", new DocumentContentType("text/xml", "xml", false, List.of(), false, "<"));
  }

  private DocumentContentType(
      String contentType,
      String extension,
      boolean media,
      List<byte[]> signatures,
      boolean riffWave,
      String textPrefix) {
    this.contentType = contentType;
    this.extension = extension;
    this.media = media;
    this.signatures = List.copyOf(signatures);
    this.riffWave = riffWave;
    this.textPrefix = textPrefix;
  }

  /** Build several magic-byte signatures from ASCII string literals. */
  private static List<byte[]> sigs(String... literals) {
    byte[][] out = new byte[literals.length][];
    for (int i = 0; i < literals.length; i++) {
      out[i] = literals[i].getBytes(StandardCharsets.US_ASCII);
    }
    return List.of(out);
  }

  /**
   * Resolve a client-declared content type against the allowlist. The declaration is normalised
   * (parameters such as {@code ;charset=...} stripped, trimmed, lower-cased) before lookup; a type
   * that is not on the closed allowlist is rejected so an untrusted header cannot expand the
   * surface.
   *
   * @param declared the client-declared content type (for example {@code "application/pdf"}); may
   *     carry parameters such as {@code ;charset=binary}
   * @return the matched allowlisted content type
   * @throws IllegalArgumentException if the declared type is blank or not on the allowlist
   */
  public static DocumentContentType from(String declared) {
    if (declared == null) {
      throw new IllegalArgumentException("content-type must not be null");
    }
    String normalized = declared.trim();
    int semi = normalized.indexOf(';');
    if (semi >= 0) {
      normalized = normalized.substring(0, semi);
    }
    normalized = normalized.trim().toLowerCase();
    DocumentContentType match = BY_TYPE.get(normalized);
    if (match == null) {
      throw new IllegalArgumentException("content type is not permitted: " + declared);
    }
    return match;
  }

  /**
   * @return the canonical content type
   */
  public String contentType() {
    return contentType;
  }

  /**
   * @return the optional content-type hint extension, when this type maps to one
   */
  public String extension() {
    return extension;
  }

  /**
   * @return whether the decoded duration of this type is bounded (media types only)
   */
  public boolean isMedia() {
    return media;
  }

  /**
   * Verify a payload's leading bytes against this content type's declared magic bytes (binary
   * types) or its light text-like signature (text-like types). A {@code null} or empty payload
   * never matches.
   *
   * @param payload the raw upload bytes
   * @return {@code true} when the payload begins with the bytes the type requires
   */
  public boolean matches(byte[] payload) {
    if (payload == null || payload.length == 0) {
      return false;
    }
    if (riffWave) {
      return isRiffWave(payload);
    }
    if (!signatures.isEmpty()) {
      for (byte[] signature : signatures) {
        if (hasPrefix(payload, signature)) {
          return true;
        }
      }
      return false;
    }
    return textLikeMatches(payload);
  }

  private boolean isRiffWave(byte[] payload) {
    if (payload.length < 12) {
      return false;
    }
    return hasPrefix(payload, "RIFF".getBytes(StandardCharsets.US_ASCII))
        && hasPrefix(payload, "WAVE".getBytes(StandardCharsets.US_ASCII), 8);
  }

  private boolean textLikeMatches(byte[] payload) {
    if (!hasNoNul(payload)) {
      return false;
    }
    if (textPrefix == null) {
      return true;
    }
    char first = firstSignificantChar(payload);
    return textPrefix.indexOf(first) >= 0;
  }

  /**
   * @return the first non-whitespace byte of the payload as a char; {@code '\0'} when all
   *     whitespace
   */
  private char firstSignificantChar(byte[] payload) {
    for (byte b : payload) {
      if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
        return (char) (b & 0xFF);
      }
    }
    return '\0';
  }

  private static boolean hasNoNul(byte[] payload) {
    for (byte b : payload) {
      if (b == 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasPrefix(byte[] payload, byte[] signature) {
    return hasPrefix(payload, signature, 0);
  }

  private static boolean hasPrefix(byte[] payload, byte[] signature, int offset) {
    if (offset + signature.length > payload.length) {
      return false;
    }
    for (int i = 0; i < signature.length; i++) {
      if (payload[offset + i] != signature[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * @param declared a client-declared content type
   * @return {@code true} when the type is on the allowlist (a fast allowlist membership probe that
   *     does not throw)
   */
  public static boolean isPermitted(String declared) {
    if (declared == null) {
      return false;
    }
    String normalized = declared.trim();
    int semi = normalized.indexOf(';');
    if (semi >= 0) {
      normalized = normalized.substring(0, semi);
    }
    return BY_TYPE.containsKey(normalized.trim().toLowerCase());
  }
}
