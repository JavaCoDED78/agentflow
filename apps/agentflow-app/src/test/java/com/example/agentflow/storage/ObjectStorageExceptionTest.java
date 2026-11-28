package com.example.agentflow.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract unit tests for the {@link ObjectStorageException} taxonomy carrier (S07-06B, contract
 * §2.3). The typed code is the contract's classification surface — the caller classifies by code,
 * never by message.
 */
class ObjectStorageExceptionTest {

  @Test
  void the_exception_carries_its_code_and_message() {
    ObjectStorageException exception =
        new ObjectStorageException(ObjectStorageErrorCode.NOT_FOUND, "object not found");

    assertThat(exception.code()).isEqualTo(ObjectStorageErrorCode.NOT_FOUND);
    assertThat(exception.getMessage()).isEqualTo("object not found");
    assertThat(exception).isInstanceOf(RuntimeException.class);
  }

  @Test
  void the_exception_preserves_the_cause() {
    IllegalArgumentException cause = new IllegalArgumentException("root cause");
    ObjectStorageException exception =
        new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST, "invalid request", cause);

    assertThat(exception.getCause()).isSameAs(cause);
  }

  @Test
  void a_null_code_is_refused() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ObjectStorageException(null, "message"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void the_toString_never_echoes_the_message_or_any_key() {
    ObjectStorageException exception =
        new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_KEY, "key 'documents/tenant-42/secret-doc' is blank");

    assertThat(exception.toString())
        .doesNotContain("documents/tenant-42/secret-doc")
        .contains("INVALID_KEY");
  }

  @Test
  void the_request_id_is_carried_and_a_blank_one_is_treated_as_absent() {
    ObjectStorageException withId =
        new ObjectStorageException(
            ObjectStorageErrorCode.UNAVAILABLE, "unavailable", null, "req-1");
    assertThat(withId.requestId()).contains("req-1");

    ObjectStorageException blank =
        new ObjectStorageException(ObjectStorageErrorCode.UNAVAILABLE, "unavailable", null, "  ");
    assertThat(blank.requestId()).isEmpty();

    ObjectStorageException noId =
        new ObjectStorageException(ObjectStorageErrorCode.UNAVAILABLE, "unavailable");
    assertThat(noId.requestId()).isEmpty();
  }

  @Test
  void the_toString_echoes_the_request_id_when_present_and_never_the_message() {
    ObjectStorageException exception =
        new ObjectStorageException(
            ObjectStorageErrorCode.UNAVAILABLE, "secret message", null, "req-1");

    assertThat(exception.toString()).contains("req-1").doesNotContain("secret message");
  }

  @Test
  void a_request_value_type_failure_carries_the_stable_code() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new CreateObjectRequest(
                    "key",
                    new ByteArrayInputStream(new byte[0]),
                    "application/pdf",
                    -1L,
                    null,
                    Map.of()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void an_invalid_key_on_the_request_carries_the_invalid_key_code() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new CreateObjectRequest(
                    "documents/../bad",
                    new ByteArrayInputStream(new byte[0]),
                    "application/pdf",
                    null,
                    null,
                    Map.of()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_KEY));
  }

  @Test
  void a_null_stream_is_rejected_at_request_construction() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new CreateObjectRequest("key", null, "application/pdf", null, null, Map.of()))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }
}
