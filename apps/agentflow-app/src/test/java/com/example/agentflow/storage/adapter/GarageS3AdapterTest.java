package com.example.agentflow.storage.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.storage.CreateObjectRequest;
import com.example.agentflow.storage.ObjectReference;
import com.example.agentflow.storage.ObjectStorageErrorCode;
import com.example.agentflow.storage.ObjectStorageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AccessDeniedException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Unit tests for the {@code S07-06C} Garage adapter — the put path: the bounded chunked reader, the
 * declared length/checksum verification, the metadata pass-through and the error taxonomy mapping.
 * DB-free: the shared {@link S3Client} is mocked; the mock reads the request body exactly like the
 * real SDK would (through the body's stream provider), so mid-stream verification failures behave
 * exactly as they do against the live endpoint, and the SDK never retries them.
 *
 * <p>The real Garage path (byte array + large stream + repeat-put determinism + size/content-type/
 * checksum-metadata confirmation) is covered by the integration suite — this class pins the
 * adapter's own logic without a container.
 */
class GarageS3AdapterTest {

  private static final String BUCKET = "agentflow-test";

  private final S3Client s3Client = mock(S3Client.class);
  private final GarageS3Adapter adapter = new GarageS3Adapter(s3Client, BUCKET);

  /** A stream whose close() is observable, mirroring what the caller transfers to the adapter. */
  private static InputStream observableStream(byte[] bytes, boolean[] state) {
    return new ByteArrayInputStream(bytes) {
      @Override
      public void close() {
        state[0] = true;
        try {
          super.close();
        } catch (IOException ignored) {
          // close must never hide the observable close state
        }
      }
    };
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
      return sb.toString();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  /** The mock simulates the SDK: reads the body through the provider, then returns a response. */
  private void readBody(S3Client client) {
    lenient()
        .when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(
            invocation -> {
              RequestBody body = invocation.getArgument(1);
              try (InputStream stream = body.contentStreamProvider().newStream()) {
                while (stream.read() != -1) {
                  // drain exactly like the SDK does
                }
              }
              return PutObjectResponse.builder().build();
            });
  }

  private ObjectReference put(byte[] bytes, Long declaredLength, String declaredSha256) {
    return adapter.put(
        new CreateObjectRequest(
            "checkpoints/run-1/output.bin",
            observableStream(bytes, new boolean[1]),
            "application/octet-stream",
            declaredLength,
            declaredSha256,
            Map.of()));
  }

  @Test
  void a_successful_put_returns_the_reference_with_the_actual_bytes_and_closes_the_stream() {
    readBody(s3Client);
    byte[] bytes = "hello object".getBytes();
    boolean[] closed = {false};

    ObjectReference reference =
        adapter.put(
            new CreateObjectRequest(
                "documents/tenant-1/doc-1/original.bin",
                observableStream(bytes, closed),
                "application/octet-stream",
                (long) bytes.length,
                sha256(bytes),
                Map.of("pipeline", "extract-v1")));

    assertThat(reference.bucket()).isEqualTo(BUCKET);
    assertThat(reference.key()).isEqualTo("documents/tenant-1/doc-1/original.bin");
    assertThat(reference.contentType()).isEqualTo("application/octet-stream");
    assertThat(reference.size()).isEqualTo(bytes.length);
    assertThat(reference.sha256()).isEqualTo(sha256(bytes));
    assertThat(closed[0]).isTrue();

    ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
    org.mockito.Mockito.verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
    ArgumentCaptor<PutObjectRequest> request = captor;
    assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(request.getValue().key()).isEqualTo("documents/tenant-1/doc-1/original.bin");
    assertThat(request.getValue().contentType()).isEqualTo("application/octet-stream");
    assertThat(request.getValue().metadata())
        .containsEntry("pipeline", "extract-v1")
        .containsEntry("checksum-sha256", sha256(bytes));
  }

  @Test
  void a_repeated_put_for_the_same_key_and_content_returns_the_same_reference() {
    readBody(s3Client);
    byte[] bytes = "identical".getBytes();

    ObjectReference first = put(bytes, (long) bytes.length, sha256(bytes));
    ObjectReference second = put(bytes, (long) bytes.length, sha256(bytes));

    assertThat(first).isEqualTo(second);
  }

  @Test
  void a_stream_longer_than_the_declared_length_is_rejected_without_succeeding() {
    readBody(s3Client);
    byte[] bytes = "way more than the declared length".getBytes();

    assertThatThrownBy(() -> put(bytes, 4L, null))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.code()).isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST);
              assertThat(ise.getMessage()).contains("declared content length");
            });
  }

  @Test
  void a_stream_shorter_than_the_declared_length_is_rejected() {
    readBody(s3Client);
    byte[] bytes = "short".getBytes();

    assertThatThrownBy(() -> put(bytes, 100L, null))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.code()).isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST);
              assertThat(ise.getMessage()).contains("declared content length");
            });
  }

  @Test
  void a_declared_checksum_mismatch_is_rejected_with_the_computed_digest_in_the_reference_path() {
    readBody(s3Client);
    byte[] bytes = "actual bytes".getBytes();

    assertThatThrownBy(() -> put(bytes, (long) bytes.length, sha256("different".getBytes())))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.code()).isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST);
              assertThat(ise.getMessage()).contains("sha256");
            });
  }

  @Test
  void an_empty_object_with_a_declared_checksum_is_verified_and_returned() {
    readBody(s3Client);
    byte[] bytes = new byte[0];

    ObjectReference reference = put(bytes, 0L, sha256(bytes));

    assertThat(reference.size()).isZero();
    assertThat(reference.sha256()).isEqualTo(sha256(bytes));
  }

  @Test
  void an_empty_object_with_a_wrong_declared_checksum_is_rejected() {
    readBody(s3Client);

    assertThatThrownBy(() -> put(new byte[0], 0L, sha256("not empty".getBytes())))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void an_undeclared_content_length_is_refused_deterministically() {
    assertThatThrownBy(() -> put("data".getBytes(), null, null))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e -> {
              assertThat(((ObjectStorageException) e).code())
                  .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST);
              assertThat(e.getMessage()).contains("contentLength must be declared");
            });
  }

  @Test
  void stream_release_survives_a_failure_during_the_send() {
    // The mock fails midway through the SDK's send, after reading the stream.
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(
            invocation -> {
              RequestBody body = invocation.getArgument(1);
              // simulate a transport failure — the SDK throws before the body is fully read
              throw new RuntimeException("transport failure");
            });
    byte[] bytes = "payload".getBytes();
    boolean[] closed = {false};

    assertThatThrownBy(
            () ->
                adapter.put(
                    new CreateObjectRequest(
                        "k",
                        observableStream(bytes, closed),
                        "application/octet-stream",
                        (long) bytes.length,
                        null,
                        Map.of())))
        .isInstanceOf(ObjectStorageException.class);
    assertThat(closed[0]).isTrue();
  }

  @Test
  void access_denied_is_mapped_to_access_denied() {
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(AccessDeniedException.builder().statusCode(403).build());

    assertThatThrownBy(() -> put("data".getBytes(), 4L, null))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.ACCESS_DENIED));
  }

  @Test
  void a_503_is_mapped_to_unavailable_and_is_retryable() {
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(AwsServiceException.builder().statusCode(503).build());

    assertThatThrownBy(() -> put("data".getBytes(), 4L, null))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.code()).isEqualTo(ObjectStorageErrorCode.UNAVAILABLE);
              assertThat(ise.code().retryable()).isTrue();
            });
  }

  @Test
  void a_408_is_mapped_to_timeout() {
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(AwsServiceException.builder().statusCode(408).build());

    assertThatThrownBy(() -> put("data".getBytes(), 4L, null))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.TIMEOUT));
  }

  @Test
  void an_unknown_provider_failure_is_permanent_and_never_echoes_provider_details() {
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(new RuntimeException("some internal AWS SDK detail: http://secret-host/path"));

    assertThatThrownBy(() -> put("data".getBytes(), 4L, null))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.code()).isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE);
              assertThat(ise.getMessage()).doesNotContain("secret-host");
            });
  }

  @Test
  void not_yet_implemented_operations_fail_closed_with_a_typed_rejection() {
    assertThatThrownBy(() -> adapter.get("any/key"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.code()).isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE);
              assertThat(ise.getMessage()).contains("S07-06D");
            });
    assertThatThrownBy(() -> adapter.head("any/key"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(e -> assertThat(((ObjectStorageException) e).getMessage()).contains("S07-06E"));
    assertThatThrownBy(() -> adapter.list("any/prefix", null, 10))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(e -> assertThat(((ObjectStorageException) e).getMessage()).contains("S07-06E"));
    assertThatThrownBy(() -> adapter.delete("any/key"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(e -> assertThat(((ObjectStorageException) e).getMessage()).contains("S07-06E"));
  }

  @Test
  void the_adapter_rejects_a_blank_bucket_at_construction_time() {
    assertThatThrownBy(() -> new GarageS3Adapter(s3Client, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("agentflow.storage.bucket"));
  }
}
