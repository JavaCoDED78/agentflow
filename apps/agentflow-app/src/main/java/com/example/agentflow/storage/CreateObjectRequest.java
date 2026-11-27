package com.example.agentflow.storage;

import java.io.InputStream;
import java.util.Map;

/**
 * The {@code put} input (S07-06B, contract §2.2 and §5.1).
 *
 * <p>Immutable, provider-neutral, JDK-only. Ownership rule (contract §3): the caller transfers the
 * stream to the adapter when the call starts — the adapter must close or release it on success,
 * failure and cancellation, and the caller must not reuse it once the operation has started. The
 * request carries the stream <em>by reference</em> (never a copy); the adapter may only consume it
 * through a bounded chunked reader, never {@code readAllBytes()}.
 *
 * <p>No tenant, run, prompt or document text — metadata is a bounded flat string-to-string map (at
 * most 10 entries, keys up to 128 chars matching the structural pattern, values up to 256 chars
 * with no control characters, and no secret-shaped keys or values) — the contract refuses anything
 * outside its whitelist (contract §5.1, §6). No bucket: the adapter is bound to its configured
 * bucket.
 *
 * @param key the object key (server-generated and tenant-scoped); structurally validated
 * @param stream the payload stream; never {@code null}; the caller transfers ownership when the
 *     call starts
 * @param contentType the media type; non-blank, at most 255 chars, no control characters
 * @param contentLength the declared byte length; {@code null} means "not declared"; when present,
 *     must be at least 0
 * @param sha256 the declared lower-case hex SHA-256 of the payload; {@code null} means "not
 *     declared"; when present, exactly 64 lowercase hex characters
 * @param metadata bounded flat string metadata, never secret-shaped; defensive copy
 */
public record CreateObjectRequest(
    String key,
    InputStream stream,
    String contentType,
    Long contentLength,
    String sha256,
    Map<String, String> metadata) {

  /**
   * Compact constructor.
   *
   * @throws ObjectStorageException with {@code INVALID_KEY} for key violations, with {@code
   *     INVALID_REQUEST} for every other structural violation
   */
  public CreateObjectRequest {
    StorageValidation.validateKey(key);
    if (stream == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "stream must not be null");
    }
    StorageValidation.validateContentType(contentType);
    StorageValidation.validateOptionalLength(contentLength);
    StorageValidation.validateOptionalSha256(sha256);
    if (metadata == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "metadata must not be null");
    }
    StorageValidation.validateMetadata(metadata);
    metadata = Map.copyOf(metadata);
  }
}
