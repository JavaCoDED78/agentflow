package com.example.agentflow.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Contract unit tests for the {@link OpenedObject} stream ownership (S07-06B, contract §3 and §9
 * case 9 — the stream-lifecycle rules).
 */
class OpenedObjectTest {

  private static final String VALID_SHA256 =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  private static ObjectMetadata metadata() {
    return new ObjectMetadata(
        "agentflow",
        "key",
        "application/pdf",
        0L,
        VALID_SHA256,
        Instant.parse("2026-11-27T10:15:30.000Z"),
        "etag-1");
  }

  @Test
  void an_opened_object_keeps_its_metadata_and_stream() {
    InputStream in = new ByteArrayInputStream(new byte[] {1, 2, 3});
    OpenedObject opened = new OpenedObject(metadata(), in);

    assertThat(opened.metadata().key()).isEqualTo("key");
    assertThat(opened.stream()).isSameAs(in);
  }

  @Test
  void a_null_metadata_is_a_contract_failure() {
    assertThatThrownBy(() -> new OpenedObject(null, new ByteArrayInputStream(new byte[0])))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_null_stream_is_a_contract_failure() {
    assertThatThrownBy(() -> new OpenedObject(metadata(), null))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void close_releases_the_stream_once() {
    TrackingStream in = new TrackingStream();
    OpenedObject opened = new OpenedObject(metadata(), in);

    opened.close();

    assertThat(in.wasClosed()).isTrue();
  }

  @Test
  void close_is_idempotent() {
    TrackingStream in = new TrackingStream();
    OpenedObject opened = new OpenedObject(metadata(), in);

    opened.close();
    opened.close();

    assertThat(in.closeCalls()).isEqualTo(1);
  }

  @Test
  void reading_after_close_is_an_illegal_state() {
    OpenedObject opened = new OpenedObject(metadata(), new ByteArrayInputStream(new byte[0]));
    opened.close();

    assertThatThrownBy(opened::stream)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void metadata_is_readable_after_close() {
    OpenedObject opened = new OpenedObject(metadata(), new ByteArrayInputStream(new byte[0]));
    opened.close();

    assertThat(opened.metadata().size()).isZero();
  }

  @Test
  void a_close_that_fails_propagates_an_unchecked_failure() {
    FaultyCloseStream in = new FaultyCloseStream();
    OpenedObject opened = new OpenedObject(metadata(), in);

    assertThatThrownBy(opened::close).isInstanceOf(java.io.UncheckedIOException.class);
  }

  /** A stream that counts close calls and never throws. */
  private static final class TrackingStream extends InputStream {
    private int closeCalls;

    @Override
    public int read() {
      return -1;
    }

    @Override
    public void close() throws IOException {
      closeCalls++;
      super.close();
    }

    boolean wasClosed() {
      return closeCalls > 0;
    }

    int closeCalls() {
      return closeCalls;
    }
  }

  /** A stream whose close fails — a provider/release failure must never be swallowed. */
  private static final class FaultyCloseStream extends InputStream {
    @Override
    public int read() {
      return -1;
    }

    @Override
    public void close() throws IOException {
      throw new IOException("underlying release failed");
    }
  }
}
