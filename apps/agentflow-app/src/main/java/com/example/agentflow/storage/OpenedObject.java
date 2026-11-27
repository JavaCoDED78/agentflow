package com.example.agentflow.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@code get} result (S07-06B, contract §2.2 and §3).
 *
 * <p>Carries the {@link ObjectMetadata} (five reference fields plus {@code lastModified} and {@code
 * etag}) and a caller-owned, provider-neutral {@link InputStream}. The caller must close the object
 * (try-with-resources): closing releases the stream, and reading after close is an {@link
 * IllegalStateException}. The adapter guarantees the underlying provider stream is released on
 * success, exception and cancellation.
 *
 * <p>The contract's bounded-memory rule applies: no operation may require the whole object to fit
 * in heap — the consumer reads in chunks and never calls {@code readAllBytes()} on the stream.
 *
 * <p>No provider types cross the contract — the stream is a plain JDK {@link InputStream}, never an
 * AWS SDK type.
 */
public final class OpenedObject implements AutoCloseable {

  private final ObjectMetadata metadata;
  private final InputStream stream;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * @param metadata the object metadata; never {@code null}
   * @param stream the provider-neutral payload stream; never {@code null}
   * @throws ObjectStorageException with {@code INVALID_REQUEST} if either component is {@code null}
   */
  public OpenedObject(ObjectMetadata metadata, InputStream stream) {
    if (metadata == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "metadata must not be null");
    }
    if (stream == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "stream must not be null");
    }
    this.metadata = metadata;
    this.stream = stream;
  }

  /**
   * @return the object metadata. Reading is safe before or after close (it is a plain value).
   */
  public ObjectMetadata metadata() {
    return metadata;
  }

  /**
   * The caller-owned payload stream. Must be closed by the caller (try-with-resources).
   *
   * @return the provider-neutral stream; never {@code null}
   * @throws IllegalStateException if the object has been closed
   */
  public InputStream stream() {
    ensureOpen();
    return stream;
  }

  /**
   * Release the stream, exactly once. Idempotent — closing twice is a no-op, never a failure.
   *
   * @throws UncheckedIOException if the underlying stream's close fails — never a silent leak
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try {
      stream.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("OpenedObject is closed");
    }
  }
}
