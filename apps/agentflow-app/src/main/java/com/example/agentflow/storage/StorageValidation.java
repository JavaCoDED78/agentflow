package com.example.agentflow.storage;

import java.util.Map;
import java.util.Set;

/**
 * Package-private validation rules of the {@link ObjectStoragePort} contract (S07-06B, contract
 * §2.3 and §5.1).
 *
 * <p>The contract's structural rejections are raised <em>before</em> any port operation runs: every
 * value type runs these checks in its compact constructor and throws {@link ObjectStorageException}
 * with {@link ObjectStorageErrorCode#INVALID_KEY} or {@link ObjectStorageErrorCode#INVALID_REQUEST}
 * (the caller-side rejections of the contract's error table). The adapter repeats the structural
 * checks on its string arguments before constructing any value — never silently normalizing.
 *
 * <p>The rules deliberately avoid inventing semantic constraints (no tenant parsing, no content
 * sniffing, no prompt detection): a value is structurally valid or it is a deterministic reject
 * with a stable code.
 */
final class StorageValidation {

  /** Maximum object-key length in characters — S3's key limit (contract §5.1). */
  static final int MAX_KEY_LENGTH = 1024;

  /** S3's bucket-name limit (3-63 chars). */
  static final int MAX_BUCKET_LENGTH = 63;

  /** Bounded media-type length — the contract requires a non-blank, structurally clean type. */
  static final int MAX_CONTENT_TYPE_LENGTH = 255;

  /** Bounded metadata: at most this many entries (contract §5.1). */
  static final int MAX_METADATA_ENTRIES = 10;

  /** Bounded metadata keys (contract §5.1). */
  static final int MAX_METADATA_KEY_LENGTH = 128;

  /** Bounded metadata values (contract §5.1). */
  static final int MAX_METADATA_VALUE_LENGTH = 256;

  /** Maximum etag length (provider-neutral string, structurally bounded). */
  static final int MAX_ETAG_LENGTH = 256;

  /** Lowercase-hex 64-character SHA-256 (contract §2.1/§5.1). */
  static final String SHA256_HEX_PATTERN = "[0-9a-f]{64}";

  /**
   * Structural key pattern: a leading alphanumeric, then alphanumerics, dots, dashes or
   * underscores.
   */
  static final String METADATA_KEY_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]*";

  /**
   * Secret-shaped markers — a structural best-effort reject, not a proof of absence of secrets (a
   * keyword matcher cannot see a credential encoded inside a longer value). The contract forbids
   * secrets, prompts and document text in object metadata (§5.1); anything carrying a credential
   * shape is refused outright.
   */
  private static final Set<String> SECRET_SHAPED_MARKERS =
      Set.of(
          "secret",
          "password",
          "credential",
          "token",
          "authorization",
          "api_key",
          "apikey",
          "access_key",
          "secret_key",
          "private_key",
          "client_secret",
          "bearer",
          "signing_key",
          "private_token");

  private StorageValidation() {}

  /**
   * Validates an object key against the contract's structural rules.
   *
   * @param key the key; must not be null, blank or over {@link #MAX_KEY_LENGTH} characters, must
   *     contain no control characters and no {@code ..} path segment
   * @throws ObjectStorageException with {@code INVALID_KEY} on any violation
   */
  static void validateKey(String key) {
    if (key == null || key.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_KEY, "key must not be null or blank");
    }
    if (key.length() > MAX_KEY_LENGTH) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_KEY,
          "key must not exceed " + MAX_KEY_LENGTH + " characters, was " + key.length());
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c < 0x20) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_KEY, "key must not contain control characters");
      }
    }
    for (String segment : key.split("/", -1)) {
      if (segment.equals("..")) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_KEY, "key must not contain '..' path segments");
      }
    }
  }

  /**
   * Validates a bucket name against the contract's structural rules (contract §2.1).
   *
   * @throws ObjectStorageException with {@code INVALID_REQUEST} on any violation
   */
  static void validateBucket(String bucket) {
    if (bucket == null || bucket.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "bucket must not be null or blank");
    }
    if (bucket.length() > MAX_BUCKET_LENGTH) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST,
          "bucket must not exceed " + MAX_BUCKET_LENGTH + " characters, was " + bucket.length());
    }
    for (int i = 0; i < bucket.length(); i++) {
      if (bucket.charAt(i) < 0x20) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST, "bucket must not contain control characters");
      }
    }
  }

  /**
   * Validates a media type against the contract's structural rules.
   *
   * @throws ObjectStorageException with {@code INVALID_REQUEST} on any violation
   */
  static void validateContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "contentType must not be null or blank");
    }
    if (contentType.length() > MAX_CONTENT_TYPE_LENGTH) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST,
          "contentType must not exceed "
              + MAX_CONTENT_TYPE_LENGTH
              + " characters, was "
              + contentType.length());
    }
    for (int i = 0; i < contentType.length(); i++) {
      if (contentType.charAt(i) < 0x20) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST,
            "contentType must not contain control characters");
      }
    }
  }

  /**
   * Validates a SHA-256 hex digest (contract §2.1).
   *
   * @param sha256 the declared digest; must be non-blank and exactly 64 lowercase hex characters
   * @throws ObjectStorageException with {@code INVALID_REQUEST} on any violation
   */
  static void validateSha256(String sha256) {
    if (sha256 == null || sha256.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "sha256 must not be null or blank");
    }
    if (!sha256.matches(SHA256_HEX_PATTERN)) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST,
          "sha256 must be exactly 64 lowercase hex characters");
    }
  }

  /**
   * Validates an optional declared SHA-256 digest. {@code null} is accepted (the caller declared no
   * checksum — the adapter must still verify the actual bytes against the declared length or report
   * the gap honestly).
   *
   * @throws ObjectStorageException with {@code INVALID_REQUEST} if present and invalid
   */
  static void validateOptionalSha256(String sha256) {
    if (sha256 != null) {
      validateSha256(sha256);
    }
  }

  /**
   * Validates a size field: must be at least 0 (an empty object is a legitimate, first-class case).
   *
   * @throws ObjectStorageException with {@code INVALID_REQUEST} if negative
   */
  static void validateSize(long size) {
    if (size < 0) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "size must be at least 0, was " + size);
    }
  }

  /**
   * Validates a declared content length: must be at least 0 when present.
   *
   * @throws ObjectStorageException with {@code INVALID_REQUEST} if negative
   */
  static void validateOptionalLength(Long contentLength) {
    if (contentLength != null && contentLength < 0) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST,
          "contentLength must be at least 0, was " + contentLength);
    }
  }

  /**
   * Validates a last-modified instant: must not be null.
   *
   * @throws ObjectStorageException with {@code INVALID_REQUEST} if null
   */
  static void validateLastModified(java.time.Instant lastModified) {
    if (lastModified == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "lastModified must not be null");
    }
  }

  /**
   * Validates an etag: must be non-blank, structurally bounded and free of control characters.
   *
   * @throws ObjectStorageException with {@code INVALID_REQUEST} on any violation
   */
  static void validateEtag(String etag) {
    if (etag == null || etag.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "etag must not be null or blank");
    }
    if (etag.length() > MAX_ETAG_LENGTH) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST,
          "etag must not exceed " + MAX_ETAG_LENGTH + " characters, was " + etag.length());
    }
    for (int i = 0; i < etag.length(); i++) {
      if (etag.charAt(i) < 0x20) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST, "etag must not contain control characters");
      }
    }
  }

  /**
   * Validates metadata against the contract's bounded-map rules (§5.1): at most {@link
   * #MAX_METADATA_ENTRIES} entries; keys 1-{@link #MAX_METADATA_KEY_LENGTH} characters matching the
   * structural pattern; values up to {@link #MAX_METADATA_VALUE_LENGTH} characters with no control
   * characters; no secret-shaped keys or values (a structural best-effort reject, never a proof of
   * absence of secrets); never a nested map or a non-string value (the contract requires a flat
   * string-to-string map).
   *
   * @throws ObjectStorageException with {@code INVALID_REQUEST} on any violation
   */
  static void validateMetadata(Map<String, String> metadata) {
    if (metadata == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "metadata must not be null");
    }
    if (metadata.size() > MAX_METADATA_ENTRIES) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST,
          "metadata must not exceed " + MAX_METADATA_ENTRIES + " entries, was " + metadata.size());
    }
    for (Map.Entry<String, String> entry : metadata.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || key.isBlank()) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST, "metadata key must not be null or blank");
      }
      if (key.length() > MAX_METADATA_KEY_LENGTH) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST,
            "metadata key must not exceed "
                + MAX_METADATA_KEY_LENGTH
                + " characters, was "
                + key.length());
      }
      if (!key.matches(METADATA_KEY_PATTERN)) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST,
            "metadata key must match " + METADATA_KEY_PATTERN);
      }
      if (isSecretShaped(key)) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST, "metadata key must not be secret-shaped");
      }
      if (value == null) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST, "metadata value must not be null");
      }
      if (value.length() > MAX_METADATA_VALUE_LENGTH) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST,
            "metadata value must not exceed "
                + MAX_METADATA_VALUE_LENGTH
                + " characters, was "
                + value.length());
      }
      for (int i = 0; i < value.length(); i++) {
        if (value.charAt(i) < 0x20) {
          throw new ObjectStorageException(
              ObjectStorageErrorCode.INVALID_REQUEST,
              "metadata value must not contain control characters");
        }
      }
      if (isSecretShaped(value)) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST, "metadata value must not be secret-shaped");
      }
    }
  }

  private static boolean isSecretShaped(String text) {
    String lower = text.toLowerCase();
    for (String marker : SECRET_SHAPED_MARKERS) {
      if (lower.contains(marker)) {
        return true;
      }
    }
    return false;
  }
}
