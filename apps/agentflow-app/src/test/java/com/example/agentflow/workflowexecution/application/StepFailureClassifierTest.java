package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.common.ErrorClass;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * Unit tests for the step retry classifier (S07-11). Each case asserts the expected {@link
 * ErrorClass} and its retry decision: the three transient classes ({@link
 * ErrorClass#TRANSIENT_INFRA} , {@link ErrorClass#PROVIDER_RATE_LIMIT}, {@link
 * ErrorClass#PROVIDER_TIMEOUT}) retry, and every other class is permanent (never retried). It also
 * asserts the safe, redacted reason never leaks a secret (invariant 8) and is length-bounded.
 *
 * <p>The DB / connection / provider faults are recognised by their {@code java.sql} / {@code
 * java.net} / {@code java.util.concurrent} type or by a characteristic message; framework-auth
 * faults (Spring Security) are recognised by class-name prefix; constraint / duplicate-key faults
 * are permanent. The DB-free test keeps the classification decision here where it is a pure
 * function of the thrown {@link Throwable}.
 */
class StepFailureClassifierTest {

  private final StepFailureClassifier classifier = StepFailureClassifier.defaults();

  // ---------------------------------------------------------------------------
  // Transient faults — retried with bounded backoff (retryable = true)
  // ---------------------------------------------------------------------------

  @Test
  void a_connection_failure_is_transient_infra() {
    StepFailure failure = classifier.classify(new ConnectException("connection refused"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.TRANSIENT_INFRA);
    assertThat(failure.isRetryable()).isTrue();
  }

  @Test
  void a_sql_exception_is_transient_infra() {
    StepFailure failure = classifier.classify(new SQLException("pool exhausted"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.TRANSIENT_INFRA);
    assertThat(failure.isRetryable()).isTrue();
  }

  @Test
  void a_connection_reset_message_is_transient_infra() {
    StepFailure failure = classifier.classify(new RuntimeException("Connection reset by peer"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.TRANSIENT_INFRA);
    assertThat(failure.isRetryable()).isTrue();
  }

  @Test
  void an_unresolved_address_message_is_transient_infra() {
    StepFailure failure = classifier.classify(new RuntimeException("Unresolved address for host"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.TRANSIENT_INFRA);
    assertThat(failure.isRetryable()).isTrue();
  }

  @Test
  void a_provider_rate_limit_message_is_transient() {
    StepFailure failure = classifier.classify(new RuntimeException("HTTP 429 Too Many Requests"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.PROVIDER_RATE_LIMIT);
    assertThat(failure.isRetryable()).isTrue();
  }

  @Test
  void a_throttled_message_is_provider_rate_limit() {
    StepFailure failure =
        classifier.classify(new RuntimeException("rate limit exceeded, throttled"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.PROVIDER_RATE_LIMIT);
  }

  @Test
  void a_timeout_message_is_provider_timeout() {
    StepFailure failure =
        classifier.classify(new RuntimeException("Timed out waiting for the provider"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.PROVIDER_TIMEOUT);
    assertThat(failure.isRetryable()).isTrue();
  }

  @Test
  void a_jdk_timeout_exception_is_provider_timeout() {
    StepFailure failure = classifier.classify(new TimeoutException("timed out"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.PROVIDER_TIMEOUT);
  }

  // ---------------------------------------------------------------------------
  // Permanent faults — terminal, never retried (retryable = false)
  // ---------------------------------------------------------------------------

  @Test
  void a_validation_error_is_permanent() {
    StepFailure failure = classifier.classify(new IllegalArgumentException("bad graph shape"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.VALIDATION);
    assertThat(failure.isRetryable()).isFalse();
  }

  @Test
  void a_null_pointer_is_a_validation_error() {
    StepFailure failure = classifier.classify(new NullPointerException("null config"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.VALIDATION);
    assertThat(failure.isRetryable()).isFalse();
  }

  @Test
  void a_security_exception_is_authorization() {
    StepFailure failure = classifier.classify(new SecurityException("access denied"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.AUTHORIZATION);
    assertThat(failure.isRetryable()).isFalse();
  }

  @Test
  void a_spring_security_authentication_failure_is_authentication() {
    StepFailure failure = classifier.classify(new BadCredentialsException("bad credentials"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.AUTHENTICATION);
    assertThat(failure.isRetryable()).isFalse();
  }

  @Test
  void a_spring_security_access_denied_is_authorization() {
    StepFailure failure = classifier.classify(new AccessDeniedException("forbidden"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.AUTHORIZATION);
    assertThat(failure.isRetryable()).isFalse();
  }

  @Test
  void a_data_integrity_violation_is_conflict() {
    StepFailure failure = classifier.classify(new DataIntegrityViolationException("duplicate"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.CONFLICT);
    assertThat(failure.isRetryable()).isFalse();
  }

  @Test
  void an_integrity_constraint_violation_is_conflict() {
    StepFailure failure =
        classifier.classify(new SQLIntegrityConstraintViolationException("dup key"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.CONFLICT);
    assertThat(failure.isRetryable()).isFalse();
  }

  @Test
  void a_domain_conflict_exception_is_conflict() {
    StepFailure failure = classifier.classify(new ConflictException("lease moved"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.CONFLICT);
    assertThat(failure.isRetryable()).isFalse();
  }

  @Test
  void an_unrecognized_exception_is_internal_and_permanent() {
    StepFailure failure =
        classifier.classify(new RuntimeException("something unexpected happened"));

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.INTERNAL);
    assertThat(failure.isRetryable()).isFalse();
  }

  @Test
  void a_null_fault_classifies_as_internal_and_is_never_retried() {
    StepFailure failure = classifier.classify(null);

    assertThat(failure.errorClass()).isEqualTo(ErrorClass.INTERNAL);
    assertThat(failure.isRetryable()).isFalse();
    assertThat(failure.safeReason()).contains("undetermined");
  }

  // ---------------------------------------------------------------------------
  // The safe reason is redacted and length-bounded (invariant 8)
  // ---------------------------------------------------------------------------

  @Test
  void the_safe_reason_redacts_a_password_in_the_message() {
    StepFailure failure = classifier.classify(new RuntimeException("\"api_key\": \"supersecret\""));

    assertThat(failure.safeReason()).contains("[REDACTED]");
    assertThat(failure.safeReason()).doesNotContain("supersecret");
  }

  @Test
  void the_safe_reason_is_length_bounded() {
    StepFailure failure = classifier.classify(new RuntimeException("x".repeat(5000)));

    assertThat(failure.safeReason().length()).isLessThanOrEqualTo(301); // 300 + the ellipsis char
  }

  @Test
  void the_safe_reason_records_the_fault_type_and_the_class_token() {
    StepFailure failure = classifier.classify(new ConnectException("connection refused"));

    assertThat(failure.safeReason()).contains("ConnectException");
    assertThat(failure.safeReason()).contains("connection refused");
    assertThat(failure.errorClass().errorClass()).isEqualTo("transient_infra");
  }
}
