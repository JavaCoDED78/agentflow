package com.example.agentflow.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Contract unit tests for the {@link ObjectMetadata} head result (S07-06B, contract §2.2 and §5.3).
 */
class ObjectMetadataTest {

  private static final String VALID_SHA256 =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final Instant INSTANT = Instant.parse("2026-11-27T10:15:30.000Z");

  @Test
  void a_populated_metadata_is_kept() {
    ObjectMetadata metadata =
        new ObjectMetadata(
            "agentflow",
            "documents/t1/d1.pdf",
            "application/pdf",
            42L,
            VALID_SHA256,
            INSTANT,
            "etag-1");

    assertThat(metadata.bucket()).isEqualTo("agentflow");
    assertThat(metadata.key()).isEqualTo("documents/t1/d1.pdf");
    assertThat(metadata.contentType()).isEqualTo("application/pdf");
    assertThat(metadata.size()).isEqualTo(42L);
    assertThat(metadata.sha256()).isEqualTo(VALID_SHA256);
    assertThat(metadata.lastModified()).isEqualTo(INSTANT);
    assertThat(metadata.etag()).isEqualTo("etag-1");
  }

  @Test
  void an_empty_object_metadata_is_kept() {
    ObjectMetadata metadata =
        new ObjectMetadata(
            "agentflow",
            "documents/t1/empty",
            "application/pdf",
            0L,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            INSTANT,
            "etag-empty");

    assertThat(metadata.size()).isZero();
  }

  @Test
  void a_null_last_modified_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new ObjectMetadata(
                    "agentflow", "key", "application/pdf", 0L, VALID_SHA256, null, "etag-1"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_null_etag_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new ObjectMetadata(
                    "agentflow", "key", "application/pdf", 0L, VALID_SHA256, INSTANT, null))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_blank_etag_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new ObjectMetadata(
                    "agentflow", "key", "application/pdf", 0L, VALID_SHA256, INSTANT, "  "))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_negative_size_is_a_contract_failure() {
    assertThatThrownBy(
            () ->
                new ObjectMetadata(
                    "agentflow", "key", "application/pdf", -1L, VALID_SHA256, INSTANT, "etag-1"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }
}
