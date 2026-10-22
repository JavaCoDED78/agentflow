package com.example.agentflow.common;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The RFC 9457 <em>Problem Details</em> envelope returned by the global error handler (S03-11) for
 * every {@code 4xx}/{@code 5xx} response. It carries only what a client needs to act on the error:
 * the domain {@code error_class}, the {@code status}, a human-readable {@code detail}, the {@code
 * instance} that failed, a {@code correlation_id} for support/tracing, and {@code retryable} so a
 * client does not have to infer the retry decision from the status alone.
 *
 * <p>By design it never leaks a stack trace or internal detail: the handler supplies a safe {@code
 * detail} (a static message for {@link ErrorClass#INTERNAL}), and the raw exception is only ever
 * written to the structured log.
 *
 * <p>The field names match {@code docs/architecture/error-taxonomy.md} exactly ({@code
 * error_class}, {@code correlation_id}, …) via {@link JsonProperty} on the record components.
 */
public record ProblemDetails(
    @JsonProperty("type") String type,
    @JsonProperty("title") String title,
    @JsonProperty("status") int status,
    @JsonProperty("error_class") String errorClass,
    @JsonProperty("detail") String detail,
    @JsonProperty("instance") String instance,
    @JsonProperty("correlation_id") String correlationId,
    @JsonProperty("retryable") boolean retryable) {

  /**
   * Builds a {@link ProblemDetails} from a classified {@link ErrorClass}. The HTTP status, {@code
   * code}-style title and {@code retryable} flag all come from the class, so the shape is stable
   * across every error of that class.
   *
   * @param errorClass the classified error
   * @param detail a safe, human-readable message (already redacted by the caller)
   * @param instance the request path that failed
   * @param correlationId a correlation id for tracing
   */
  public static ProblemDetails of(
      ErrorClass errorClass, String detail, String instance, String correlationId) {
    return new ProblemDetails(
        "about:blank",
        errorClass.httpStatus().getReasonPhrase(),
        errorClass.httpStatus().value(),
        errorClass.errorClass(),
        detail,
        instance,
        correlationId,
        errorClass.isRetryable());
  }

  /**
   * Builds the {@code not_found} problem: {@code 404 / not_found}, permanent (never retried). Used
   * for a missing resource, including another tenant's, which is masked as missing (the {@code
   * 401/403/404} policy) rather than reported as a distinct error class.
   *
   * @param detail a safe, human-readable message (already redacted by the caller)
   * @param instance the request path that failed
   * @param correlationId a correlation id for tracing
   */
  public static ProblemDetails notFound(String detail, String instance, String correlationId) {
    return new ProblemDetails(
        "about:blank", "Not found", 404, "not_found", detail, instance, correlationId, false);
  }
}
