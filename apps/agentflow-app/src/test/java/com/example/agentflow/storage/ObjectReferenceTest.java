package com.example.agentflow.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Contract unit tests for the {@link ObjectReference} persistable handle (S07-06B, contract §2.1
 * and §9 case 1). Required fields, invalid keys, and the empty-object edge case.
 */
class ObjectReferenceTest {

  private static final String EMPTY_SHA256 =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @Test
  void a_populated_reference_is_kept() {
    ObjectReference ref =
        new ObjectReference("agentflow", "documents/t1/d1.pdf", "application/pdf", 42L, validHex());

    assertThat(ref.bucket()).isEqualTo("agentflow");
    assertThat(ref.key()).isEqualTo("documents/t1/d1.pdf");
    assertThat(ref.contentType()).isEqualTo("application/pdf");
    assertThat(ref.size()).isEqualTo(42L);
    assertThat(ref.sha256()).isEqualTo(validHex());
  }

  @Test
  void the_empty_object_reference_carries_the_empty_string_sha256() {
    ObjectReference ref =
        new ObjectReference("agentflow", "documents/t1/empty", "application/pdf", 0L, EMPTY_SHA256);

    assertThat(ref.size()).isZero();
    assertThat(ref.sha256()).isEqualTo(EMPTY_SHA256);
  }

  @Test
  void a_null_bucket_is_a_contract_failure() {
    assertThatThrownBy(() -> new ObjectReference(null, "key", "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_blank_bucket_is_a_contract_failure() {
    assertThatThrownBy(() -> new ObjectReference("  ", "key", "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_negative_size_is_a_contract_failure() {
    assertThatThrownBy(
            () -> new ObjectReference("agentflow", "key", "application/pdf", -1L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_blank_content_type_is_a_contract_failure() {
    assertThatThrownBy(() -> new ObjectReference("agentflow", "key", "  ", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_non_hex_sha256_is_a_contract_failure() {
    assertThatThrownBy(
            () -> new ObjectReference("agentflow", "key", "application/pdf", 0L, "not-hex"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void an_uppercase_hex_sha256_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new ObjectReference(
                    "agentflow", "key", "application/pdf", 0L, EMPTY_SHA256.toUpperCase()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_null_key_is_a_contract_failure() {
    assertThatThrownBy(
            () -> new ObjectReference("agentflow", null, "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void a_blank_key_is_a_contract_failure() {
    assertThatThrownBy(
            () -> new ObjectReference("agentflow", "  ", "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void an_oversized_key_is_a_contract_failure() {
    String oversized = "k".repeat(1025);
    assertThatThrownBy(
            () -> new ObjectReference("agentflow", oversized, "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void a_key_at_the_boundary_is_kept() {
    String key = "k".repeat(1024);
    ObjectReference ref = new ObjectReference("agentflow", key, "application/pdf", 0L, validHex());
    assertThat(ref.key()).hasSize(1024);
  }

  @Test
  void a_key_with_a_control_character_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new ObjectReference(
                    "agentflow", "key\u0001with-control", "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void a_key_with_a_nul_character_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new ObjectReference(
                    "agentflow", "key\u0000with-nul", "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void a_key_with_a_cr_character_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new ObjectReference("agentflow", "key\rwith-cr", "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void a_key_with_a_lf_character_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new ObjectReference("agentflow", "key\nwith-lf", "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void a_key_with_a_dot_dot_path_segment_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new ObjectReference(
                    "agentflow", "documents/../bad", "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void a_key_with_a_trailing_dot_dot_segment_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new ObjectReference(
                    "agentflow", "documents/ok/..", "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void a_key_with_an_embedded_dot_dot_segment_is_a_contract_failure() {
    assertThatThrownBy(
            () -> new ObjectReference("agentflow", "a/b/../c", "application/pdf", 0L, validHex()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void a_key_with_sibling_segments_is_kept() {
    ObjectReference ref =
        new ObjectReference(
            "agentflow", "documents/ok/nested/path", "application/pdf", 1L, validHex());
    assertThat(ref.key()).isEqualTo("documents/ok/nested/path");
  }

  private static String validHex() {
    return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  }
}
