package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.common.ErrorClass;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies a step failure into a canonical {@link ErrorClass} and a short, safe (redacted) reason
 * (S07-11, the "retry classifier" named in {@code docs/architecture/error-taxonomy.md}).
 *
 * <p>A step handler runs untrusted content (invariant 7) and can fail for many reasons. Before a
 * failed step is retried or declared terminal, the worker must know <i>which</i> kind of fault it
 * is, so this classifier maps the thrown {@link Throwable} to one {@link ErrorClass} from the
 * shared {@code common} taxonomy (S02-10). The retry decision is then read off that class: {@link
 * ErrorClass#isRetryable()} is {@code true} for the transient classes &mdash; {@link
 * ErrorClass#TRANSIENT_INFRA}, {@link ErrorClass#PROVIDER_RATE_LIMIT}, {@link
 * ErrorClass#PROVIDER_TIMEOUT} &mdash; so the step retries with bounded exponential backoff; every
 * other class is permanent and the step ends {@code FAILED} (or, once its budget is exhausted,
 * {@code FAILED} per {@code S07-10}).
 *
 * <p>The classification mirrors the {@code messaging} inbox policy ({@link
 * com.example.agentflow.messaging.application.InboxErrorPolicy}) &mdash; a malformed / invalid /
 * programming fault is permanent, a briefly unreachable dependency or provider is transient &mdash;
 * extended for the workflow failure surface: DB / pool / connection faults ({@code
 * java.sql.SQLException}, connection resets, Kafka broker errors) and provider timeouts / rate
 * limits, while validation, authentication, authorization and constraint violations never retry.
 *
 * <p>The policy is a pure function of the thrown {@link Throwable} and carries no state; a single
 * stateless instance ({@link #defaults()}) is shared. It is deliberately dependency-light:
 * framework- and broker-specific faults are recognised by class-name prefix ({@code
 * org.apache.kafka.*}, {@code org.springframework.security.*}) rather than by importing those
 * packages, so {@code workflowexecution} never gains a compile edge to Kafka or Security for a
 * classification heuristic. A {@code null} fault (an unexpected {@code throw null}) and any fault
 * that matches no known class classify as {@link ErrorClass#INTERNAL} &mdash; permanent and visible
 * to the operator, never an unbounded retry.
 *
 * <p>The {@link StepFailure#safeReason()} is the fault description redacted through {@link
 * CheckpointRedactor} and length-bounded, so the class never lands a secret, a full prompt or a
 * document in {@code workflow_step_runs.error_summary} or a log (invariant 8).
 */
public class StepFailureClassifier {

  private static final Logger log = LoggerFactory.getLogger(StepFailureClassifier.class);

  /** Upper bound for a persisted / logged failure reason (the column is bounded, invariant 8). */
  private static final int MAX_REASON_LENGTH = 300;

  private static final StepFailureClassifier DEFAULT = new StepFailureClassifier();

  /**
   * @return a shared, stateless default classifier (the stateless {@link #classify} has no instance
   *     state to share)
   */
  public static StepFailureClassifier defaults() {
    return DEFAULT;
  }

  /**
   * Classify a failure thrown while executing a step.
   *
   * @param failure the step execution failure (may be {@code null} if the cause is undetermined)
   * @return the {@link StepFailure}: the canonical {@link ErrorClass} and a redacted, safe reason.
   *     A {@code null} fault classifies as {@link ErrorClass#INTERNAL} (permanent, operator) rather
   *     than retrying unbounded
   */
  public StepFailure classify(Throwable failure) {
    ErrorClass errorClass = (failure == null) ? ErrorClass.INTERNAL : classifyClass(failure);
    String safeReason = safeReason(failure, errorClass);
    if (failure != null) {
      log.debug(
          "step failure classified as {} (retryable={}): {}",
          errorClass.errorClass(),
          errorClass.isRetryable(),
          safeReason);
    }
    return new StepFailure(errorClass, safeReason);
  }

  /**
   * Map a failure to its {@link ErrorClass}, most-specific rule first. Programming / validation /
   * policy / domain-state faults are permanent; connection / provider faults are transient;
   * anything unrecognized falls through to {@link ErrorClass#INTERNAL}.
   *
   * @param failure the failure to classify
   * @return the canonical error class
   */
  private ErrorClass classifyClass(Throwable failure) {
    // Permanent, never retry: a fault that a retry with identical input cannot overcome.
    if (failure instanceof ConflictException) {
      // Optimistic-lock / lease / reused Idempotency-Key collision (S07-05).
      return ErrorClass.CONFLICT;
    }
    if (isMalformedInput(failure)) {
      // IllegalArgumentException / IllegalStateException / null-pointer / wrong type / bad format:
      // a programming or validation error, never a transient condition.
      return ErrorClass.VALIDATION;
    }
    if (failure instanceof SecurityException) {
      // java.lang.SecurityException: a classic access / authorization check rejection.
      return ErrorClass.AUTHORIZATION;
    }
    // framework-auth faults by class-name prefix (no spring-security compile edge): an
    // authentication failure lives in the `org.springframework.security.authentication.*` subtree
    // (so its fully-qualified name contains "authentication"), an access-denied failure in
    // `...access.*`; the former is a credential / identity problem (AUTHENTICATION), the latter an
    // authorization problem (AUTHORIZATION).
    String name = failure.getClass().getName();
    if (name.startsWith("org.springframework.security")) {
      return name.toLowerCase(Locale.ROOT).contains("authentication")
          ? ErrorClass.AUTHENTICATION
          : ErrorClass.AUTHORIZATION;
    }
    // A constraint / duplicate-key failure is permanent: re-running identical input hits the same
    // constraint, so it is a conflict rather than a transient infra fault. Checked before the broad
    // SQLException rule below.
    if (failure instanceof org.springframework.dao.DataIntegrityViolationException
        || failure instanceof java.sql.SQLIntegrityConstraintViolationException) {
      return ErrorClass.CONFLICT;
    }

    // Transient, retry with bounded backoff: a dependency is briefly unreachable.
    if (failure instanceof java.sql.SQLException) {
      // A pool / connection / driver error (PSQLException is a subclass); a constraint violation
      // was
      // already handled above, so a bare SQLException is an infrastructure fault.
      return ErrorClass.TRANSIENT_INFRA;
    }
    if (isConnectionFailure(failure)) {
      // Connection refused / reset / unreachable host / Kafka broker down.
      return ErrorClass.TRANSIENT_INFRA;
    }
    if (isKafkaTransient(failure.getClass().getName())
        || failure instanceof java.util.concurrent.TimeoutException) {
      // A broker connection timeout or an external-call timeout; the attempt is persisted and the
      // outcome is retryable (never exactly-once, invariant 6).
      return ErrorClass.PROVIDER_TIMEOUT;
    }

    // Message-based heuristics for faults expressed as a message rather than a recognizable type.
    String message = failure.getMessage();
    if (message != null && !message.isBlank()) {
      String lower = message.toLowerCase(Locale.ROOT);
      if (isRateLimit(lower)) {
        return ErrorClass.PROVIDER_RATE_LIMIT;
      }
      if (lower.contains("timeout") || lower.contains("timed out")) {
        return ErrorClass.PROVIDER_TIMEOUT;
      }
      if (isConnectionFailureMessage(lower)) {
        return ErrorClass.TRANSIENT_INFRA;
      }
    }

    // Unknown / unexpected after the classifier runs: operator-visible catch-all, permanent.
    return ErrorClass.INTERNAL;
  }

  /**
   * A fault that means the envelope/step input is malformed and can never run as-is (permanent).
   */
  private static boolean isMalformedInput(Throwable failure) {
    return failure instanceof IllegalArgumentException
        || failure instanceof IllegalStateException
        || failure instanceof NullPointerException
        || failure instanceof UnsupportedOperationException
        || failure instanceof ClassCastException
        || failure instanceof NumberFormatException
        || failure instanceof java.util.regex.PatternSyntaxException;
  }

  /**
   * A connection-level failure: refused / reset / broken pipe / unreachable host / Kafka broker.
   */
  private static boolean isConnectionFailure(Throwable failure) {
    if (isKafkaTransient(failure.getClass().getName())) {
      return true;
    }
    return failure instanceof java.net.ConnectException
        || failure instanceof java.net.SocketException
        || failure instanceof java.net.UnknownHostException
        || failure instanceof java.io.EOFException;
  }

  /**
   * A Kafka fault that is transient on the wire (broker down / connection / timeout), by
   * class-name.
   */
  private static boolean isKafkaTransient(String className) {
    return className.startsWith("org.apache.kafka")
        && (className.contains("Timeout")
            || className.contains("Network")
            || className.contains("Connection")
            || className.contains("Disconnect"));
  }

  /** A rate-limit signal expressed as a message (provider HTTP 429 / throttling). */
  private static boolean isRateLimit(String lowerMessage) {
    return lowerMessage.contains("429")
        || lowerMessage.contains("rate limit")
        || lowerMessage.contains("throttl")
        || lowerMessage.contains("too many requests");
  }

  /**
   * A connection-failure signal expressed as a message (refused / reset / broken pipe /
   * unreachable).
   */
  private static boolean isConnectionFailureMessage(String lowerMessage) {
    return lowerMessage.contains("connection reset")
        || lowerMessage.contains("connection refused")
        || lowerMessage.contains("broken pipe")
        || lowerMessage.contains("unreachable")
        || lowerMessage.contains("unresolved")
        || lowerMessage.contains("no address")
        || lowerMessage.contains("io exception");
  }

  /**
   * Build a short, safe reason for {@code failure}: the fault type and message, redacted (invariant
   * 8) and length-bounded. A {@code null} fault yields a fixed "undetermined" label.
   */
  private static String safeReason(Throwable failure, ErrorClass errorClass) {
    if (failure == null) {
      return "undetermined failure (null cause)";
    }
    String message = failure.getMessage();
    String raw =
        (message == null || message.isBlank())
            ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    String redacted = CheckpointRedactor.redact(raw);
    if (redacted.length() > MAX_REASON_LENGTH) {
      return redacted.substring(0, MAX_REASON_LENGTH) + "\u2026";
    }
    return redacted;
  }
}
