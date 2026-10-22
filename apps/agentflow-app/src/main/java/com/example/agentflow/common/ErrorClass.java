package com.example.agentflow.common;

import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * The canonical, domain-neutral error taxonomy (S02-10, {@code
 * docs/architecture/error-taxonomy.md}).
 *
 * <p>Each constant is one error <em>class</em>. The {@link #errorClass()} label is the token shared
 * by three consumers so they agree on what an error <em>is</em>:
 *
 * <ul>
 *   <li>the <b>global error handler</b> (S03-11) maps it to an HTTP status and the RFC 9457 {@code
 *       ProblemDetails} body;
 *   <li>the worker retry classifier (S07-11) decides retry (transient) versus terminal (permanent);
 *   <li>the {@code error_class} metric label (rule 9) — never a high-cardinality id.
 * </ul>
 *
 * <p>This enum owns only the classification; it carries no business state. It is not a base class
 * for domain exceptions (those live in their owning modules and are surfaced to the handler through
 * the HTTP layer, S03-11).
 *
 * <p>{@code not_found} is intentionally <em>not</em> a class: a missing resource (including another
 * tenant's, masked as missing) is reported as {@code 404 / not_found} by the global handler, not as
 * a distinct error class (see the {@code 401/403/404} policy in the taxonomy).
 */
public enum ErrorClass {

  /** Request validation failed (bad graph shape, missing/invalid field, malformed JSON). */
  VALIDATION("validation", HttpStatus.BAD_REQUEST, "validation_failed", false),

  /** Missing / invalid / expired credential, wrong issuer or audience. */
  AUTHENTICATION("authentication", HttpStatus.UNAUTHORIZED, "authentication_failed", false),

  /** Known caller without the scope for the action or resource. */
  AUTHORIZATION("authorization", HttpStatus.FORBIDDEN, "authorization_failed", false),

  /** Optimistic {@code version} stale, or a duplicate under a reused {@code Idempotency-Key}. */
  CONFLICT("conflict", HttpStatus.CONFLICT, "conflict", false),

  /** A dependency is briefly unreachable (DB pool, Kafka, Redis). */
  TRANSIENT_INFRA("transient_infra", HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", true),

  /** A model provider returned {@code 429}. */
  PROVIDER_RATE_LIMIT(
      "provider_rate_limit", HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", true),

  /** A model call exceeded its timeout; the attempt is persisted as ambiguous/retryable. */
  PROVIDER_TIMEOUT("provider_timeout", HttpStatus.GATEWAY_TIMEOUT, "provider_timeout", true),

  /** Internal catch-all after the classifier runs; operator attention. */
  INTERNAL("internal", HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", false);

  private static final Map<HttpStatus, ErrorClass> BY_STATUS =
      Map.of(
          HttpStatus.BAD_REQUEST, VALIDATION,
          HttpStatus.UNAUTHORIZED, AUTHENTICATION,
          HttpStatus.FORBIDDEN, AUTHORIZATION,
          HttpStatus.CONFLICT, CONFLICT,
          HttpStatus.TOO_MANY_REQUESTS, PROVIDER_RATE_LIMIT,
          HttpStatus.SERVICE_UNAVAILABLE, TRANSIENT_INFRA,
          HttpStatus.GATEWAY_TIMEOUT, PROVIDER_TIMEOUT,
          HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL);

  private final String errorClass;
  private final HttpStatus httpStatus;
  private final String rfc9457Code;
  private final boolean retryable;

  ErrorClass(String errorClass, HttpStatus httpStatus, String rfc9457Code, boolean retryable) {
    this.errorClass = errorClass;
    this.httpStatus = httpStatus;
    this.rfc9457Code = rfc9457Code;
    this.retryable = retryable;
  }

  /**
   * @return the canonical lower-case token (the metric label and the taxonomy identifier), e.g.
   *     {@code validation}
   */
  public String errorClass() {
    return errorClass;
  }

  /**
   * @return the HTTP status this class maps to by default
   */
  public HttpStatus httpStatus() {
    return httpStatus;
  }

  /**
   * @return the RFC 9457 {@code code} member for this class, e.g. {@code validation_failed}
   */
  public String rfc9457Code() {
    return rfc9457Code;
  }

  /**
   * @return {@code true} only for the transient classes, which the worker (S07-11) retries with
   *     bounded backoff; every other class is permanent for the request
   */
  public boolean isRetryable() {
    return retryable;
  }

  /**
   * Looks up the error class for a given HTTP status value. Used by the global error handler
   * (S03-11) to classify a {@code ResponseStatusException}; a {@code 404} is handled separately as
   * {@code not_found} and a bare {@code 403} resolves to {@link #AUTHORIZATION} (tool-policy denial
   * is a tool-layer concern, S08).
   *
   * @param value the numeric HTTP status
   * @return the matching class, or {@link #Optional#empty()} if the status is unlisted
   */
  public static Optional<ErrorClass> fromStatus(int value) {
    ErrorClass match = null;
    for (var entry : BY_STATUS.entrySet()) {
      if (entry.getKey().value() == value) {
        match = entry.getValue();
        break;
      }
    }
    return Optional.ofNullable(match);
  }
}
