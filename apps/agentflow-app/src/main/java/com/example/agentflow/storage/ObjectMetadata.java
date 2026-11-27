package com.example.agentflow.storage;

import java.time.Instant;

/**
 * The {@code head} result view (S07-06B, contract §2.2).
 *
 * <p>Carries the five {@link ObjectReference} fields plus the provider-neutral {@code lastModified}
 * and {@code etag} — presence plus metadata, never a stream. An empty object is a legitimate value
 * ({@code size = 0} with the empty-string SHA-256). No tenant, run, prompt or content — see
 * contract §6.
 *
 * @param bucket the configured bucket the object was written to
 * @param key the object key (structurally validated)
 * @param contentType the media type stored with the object
 * @param size the stored size in bytes; at least 0
 * @param sha256 lower-case hex SHA-256 of the stored bytes; exactly 64 hex characters
 * @param lastModified the provider's last-modified instant; never {@code null}
 * @param etag the provider's ETag, provider-neutral; non-blank, at most 256 chars, no control
 *     characters
 */
public record ObjectMetadata(
    String bucket,
    String key,
    String contentType,
    long size,
    String sha256,
    Instant lastModified,
    String etag) {

  /**
   * Compact constructor.
   *
   * @throws ObjectStorageException with {@code INVALID_KEY} for key violations, with {@code
   *     INVALID_REQUEST} for every other structural violation
   */
  public ObjectMetadata {
    StorageValidation.validateBucket(bucket);
    StorageValidation.validateKey(key);
    StorageValidation.validateContentType(contentType);
    StorageValidation.validateSize(size);
    StorageValidation.validateSha256(sha256);
    StorageValidation.validateLastModified(lastModified);
    StorageValidation.validateEtag(etag);
  }
}
