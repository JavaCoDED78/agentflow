package com.example.agentflow.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract unit tests for the {@link CreateObjectRequest} put input (S07-06B, contract §2.2, §5.1
 * and §9 cases 1, 6 and 7 — the required/invalid-key/metadata checks).
 */
class CreateObjectRequestTest {

  private static final String VALID_SHA256 =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  private static InputStream stream() {
    return new ByteArrayInputStream(new byte[] {1, 2, 3});
  }

  private static Map<String, String> metadata(String... entries) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) {
      map.put(entries[i], entries[i + 1]);
    }
    return map;
  }

  @Test
  void a_populated_request_is_kept() {
    InputStream in = stream();
    CreateObjectRequest request =
        new CreateObjectRequest(
            "documents/t1/d1.pdf",
            in,
            "application/pdf",
            3L,
            VALID_SHA256,
            metadata("kind", "original"));

    assertThat(request.key()).isEqualTo("documents/t1/d1.pdf");
    assertThat(request.stream()).isSameAs(in);
    assertThat(request.contentType()).isEqualTo("application/pdf");
    assertThat(request.contentLength()).isEqualTo(3L);
    assertThat(request.sha256()).isEqualTo(VALID_SHA256);
    assertThat(request.metadata()).containsEntry("kind", "original");
  }

  @Test
  void a_request_without_declared_length_or_sha256_is_kept() {
    CreateObjectRequest request =
        new CreateObjectRequest("key", stream(), "application/pdf", null, null, Map.of());

    assertThat(request.contentLength()).isNull();
    assertThat(request.sha256()).isNull();
    assertThat(request.metadata()).isEmpty();
  }

  @Test
  void the_metadata_map_is_defensively_copied() {
    Map<String, String> caller = new LinkedHashMap<>();
    caller.put("kind", "original");
    CreateObjectRequest request =
        new CreateObjectRequest("key", stream(), "application/pdf", null, null, caller);
    caller.put("kind", "mutated");
    caller.put("extra", "mutated");

    assertThat(request.metadata()).containsExactly(Map.entry("kind", "original"));
  }

  @Test
  void a_null_stream_is_a_contract_failure() {
    assertThatThrownBy(
            () -> new CreateObjectRequest("key", null, "application/pdf", null, null, Map.of()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_negative_content_length_is_a_contract_failure() {
    assertThatThrownBy(
            () -> new CreateObjectRequest("key", stream(), "application/pdf", -1L, null, Map.of()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_blank_content_type_is_a_contract_failure() {
    assertThatThrownBy(() -> new CreateObjectRequest("key", stream(), "  ", null, null, Map.of()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_malformed_declared_sha256_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new CreateObjectRequest(
                    "key", stream(), "application/pdf", null, "not-a-digest", Map.of()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_blank_declared_sha256_is_a_contract_failure() {
    assertThatThrownBy(
            () -> new CreateObjectRequest("key", stream(), "application/pdf", null, "  ", Map.of()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_null_metadata_is_a_contract_failure() {
    assertThatThrownBy(
            () -> new CreateObjectRequest("key", stream(), "application/pdf", null, null, null))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_null_key_is_a_contract_failure() {
    assertThatThrownBy(
            () -> new CreateObjectRequest(null, stream(), "application/pdf", null, null, Map.of()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void a_key_with_a_dot_dot_segment_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new CreateObjectRequest(
                    "documents/../bad", stream(), "application/pdf", null, null, Map.of()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void metadata_with_too_many_entries_is_a_contract_failure() {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < 11; i++) {
      map.put("key-" + i, "value-" + i);
    }
    assertThatThrownBy(
            () -> new CreateObjectRequest("key", stream(), "application/pdf", null, null, map))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void metadata_with_an_oversized_key_is_a_contract_failure() {
    String key = "k".repeat(129);
    assertThatThrownBy(
            () ->
                new CreateObjectRequest(
                    "key", stream(), "application/pdf", null, null, metadata(key, "v")))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void metadata_with_an_oversized_value_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new CreateObjectRequest(
                    "key",
                    stream(),
                    "application/pdf",
                    null,
                    null,
                    metadata("kind", "v".repeat(257))))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void metadata_with_a_control_character_in_the_value_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new CreateObjectRequest(
                    "key",
                    stream(),
                    "application/pdf",
                    null,
                    null,
                    metadata("kind", "with\u0001control")))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void metadata_with_a_non_matching_key_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new CreateObjectRequest(
                    "key", stream(), "application/pdf", null, null, metadata("bad key!", "v")))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void metadata_with_a_secret_shaped_key_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new CreateObjectRequest(
                    "key", stream(), "application/pdf", null, null, metadata("api-secret", "v")))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void metadata_with_a_secret_shaped_value_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new CreateObjectRequest(
                    "key",
                    stream(),
                    "application/pdf",
                    null,
                    null,
                    metadata("kind", "abc-token-xyz")))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void metadata_with_a_blank_key_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new CreateObjectRequest(
                    "key", stream(), "application/pdf", null, null, metadata(" ", "v")))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void metadata_with_values_but_no_key_uses_the_structural_pattern() {
    CreateObjectRequest request =
        new CreateObjectRequest(
            "key",
            stream(),
            "application/pdf",
            null,
            null,
            metadata("kind.original_v2", "with spaces and dots ok"));
    assertThat(request.metadata()).containsEntry("kind.original_v2", "with spaces and dots ok");
  }

  @Test
  void metadata_allows_at_most_ten_entries_and_exact_boundary_is_kept() {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < 10; i++) {
      map.put("key-" + i, "value-" + i);
    }
    CreateObjectRequest request =
        new CreateObjectRequest("key", stream(), "application/pdf", null, null, map);
    assertThat(request.metadata()).hasSize(10);
  }
}
