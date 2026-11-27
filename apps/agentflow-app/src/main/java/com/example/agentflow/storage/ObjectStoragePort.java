package com.example.agentflow.storage;

import java.util.Optional;

/**
 * The provider-neutral object storage contract (S07-06B, contract §1 — the spec {@code
 * docs/architecture/object-storage.md} fixes).
 *
 * <p>Exactly five operations — there is no public surface beyond them:
 *
 * <ul>
 *   <li>{@link #put} — a streaming upsert; returns the persistable {@link ObjectReference}
 *   <li>{@link #get} — a streaming read; returns an {@link OpenedObject} whose stream is
 *       caller-owned
 *   <li>{@link #head} — presence + metadata; never throws for a missing key
 *   <li>{@link #list} — paginated, server-side prefix listing; never opens content
 *   <li>{@link #delete} — idempotent; never fails for a missing key
 * </ul>
 *
 * <p>All values are JDK-only ({@code InputStream}, {@link ObjectReference}, {@link ObjectListPage})
 * — no AWS SDK, Garage or provider-specific type may cross this boundary in either direction. The
 * contract deliberately does <b>not</b> enforce tenant scope, parse document state or own any
 * metadata row: the consuming application service authorizes against its own PostgreSQL row (and
 * generates the key) before calling the port; {@code storage} only moves bytes and reports what
 * happened.
 *
 * <p>Every failure — including a structurally invalid request — is thrown as {@link
 * ObjectStorageException} with a stable {@link ObjectStorageErrorCode}; never a raw provider
 * exception.
 */
public interface ObjectStoragePort {

  /**
   * Store a byte stream. Identical re-{@code put} is an idempotent no-op in identity (same
   * reference, one logical object); different content replaces the object atomically. A declared
   * {@code contentLength} or {@code sha256} must verify against the actual bytes — a mismatch is an
   * {@link ObjectStorageErrorCode#INVALID_REQUEST} rejection, never a silent acceptance.
   *
   * <p>The caller transfers stream ownership when the call starts; the adapter must close or
   * release the stream on success, failure and cancellation, and must never buffer the whole stream
   * in heap.
   *
   * @param request the (validated) put input
   * @return the persistable reference for the stored object
   * @throws ObjectStorageException with {@code INVALID_REQUEST} (declared length/checksum
   *     mismatch), {@code ACCESS_DENIED}, {@code UNAVAILABLE}, {@code TIMEOUT} or {@code
   *     PERMANENT_FAILURE}
   */
  ObjectReference put(CreateObjectRequest request) throws ObjectStorageException;

  /**
   * Open an object for streaming read. Missing key → {@link ObjectStorageErrorCode#NOT_FOUND}
   * (typed — never a raw provider exception). The returned stream is caller-owned: read and close
   * it (try-with-resources); the adapter releases the underlying stream on success, exception and
   * cancellation. No heap-loading: the read is bounded-memory.
   *
   * @param key the object key (never a bucket — the adapter is bound to its configured bucket)
   * @return the opened object (metadata + caller-owned stream)
   * @throws ObjectStorageException with {@code INVALID_KEY}, {@code NOT_FOUND}, {@code
   *     ACCESS_DENIED}, {@code UNAVAILABLE}, {@code TIMEOUT} or {@code PERMANENT_FAILURE}
   */
  OpenedObject get(String key) throws ObjectStorageException;

  /**
   * Presence + metadata. Presence is checked without throwing: a missing key yields {@code
   * Optional.empty()}, never an exception.
   *
   * @param key the object key
   * @return the metadata if present, {@code Optional.empty()} if missing
   * @throws ObjectStorageException with {@code INVALID_KEY}, {@code ACCESS_DENIED}, {@code
   *     UNAVAILABLE}, {@code TIMEOUT} or {@code PERMANENT_FAILURE}
   */
  Optional<ObjectMetadata> head(String key) throws ObjectStorageException;

  /**
   * Paginated, server-side prefix listing (ascending key order; the cursor is opaque; {@code
   * nextCursor} is non-null iff {@code hasMore}). This is a reconciliation/inspection operation —
   * never authorization. The prefix is server-generated and tenant-scoped (a consumer passes the
   * prefix it is authorized to see; {@code storage} never derives or checks tenant scope).
   *
   * @param prefix the server-generated prefix; never blank or oversized
   * @param cursor the opaque cursor; {@code null} for the first page
   * @param limit the page size (1–100); the implementation clamps or rejects out-of-range values
   * @return the page (ascending keys, {@code nextCursor} non-null iff {@code hasMore})
   * @throws ObjectStorageException with {@code INVALID_KEY} (blank/out-of-contract prefix), {@code
   *     INVALID_REQUEST} (out-of-range limit), {@code ACCESS_DENIED}, {@code UNAVAILABLE}, {@code
   *     TIMEOUT} or {@code PERMANENT_FAILURE}
   */
  ObjectListPage list(String prefix, String cursor, int limit) throws ObjectStorageException;

  /**
   * Idempotent delete. Missing object → {@link DeleteResult#ALREADY_ABSENT} (success). Never opens
   * content.
   *
   * @param key the object key
   * @return {@link DeleteResult#DELETED} if the object existed and was removed, otherwise {@link
   *     DeleteResult#ALREADY_ABSENT}
   * @throws ObjectStorageException with {@code INVALID_KEY}, {@code ACCESS_DENIED}, {@code
   *     UNAVAILABLE}, {@code TIMEOUT} or {@code PERMANENT_FAILURE}
   */
  DeleteResult delete(String key) throws ObjectStorageException;
}
