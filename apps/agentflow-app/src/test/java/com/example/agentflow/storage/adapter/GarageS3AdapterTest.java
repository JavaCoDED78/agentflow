package com.example.agentflow.storage.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.storage.CreateObjectRequest;
import com.example.agentflow.storage.DeleteResult;
import com.example.agentflow.storage.ObjectListPage;
import com.example.agentflow.storage.ObjectMetadata;
import com.example.agentflow.storage.ObjectReference;
import com.example.agentflow.storage.ObjectStorageErrorCode;
import com.example.agentflow.storage.ObjectStorageException;
import com.example.agentflow.storage.OpenedObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AccessDeniedException;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Unit tests for the {@code S07-06C/D/E} Garage adapter — the put path (the bounded chunked reader,
 * the declared length/checksum verification, the metadata pass-through), the get path
 * (bounded-memory streaming, lazy body, typed not-found, stream release on success/failure) and the
 * head/list/delete paths (presence without throwing, paginated server-side listing, idempotent
 * delete). DB-free: the shared {@link S3Client} is mocked; the mock reads the request body exactly
 * like the real SDK would (through the body's stream provider), so mid-stream verification failures
 * behave exactly as they do against the live endpoint, and the SDK never retries them.
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

  // ── head / list / delete (S07-06E) ──────────────────────────────────────────────────────

  /** A stored-object response that validates the same way the real Garage response does. */
  private HeadObjectResponse headResponse(long size, String contentType, Instant lastModified) {
    return HeadObjectResponse.builder()
        .contentLength(size)
        .contentType(contentType)
        .lastModified(lastModified)
        .eTag("\"etag-1\"")
        .metadata(Map.of("checksum-sha256", sha256("hello world".getBytes())))
        .build();
  }

  private HeadObjectResponse headResponseMissingChecksum(long size) {
    return HeadObjectResponse.builder()
        .contentLength(size)
        .contentType("application/octet-stream")
        .lastModified(Instant.parse("2026-11-27T12:00:00Z"))
        .eTag("\"etag-1\"")
        .metadata(Map.of())
        .build();
  }

  private static S3Object item(String key, long size) {
    return S3Object.builder().key(key).size(size).eTag("\"etag-1\"").build();
  }

  /**
   * Stub the per-item {@code headObject} for the given key→response map. Any key not present in the
   * map throws {@link NoSuchKeyException}, exactly like the real client would for a missing object.
   */
  private void stubHeads(Map<String, HeadObjectResponse> responses) {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenAnswer(
            inv -> {
              HeadObjectRequest request = inv.getArgument(0);
              HeadObjectResponse response = responses.get(request.key());
              if (response != null) {
                return response;
              }
              throw NoSuchKeyException.builder().build();
            });
  }

  @Test
  void head_returns_metadata_for_a_present_object() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(
            headResponse(64L, "application/octet-stream", Instant.parse("2026-11-27T12:00:00Z")));

    Optional<ObjectMetadata> metadata = adapter.head("tenant/doc/file.bin");

    assertThat(metadata).isPresent();
    ObjectMetadata value = metadata.orElseThrow();
    assertThat(value.bucket()).isEqualTo(BUCKET);
    assertThat(value.key()).isEqualTo("tenant/doc/file.bin");
    assertThat(value.contentType()).isEqualTo("application/octet-stream");
    assertThat(value.size()).isEqualTo(64);
    assertThat(value.sha256()).isEqualTo(sha256("hello world".getBytes()));
    assertThat(value.lastModified()).isEqualTo(Instant.parse("2026-11-27T12:00:00Z"));
    assertThat(value.etag()).isEqualTo("\"etag-1\"");
  }

  @Test
  void head_returns_empty_for_a_missing_key_without_throwing() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().build());

    assertThat(adapter.head("tenant/doc/missing.bin")).isEmpty();
  }

  @Test
  void head_rejects_a_blank_key_before_hitting_the_provider() {
    assertThatThrownBy(() -> adapter.head("   "))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
    verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
  }

  @Test
  void head_rejects_a_traversal_key_before_hitting_the_provider() {
    assertThatThrownBy(() -> adapter.head("../evil.bin"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
    verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
  }

  @Test
  void head_maps_access_denied_to_access_denied() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(AccessDeniedException.builder().statusCode(403).build());

    assertThatThrownBy(() -> adapter.head("tenant/doc/secret.bin"))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.ACCESS_DENIED));
  }

  @Test
  void head_maps_a_server_error_to_unavailable() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(AwsServiceException.builder().statusCode(503).build());

    assertThatThrownBy(() -> adapter.head("tenant/doc/file.bin"))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.UNAVAILABLE));
  }

  @Test
  void head_rejects_a_missing_checksum_metadata_and_never_reports_a_fabricated_digest() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponseMissingChecksum(64L));

    assertThatThrownBy(() -> adapter.head("tenant/doc/file.bin"))
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.code()).isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE);
              assertThat(ise.getMessage()).contains("checksum");
            });
  }

  @Test
  void head_rejects_a_response_missing_the_content_length() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(
            HeadObjectResponse.builder()
                .contentType("application/octet-stream")
                .lastModified(Instant.parse("2026-11-27T12:00:00Z"))
                .eTag("\"etag-1\"")
                .metadata(Map.of("checksum-sha256", sha256("hello world".getBytes())))
                .build());

    assertThatThrownBy(() -> adapter.head("tenant/doc/file.bin"))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE));
  }

  @Test
  void head_rejects_a_response_missing_the_etag() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(
            HeadObjectResponse.builder()
                .contentLength(64L)
                .contentType("application/octet-stream")
                .lastModified(Instant.parse("2026-11-27T12:00:00Z"))
                .metadata(Map.of("checksum-sha256", sha256("hello world".getBytes())))
                .build());

    assertThatThrownBy(() -> adapter.head("tenant/doc/file.bin"))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE));
  }

  @Test
  void head_rejects_a_response_missing_last_modified() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(
            HeadObjectResponse.builder()
                .contentLength(64L)
                .contentType("application/octet-stream")
                .eTag("\"etag-1\"")
                .metadata(Map.of("checksum-sha256", sha256("hello world".getBytes())))
                .build());

    assertThatThrownBy(() -> adapter.head("tenant/doc/file.bin"))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE));
  }

  @Test
  void head_does_not_leak_provider_internals_or_the_object_key_in_error_messages() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(
            new RuntimeException("secret internal: http://169.254.169.254/latest/meta-data"));

    assertThatThrownBy(() -> adapter.head("tenant/doc/file.bin"))
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.getMessage())
                  .doesNotContain("169.254.169.254")
                  .doesNotContain("meta-data")
                  .doesNotContain("tenant/doc");
            });
  }

  @Test
  void head_on_an_empty_object_yields_a_zero_size_metadata_anchored_to_the_stored_digest() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(
            HeadObjectResponse.builder()
                .contentLength(0L)
                .contentType("application/octet-stream")
                .lastModified(Instant.parse("2026-11-27T12:00:00Z"))
                .eTag("\"etag-1\"")
                .metadata(Map.of("checksum-sha256", sha256(new byte[0])))
                .build());

    ObjectMetadata metadata = adapter.head("tenant/doc/empty.bin").orElseThrow();

    assertThat(metadata.size()).isZero();
    assertThat(metadata.sha256()).isEqualTo(sha256(new byte[0]));
  }

  @Test
  void head_on_an_object_written_without_a_declared_checksum_is_refused() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponseMissingChecksum(64L));

    assertThatThrownBy(() -> adapter.head("tenant/doc/file.bin"))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE));
  }

  @Test
  void list_returns_a_page_with_typed_references_and_a_cursor_for_a_truncated_page() {
    ListObjectsV2Response page =
        ListObjectsV2Response.builder()
            .contents(
                List.of(item("checkpoints/run-1/0.bin", 10L), item("checkpoints/run-1/1.bin", 20L)))
            .isTruncated(true)
            .nextContinuationToken("cursor-2")
            .build();
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page);
    stubHeads(
        Map.of(
            "checkpoints/run-1/0.bin",
            headResponse(10L, "application/octet-stream", Instant.parse("2026-11-27T12:00:00Z")),
            "checkpoints/run-1/1.bin",
            headResponse(20L, "application/octet-stream", Instant.parse("2026-11-27T12:00:01Z"))));

    ObjectListPage result = adapter.list("checkpoints/run-1/", null, 10);

    assertThat(result.items()).hasSize(2);
    assertThat(result.items().get(0).key()).isEqualTo("checkpoints/run-1/0.bin");
    assertThat(result.items().get(0).size()).isEqualTo(10);
    assertThat(result.items().get(1).key()).isEqualTo("checkpoints/run-1/1.bin");
    assertThat(result.items().get(1).size()).isEqualTo(20);
    assertThat(result.items().get(0).sha256()).isEqualTo(sha256("hello world".getBytes()));
    assertThat(result.items().get(0).contentType()).isEqualTo("application/octet-stream");
    assertThat(result.hasMore()).isTrue();
    assertThat(result.nextCursor()).isEqualTo("cursor-2");

    ArgumentCaptor<ListObjectsV2Request> captor =
        ArgumentCaptor.forClass(ListObjectsV2Request.class);
    verify(s3Client).listObjectsV2(captor.capture());
    assertThat(captor.getValue().prefix()).isEqualTo("checkpoints/run-1/");
    assertThat(captor.getValue().maxKeys()).isEqualTo(10);
    assertThat(captor.getValue().continuationToken()).isNull();
  }

  @Test
  void list_passes_the_continuation_token_through_to_the_provider() {
    ListObjectsV2Response page =
        ListObjectsV2Response.builder()
            .contents(List.of(item("checkpoints/run-1/0.bin", 10L)))
            .isTruncated(false)
            .build();
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page);
    stubHeads(
        Map.of(
            "checkpoints/run-1/0.bin",
            headResponse(10L, "application/octet-stream", Instant.now())));

    adapter.list("checkpoints/run-1/", "cursor-1", 5);

    ArgumentCaptor<ListObjectsV2Request> captor =
        ArgumentCaptor.forClass(ListObjectsV2Request.class);
    verify(s3Client).listObjectsV2(captor.capture());
    assertThat(captor.getValue().continuationToken()).isEqualTo("cursor-1");
  }

  @Test
  void list_returns_an_empty_page_for_a_missing_prefix_without_opening_content() {
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenReturn(ListObjectsV2Response.builder().contents(List.of()).isTruncated(false).build());

    ObjectListPage result = adapter.list("checkpoints/does-not-exist/", null, 10);

    assertThat(result.items()).isEmpty();
    assertThat(result.hasMore()).isFalse();
    assertThat(result.nextCursor()).isNull();
    verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
  }

  @Test
  void list_rejects_a_blank_prefix_before_hitting_the_provider() {
    assertThatThrownBy(() -> adapter.list("   ", null, 10))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
    verify(s3Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
  }

  @Test
  void list_rejects_a_traversal_prefix_before_hitting_the_provider() {
    assertThatThrownBy(() -> adapter.list("../evil/", null, 10))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
    verify(s3Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
  }

  @Test
  void list_rejects_a_blank_cursor_before_hitting_the_provider() {
    assertThatThrownBy(() -> adapter.list("checkpoints/run-1/", "   ", 10))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
    verify(s3Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
  }

  @Test
  void list_rejects_a_zero_limit_before_hitting_the_provider() {
    assertThatThrownBy(() -> adapter.list("checkpoints/run-1/", null, 0))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
    verify(s3Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
  }

  @Test
  void list_rejects_a_limit_above_the_max_before_hitting_the_provider() {
    assertThatThrownBy(() -> adapter.list("checkpoints/run-1/", null, 101))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
    verify(s3Client, never()).listObjectsV2(any(ListObjectsV2Request.class));
  }

  @Test
  void list_rejects_an_unordered_page_as_a_structural_provider_fault() {
    ListObjectsV2Response page =
        ListObjectsV2Response.builder()
            .contents(
                List.of(item("checkpoints/run-1/b.bin", 10L), item("checkpoints/run-1/a.bin", 20L)))
            .isTruncated(false)
            .build();
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page);

    assertThatThrownBy(() -> adapter.list("checkpoints/run-1/", null, 10))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE));
  }

  @Test
  void list_rejects_a_truncated_page_that_carries_no_continuation_token() {
    ListObjectsV2Response page =
        ListObjectsV2Response.builder()
            .contents(List.of(item("checkpoints/run-1/0.bin", 10L)))
            .isTruncated(true)
            .build();
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page);

    assertThatThrownBy(() -> adapter.list("checkpoints/run-1/", null, 10))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE));
  }

  @Test
  void list_rejects_an_item_missing_a_key() {
    ListObjectsV2Response page =
        ListObjectsV2Response.builder()
            .contents(List.of(S3Object.builder().size(10L).build()))
            .isTruncated(false)
            .build();
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page);

    assertThatThrownBy(() -> adapter.list("checkpoints/run-1/", null, 10))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE));
  }

  @Test
  void list_fails_closed_when_an_item_cannot_be_headed() {
    ListObjectsV2Response page =
        ListObjectsV2Response.builder()
            .contents(List.of(item("checkpoints/run-1/0.bin", 10L)))
            .isTruncated(false)
            .build();
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page);
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().build());

    assertThatThrownBy(() -> adapter.list("checkpoints/run-1/", null, 10))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE));
  }

  @Test
  void list_maps_a_provider_failure_to_the_typed_taxonomy() {
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenThrow(AccessDeniedException.builder().statusCode(403).build());

    assertThatThrownBy(() -> adapter.list("checkpoints/run-1/", null, 10))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.ACCESS_DENIED));
  }

  @Test
  void list_does_not_leak_provider_internals_or_the_object_key_in_error_messages() {
    when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenThrow(
            new RuntimeException("secret internal: http://169.254.169.254/latest/meta-data"));

    assertThatThrownBy(() -> adapter.list("checkpoints/run-1/", null, 10))
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.getMessage())
                  .doesNotContain("169.254.169.254")
                  .doesNotContain("meta-data")
                  .doesNotContain("checkpoints/run-1");
            });
  }

  @Test
  void delete_returns_deleted_for_a_present_object() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponse(64L, "application/octet-stream", Instant.now()));

    DeleteResult result = adapter.delete("tenant/doc/file.bin");

    assertThat(result).isEqualTo(DeleteResult.DELETED);
    ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3Client).deleteObject(captor.capture());
    assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(captor.getValue().key()).isEqualTo("tenant/doc/file.bin");
  }

  @Test
  void repeat_delete_returns_already_absent_on_the_second_call() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponse(64L, "application/octet-stream", Instant.now()))
        .thenThrow(NoSuchKeyException.builder().build());

    assertThat(adapter.delete("tenant/doc/file.bin")).isEqualTo(DeleteResult.DELETED);
    assertThat(adapter.delete("tenant/doc/file.bin")).isEqualTo(DeleteResult.ALREADY_ABSENT);
  }

  @Test
  void delete_returns_already_absent_for_a_missing_object_without_deleting() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().build());

    assertThat(adapter.delete("tenant/doc/missing.bin")).isEqualTo(DeleteResult.ALREADY_ABSENT);
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void delete_rejects_a_blank_key_before_hitting_the_provider() {
    assertThatThrownBy(() -> adapter.delete("   "))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
    verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void delete_rejects_a_traversal_key_before_hitting_the_provider() {
    assertThatThrownBy(() -> adapter.delete("../evil.bin"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void delete_maps_access_denied_to_access_denied() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(AccessDeniedException.builder().statusCode(403).build());

    assertThatThrownBy(() -> adapter.delete("tenant/doc/secret.bin"))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.ACCESS_DENIED));
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void delete_surfaces_a_provider_failure_during_the_delete() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponse(64L, "application/octet-stream", Instant.now()));
    when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
        .thenThrow(AwsServiceException.builder().statusCode(503).build());

    assertThatThrownBy(() -> adapter.delete("tenant/doc/file.bin"))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.UNAVAILABLE));
  }

  @Test
  void delete_works_on_an_object_without_a_stored_checksum() {
    // The presence check must not require the stored checksum anchor — delete is an idempotent
    // delete, never a metadata report.
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(headResponseMissingChecksum(64L));

    assertThat(adapter.delete("tenant/doc/no-checksum.bin")).isEqualTo(DeleteResult.DELETED);
    verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void delete_does_not_leak_provider_internals_or_the_object_key_in_error_messages() {
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(
            new RuntimeException("secret internal: http://169.254.169.254/latest/meta-data"));

    assertThatThrownBy(() -> adapter.delete("tenant/doc/file.bin"))
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.getMessage())
                  .doesNotContain("169.254.169.254")
                  .doesNotContain("meta-data")
                  .doesNotContain("tenant/doc");
            });
  }

  @Test
  void the_adapter_rejects_a_blank_bucket_at_construction_time() {
    assertThatThrownBy(() -> new GarageS3Adapter(s3Client, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(e -> assertThat(e.getMessage()).contains("agentflow.storage.bucket"));
  }

  // ── get (S07-06D) — streaming read ────────────────────────────────────────────

  /** A lazily-read stream that tracks per-read size and close, never materializing the body. */
  private static final class LazyBody extends InputStream {

    private final byte[] seed;
    private final long totalBytes;
    private long offset;
    private boolean closed;
    int maxReadReturned;

    LazyBody(byte[] seed, long totalBytes) {
      this.seed = seed;
      this.totalBytes = totalBytes;
    }

    @Override
    public int read(byte[] buffer, int off, int length) throws IOException {
      if (closed) {
        throw new IOException("stream already closed");
      }
      if (offset >= totalBytes) {
        return -1;
      }
      int toRead = (int) Math.min(length, totalBytes - offset);
      for (int i = 0; i < toRead; i++) {
        buffer[off + i] = (byte) (seed[i % seed.length] ^ (int) (offset + i));
      }
      offset += toRead;
      maxReadReturned = Math.max(maxReadReturned, toRead);
      return toRead;
    }

    @Override
    public int read() {
      try {
        return read(new byte[1], 0, 1);
      } catch (IOException exception) {
        throw new java.io.UncheckedIOException(exception);
      }
    }

    @Override
    public void close() {
      this.closed = true;
    }

    boolean isClosed() {
      return closed;
    }

    long offset() {
      return offset;
    }
  }

  private GetObjectResponse responseOf(long size, String contentType, Instant lastModified) {
    return GetObjectResponse.builder()
        .contentLength(size)
        .contentType(contentType)
        .lastModified(lastModified)
        .eTag("\"etag-1\"")
        .metadata(Map.of("checksum-sha256", sha256("hello world".getBytes())))
        .build();
  }

  private ResponseInputStream<GetObjectResponse> responseBody(
      GetObjectResponse response, LazyBody body) {
    return new ResponseInputStream<>(response, body);
  }

  private static byte[] readAll(InputStream in) throws IOException {
    byte[] buffer = new byte[64 * 1024];
    var out = new java.io.ByteArrayOutputStream();
    int read;
    while ((read = in.read(buffer)) != -1) {
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  @Test
  void get_returns_metadata_built_from_the_provider_response_and_the_bytes_lazily_when_requested()
      throws Exception {
    LazyBody body = new LazyBody(new byte[] {1}, 64);
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(
            inv ->
                responseBody(
                    responseOf(
                        64, "application/octet-stream", Instant.parse("2026-11-27T12:00:00Z")),
                    body));

    try (OpenedObject opened = adapter.get("tenant/doc/file.bin")) {
      assertThat(opened.metadata().key()).isEqualTo("tenant/doc/file.bin");
      assertThat(opened.metadata().bucket()).isEqualTo(BUCKET);
      assertThat(opened.metadata().contentType()).isEqualTo("application/octet-stream");
      assertThat(opened.metadata().size()).isEqualTo(64);
      assertThat(opened.metadata().lastModified()).isEqualTo(Instant.parse("2026-11-27T12:00:00Z"));
      assertThat(opened.metadata().etag()).isEqualTo("\"etag-1\"");
      assertThat(opened.metadata().sha256()).isEqualTo(sha256("hello world".getBytes()));
      // the body must not be consumed before the caller asks for it
      assertThat(body.offset()).isZero();

      byte[] bytes = readAll(opened.stream());
      assertThat(bytes).hasSize(64);
      assertThat(body.offset()).isEqualTo(64);
    }
  }

  @Test
  void get_releases_the_underlying_stream_when_the_caller_closes_the_opened_object()
      throws Exception {
    LazyBody body = new LazyBody(new byte[] {7}, 32);
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(
            inv -> responseBody(responseOf(32, "application/octet-stream", Instant.now()), body));

    try (OpenedObject opened = adapter.get("tenant/doc/file.bin")) {
      readAll(opened.stream());
    }
    assertThat(body.isClosed()).isTrue();
  }

  @Test
  void get_reads_a_large_object_in_bounded_chunks_without_loading_the_full_body() throws Exception {
    // 4 MiB — far beyond anything a single read would materialize; the mock lazily yields bytes.
    long size = 4L * 1024 * 1024;
    LazyBody body = new LazyBody(new byte[] {9}, size);
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(
            inv -> responseBody(responseOf(size, "application/octet-stream", Instant.now()), body));

    try (OpenedObject opened = adapter.get("tenant/doc/big.bin")) {
      assertThat(opened.metadata().size()).isEqualTo(size);
      byte[] buffer = new byte[64 * 1024];
      long total = 0, maxRead = 0;
      int read;
      while ((read = opened.stream().read(buffer)) != -1) {
        total += read;
        maxRead = Math.max(maxRead, read);
      }
      assertThat(total).isEqualTo(size);
      assertThat(maxRead).isLessThanOrEqualTo(64 * 1024L);
      assertThat(body.maxReadReturned).isLessThanOrEqualTo(64 * 1024);
    }
  }

  @Test
  void get_on_an_empty_object_yields_no_bytes_and_keeps_the_declared_zero_size() throws Exception {
    LazyBody body = new LazyBody(new byte[] {1}, 0);
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(
            inv -> responseBody(responseOf(0, "application/octet-stream", Instant.now()), body));

    try (OpenedObject opened = adapter.get("tenant/doc/empty.bin")) {
      assertThat(opened.metadata().size()).isZero();
      assertThat(opened.stream().read()).isEqualTo(-1);
    }
  }

  @Test
  void get_rejects_a_blank_key_before_hitting_the_provider() {
    assertThatThrownBy(() -> adapter.get("   "))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
    verify(s3Client, never()).getObject(any(GetObjectRequest.class));
  }

  @Test
  void get_rejects_a_traversal_key_before_hitting_the_provider() {
    assertThatThrownBy(() -> adapter.get("../evil.bin"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
    verify(s3Client, never()).getObject(any(GetObjectRequest.class));
  }

  @Test
  void get_maps_a_missing_key_to_not_found_without_leaking_provider_detail() {
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().build());

    assertThatThrownBy(() -> adapter.get("tenant/doc/missing.bin"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.code()).isEqualTo(ObjectStorageErrorCode.NOT_FOUND);
              assertThat(ise.getMessage()).doesNotContain("http");
              assertThat(ise.getMessage()).doesNotContain("tenant/doc");
            });
  }

  @Test
  void get_maps_an_access_denied_failure_to_access_denied() {
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenThrow(AccessDeniedException.builder().statusCode(403).build());

    assertThatThrownBy(() -> adapter.get("tenant/doc/secret.bin"))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.ACCESS_DENIED));
  }

  @Test
  void get_maps_a_server_error_to_unavailable_with_a_safe_message() {
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenThrow(AwsServiceException.builder().statusCode(503).build());

    assertThatThrownBy(() -> adapter.get("tenant/doc/file.bin"))
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.code()).isEqualTo(ObjectStorageErrorCode.UNAVAILABLE);
              assertThat(ise.getMessage()).isNotBlank();
            });
  }

  @Test
  void get_maps_a_timeout_status_to_timeout() {
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenThrow(AwsServiceException.builder().statusCode(408).build());

    assertThatThrownBy(() -> adapter.get("tenant/doc/file.bin"))
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.TIMEOUT));
  }

  @Test
  void get_releases_the_stream_and_throws_safe_typed_error_when_the_response_metadata_is_unusable()
      throws Exception {
    // missing eTag — metadata construction fails after the stream was already opened
    GetObjectResponse broken =
        GetObjectResponse.builder()
            .contentLength(16L)
            .contentType("application/octet-stream")
            .lastModified(Instant.parse("2026-11-27T12:00:00Z"))
            .metadata(Map.of("checksum-sha256", sha256("hello world".getBytes())))
            .build();
    LazyBody body = new LazyBody(new byte[] {4}, 16);
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(inv -> responseBody(broken, body));

    assertThatThrownBy(() -> adapter.get("tenant/doc/file.bin"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE));
    assertThat(body.isClosed()).isTrue();
  }

  @Test
  void get_rejects_a_response_missing_the_checksum_metadata_and_releases_the_stream()
      throws Exception {
    GetObjectResponse noChecksum =
        responseOf(64, "application/octet-stream", Instant.parse("2026-11-27T12:00:00Z"))
            .toBuilder()
            .metadata(Map.of())
            .build();
    LazyBody body = new LazyBody(new byte[] {2}, 64);
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(inv -> responseBody(noChecksum, body));

    assertThatThrownBy(() -> adapter.get("tenant/doc/file.bin"))
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.code()).isEqualTo(ObjectStorageErrorCode.PERMANENT_FAILURE);
              assertThat(ise.getMessage()).contains("checksum");
            });
    assertThat(body.isClosed()).isTrue();
  }

  @Test
  void get_does_not_leak_provider_internals_or_the_object_bytes_in_error_messages() {
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenThrow(
            new RuntimeException("secret internal: http://169.254.169.254/latest/meta-data"));

    assertThatThrownBy(() -> adapter.get("tenant/doc/file.bin"))
        .satisfies(
            e -> {
              ObjectStorageException ise = (ObjectStorageException) e;
              assertThat(ise.getMessage())
                  .doesNotContain("169.254.169.254")
                  .doesNotContain("meta-data")
                  .doesNotContain("tenant/doc");
            });
  }
}
