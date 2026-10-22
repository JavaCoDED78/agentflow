package com.example.agentflow.common.error;

import com.example.agentflow.common.ErrorClass;
import com.example.agentflow.common.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Single global entry point for turning any thrown exception into an RFC 9457 {@link
 * ProblemDetails} body (S03-11, {@code docs/architecture/error-taxonomy.md}).
 *
 * <p>It classifies only <em>cross-cutting</em> failures, keeping {@code common} free of any
 * module-specific exception type:
 *
 * <ul>
 *   <li><b>validation</b> ({@code 400}) — Spring MVC field/validation failures and an unreadable
 *       request body;
 *   <li><b>not-found / authentication / authorization / rate-limit / …</b> — a {@link
 *       ResponseStatusException} whose HTTP status is re-classified to the canonical {@link
 *       ErrorClass} ({@code 404} becomes the permanent {@code not_found} problem); a request that
 *       matched no route ({@link NoResourceFoundException}) is likewise a permanent {@code
 *       not_found}, never misreported as a 500 by the catch-all;
 *   <li><b>internal</b> ({@code 500}) — a catch-all for anything else: the full stack trace goes to
 *       the structured log, but the client gets a static message and never internals.
 * </ul>
 *
 * <p>Domain exceptions ({@code ConflictException}, {@code InvalidGraphException}, …) live in their
 * owning modules; the owning application layer maps them to a {@link ResponseStatusException} with
 * the right status, which the handler renders here. That keeps the handler in {@code common} (rule:
 * cross-cutting packages own only shared primitives) instead of importing every module's domain
 * exceptions.
 *
 * <p>The handler is a cross-cutting HTTP concern (a {@code @RestControllerAdvice}), not a
 * web-surface controller: it defines no endpoints and owns no business logic.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * Handles Spring's field- and parameter-validation failures. All of them are permanent client
   * errors, so they share one {@link ErrorClass#VALIDATION} mapping.
   */
  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<ProblemDetails> handleValidation(Exception ex, HttpServletRequest request) {
    return render(ErrorClass.VALIDATION, request, validationDetail(ex));
  }

  /**
   * A body that could not be read as JSON (malformed payload) is a client validation error, not a
   * server failure.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetails> handleUnreadableBody(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    return render(
        ErrorClass.VALIDATION,
        request,
        "The request body is malformed or could not be read as JSON");
  }

  /**
   * Classifies a {@link ResponseStatusException} (thrown by Spring Security,
   * {@code @ResponseStatus}, resource lookups, …) back to a canonical {@link ErrorClass} and
   * renders it. A {@code 404} is rendered as the permanent {@code not_found} problem; a bare {@code
   * 403} resolves to {@link ErrorClass#AUTHORIZATION}.
   */
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ProblemDetails> handleResponseStatus(
      ResponseStatusException ex, HttpServletRequest request) {
    int statusValue = ex.getStatusCode().value();
    String instance = instance(request);
    String correlationId = correlationId();
    String detail = reasonDetail(ex);
    if (statusValue == HttpStatus.NOT_FOUND.value()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(ProblemDetails.notFound(detail, instance, correlationId));
    }
    ErrorClass errorClass = ErrorClass.fromStatus(statusValue).orElse(ErrorClass.INTERNAL);
    return render(errorClass, statusValue, request, detail);
  }

  /**
   * A request that matched no route ({@code NoResourceFoundException}, or its {@code
   * NoHandlerFoundException} superclass) is a permanent 404, not a 500. These are infrastructure
   * exceptions Spring MVC throws when there is no handler for the path (e.g. a non-exposed Actuator
   * endpoint, or a mistyped URL), so they are rendered as a {@code not_found} problem rather than
   * being swallowed by the {@code Exception} catch-all below, which would misreport them as {@code
   * internal} and hide a missing route behind a server-error status.
   */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  ResponseEntity<ProblemDetails> handleNotFound(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            ProblemDetails.notFound(
                "The requested resource was not found", instance(request), correlationId()));
  }

  /**
   * Catch-all for anything unclassified. The complete stack trace is logged (with the correlation
   * id in the MDC) so an operator can trace it, but the client only ever sees a static message and
   * never an internal detail (S03-11: exclude stack traces and internals).
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetails> handleUnexpected(Exception ex, HttpServletRequest request) {
    String instance = instance(request);
    String correlationId = correlationId();
    String previousTraceId = MDC.get("traceId");
    MDC.put("traceId", correlationId);
    try {
      log.error("Unhandled error for request {}", instance, ex);
    } finally {
      if (previousTraceId == null) {
        MDC.remove("traceId");
      } else {
        MDC.put("traceId", previousTraceId);
      }
    }
    return render(ErrorClass.INTERNAL, HttpStatus.INTERNAL_SERVER_ERROR.value(), request, null);
  }

  /** Builds the {@link ProblemDetails} using the class's own HTTP status. */
  private ResponseEntity<ProblemDetails> render(
      ErrorClass errorClass, HttpServletRequest request, String detail) {
    return render(errorClass, errorClass.httpStatus().value(), request, detail);
  }

  /**
   * Builds the {@link ProblemDetails} with an explicit HTTP status (used when the caller supplies
   * its own status, e.g. a {@link ResponseStatusException}). The {@code error_class}/{@code
   * retryable} still come from the classified class, so the shape stays stable across classes.
   */
  private ResponseEntity<ProblemDetails> render(
      ErrorClass errorClass, int httpStatus, HttpServletRequest request, String detail) {
    return ResponseEntity.status(httpStatus)
        .body(
            new ProblemDetails(
                "about:blank",
                HttpStatus.resolve(httpStatus).getReasonPhrase(),
                httpStatus,
                errorClass.errorClass(),
                safeDefault(detail),
                instance(request),
                correlationId(),
                errorClass.isRetryable()));
  }

  private String validationDetail(Exception ex) {
    if (ex instanceof MethodArgumentNotValidException method) {
      List<String> messages =
          method.getBindingResult().getFieldErrors().stream()
              .map(field -> field.getField() + ": " + field.getDefaultMessage())
              .filter(message -> message != null)
              .distinct()
              .limit(10)
              .collect(Collectors.toList());
      return messages.isEmpty() ? "The request failed validation" : String.join("; ", messages);
    }
    return "The request failed validation";
  }

  /**
   * Extracts the human-readable reason from a {@link ResponseStatusException}, stripping the
   * redundant {@code 409 CONFLICT } prefix that {@code getMessage()} prepends. A {@code
   * null}/{@code blank} reason (e.g. a status created with no detail) yields {@code null}, which
   * {@code render} turns into a safe default.
   */
  private String reasonDetail(ResponseStatusException ex) {
    String reason = ex.getReason();
    return (reason != null && !reason.isBlank()) ? reason : null;
  }

  private String safeDefault(String detail) {
    return (detail != null && !detail.isBlank()) ? detail : "The request is invalid";
  }

  private String instance(HttpServletRequest request) {
    return request != null ? request.getRequestURI() : "/";
  }

  /**
   * @return the current trace id when tracing is wired (S13), otherwise a fresh correlation id. The
   *     id is added to the response so a client can quote it when raising a support ticket.
   */
  private String correlationId() {
    String traceId = MDC.get("traceId");
    return (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
  }
}
