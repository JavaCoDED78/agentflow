package com.example.agentflow.storage;

/**
 * The opaque, persistable handle to a stored object (S07-06B, contract §2.1).
 *
 * <p>The reference is the value persisted alongside the metadata row ({@code documents} keeps the
 * key and sha256; {@code workflow_step_runs.output_ref} keeps the ref/checksum/size) and is
 * returned by {@link ObjectStoragePort#put} and {@link ObjectStoragePort#list}. It is a plain value
 * — no provider hooks, no provider types — so it travels through the contract boundary, PostgreSQL
 * rows and the caller unchanged.
 *
 * <p>It validates itself deterministically: a structurally invalid reference is a contract reject
 * ({@link ObjectStorageException} with {@link ObjectStorageErrorCode#INVALID_KEY} or {@link
 * ObjectStorageErrorCode#INVALID_REQUEST}), never a silent acceptance. No content, no credential,
 * no tenant or run id — see contract §6.
 *
 * @param bucket the configured bucket the object was written to (self-describing; never re-chosen
 *     by the caller); 3-63 chars, no control characters
 * @param key the object key (server-generated and tenant-scoped; never the user's); structurally
 *     validated: non-blank, at most 1024 chars, no control characters, no {@code ..} path segment
 * @param contentType the media type stored with the object, as accepted by the contract; non-blank,
 *     at most 255 chars, no control characters
 * @param size the stored size in bytes; at least 0 (an empty object is a legitimate, first-class
 *     case: {@code size = 0} with the empty-string SHA-256)
 * @param sha256 lower-case hex SHA-256 of the stored bytes; exactly 64 hex characters
 */
public record ObjectReference(
    String bucket, String key, String contentType, long size, String sha256) {

  /**
   * Compact constructor.
   *
   * @throws ObjectStorageException with {@code INVALID_REQUEST} for bucket / content type / size /
   *     sha256 violations, with {@code INVALID_KEY} for key violations
   */
  public ObjectReference {
    StorageValidation.validateBucket(bucket);
    StorageValidation.validateKey(key);
    StorageValidation.validateContentType(contentType);
    StorageValidation.validateSize(size);
    StorageValidation.validateSha256(sha256);
  }
}
