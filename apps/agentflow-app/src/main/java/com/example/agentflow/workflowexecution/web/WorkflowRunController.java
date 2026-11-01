package com.example.agentflow.workflowexecution.web;

import com.example.agentflow.common.Fingerprint;
import com.example.agentflow.workflowexecution.application.CreateRunRequest;
import com.example.agentflow.workflowexecution.application.RunDto;
import com.example.agentflow.workflowexecution.application.RunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Create-run endpoint (`POST /api/v1/runs`, S06-05 / S06-06).
 *
 * <p>Returns {@code 202 Accepted} with the created {@link RunDto} &mdash; a workflow is
 * <em>starting</em>, not yet complete, so it is not a {@code 201 Created} (invariant 11). The run
 * is pinned to the tenant's active version, created and its outbox event appended by {@link
 * RunService#createRun} in one transaction, and answered idempotently under the client {@code
 * Idempotency-Key}.
 *
 * <p><b>Idempotency-Key (S06-06).</b> The key is resolved from the {@code Idempotency-Key} request
 * header when present (the HTTP idempotency-key convention), otherwise from the request body's
 * {@code idempotencyKey} (the {@link CreateRunRequest} contract). A repeat with the <em>same</em>
 * payload under that key returns the original run; a repeat with a <em>different</em> payload under
 * the same key is a {@code 409 conflict} (see {@link RunService#createRun}); neither is a silent
 * overwrite. The request fingerprint is the lowercase-hex SHA-256 of the request payload, stored
 * opaque alongside the run and never used as an authorization source (invariant 8).
 *
 * <p>The {@code tenantId} is read from application configuration, not from the request body
 * (invariant 1 — the tenant is never derived from input), exactly as {@code
 * WorkflowDefinitionController} does; threading it from the verified security context is a
 * follow-up ADR (the local default is a documented tenant-isolation skeleton).
 */
@RestController
@RequestMapping("/api/v1/runs")
public class WorkflowRunController {

  private final RunService runService;
  private final ObjectMapper objectMapper;
  private final UUID tenantId;
  private final String actor;

  public WorkflowRunController(
      RunService runService,
      @Value("${agentflow.local.tenant-id:00000000-0000-0000-0000-000000000000}")
          String tenantIdRaw,
      @Value("${agentflow.local.actor:local}") String actor) {
    this.runService = runService;
    this.objectMapper = new ObjectMapper();
    this.tenantId = UUID.fromString(tenantIdRaw);
    this.actor = actor;
  }

  /**
   * Start a run, idempotently under the {@code Idempotency-Key}.
   *
   * <p>Returns {@code 202 Accepted} with the created (or replayed) {@link RunDto}, or:
   *
   * <ul>
   *   <li>{@code 400 bad_request} when the body is not the {@code workflowId}-carrying JSON the
   *       {@link CreateRunRequest} contract expects (the tenant id comes from configuration, never
   *       here);
   *   <li>{@code 404 not_found} when the workflow has no active version owned by the tenant;
   *   <li>{@code 409 conflict} when a reused {@code Idempotency-Key} carries a different request
   *       payload;
   *   <li>{@code 503 unavailable} when no database is configured.
   * </ul>
   *
   * @param idempotencyKey the client idempotency key, or absent for an unkeyed create
   * @param rawBody the raw create-run payload JSON (fingerprinted as-is, so a retried request with
   *     the same bytes is recognized as the same payload)
   * @return {@code 202 Accepted} with the run ({@code QUEUED}, {@code attempt 1}, pinned to the
   *     active version); on a keyed replay the original run is returned
   */
  @Operation(
      summary = "Start a workflow run (idempotent under Idempotency-Key)",
      description =
          "Starts a run pinned to the tenant's active version and returns it in status "
              + "QUEUED (202 Accepted — the workflow is starting, not complete, invariant 11). "
              + "Idempotent under Idempotency-Key (S06-06): a repeat with the same payload "
              + "returns the original run; a repeat with a different payload under the same "
              + "key is a 409 conflict; neither is a silent overwrite.")
  @ApiResponse(
      responseCode = "202",
      description =
          "the run started, or the original run replayed when the key matches the payload")
  @ApiResponse(
      responseCode = "400",
      description = "the body is not the workflowId-carrying JSON the contract expects")
  @ApiResponse(
      responseCode = "409",
      description = "a reused Idempotency-Key carries a different request payload")
  @ApiResponse(responseCode = "503", description = "no database connection is configured")
  @PostMapping
  ResponseEntity<RunDto> create(
      @Parameter(
              description =
                  "A client-chosen idempotency key. A repeat with the same payload under this "
                      + "key returns the original run (S06-06); a different payload under the "
                      + "same key is a 409 conflict. Omit for an unkeyed create.")
          @RequestHeader(value = "Idempotency-Key", required = false)
          String idempotencyKey,
      @Parameter(
              description =
                  "An optional W3C Trace-Context trace-parent header (e.g. "
                      + "\"00-<traceId>-<spanId>-<flags>\"). It is threaded verbatim into the run "
                      + "request event's correlation headers so the trace survives across Kafka "
                      + "(S06-12); it is a trace label only and never an authorization input "
                      + "(invariant 1). Omit for an untraced create.")
          @RequestHeader(value = "traceparent", required = false)
          String traceparent,
      @RequestBody String rawBody) {
    CreateRunRequest parsed = parse(rawBody);
    UUID effectiveKey = resolveKey(idempotencyKey, parsed);
    // Feed the resolved key back into the request so the service deduplicates on it (the body's own
    // key is already folded in when the header is absent); the fingerprint is over that request.
    CreateRunRequest request = new CreateRunRequest(parsed.workflowId(), effectiveKey);
    String fingerprint = effectiveKey == null ? null : Fingerprint.sha256Hex(canonical(request));
    RunDto run = runService.createRun(tenantId, actor, request, fingerprint, traceparent);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
  }

  /**
   * The key to deduplicate under: the {@code Idempotency-Key} header when present (the HTTP
   * idempotency-key convention), otherwise the body's {@code idempotencyKey}. A blank header is
   * treated as absent.
   *
   * @param idempotencyKeyHeader the {@code Idempotency-Key} request header, or {@code null} when
   *     the client sent none
   * @param parsed the parsed request body (its {@code idempotencyKey} is used when no header is
   *     sent)
   * @return the resolved key, or {@code null} for an unkeyed create
   */
  private static UUID resolveKey(String idempotencyKeyHeader, CreateRunRequest parsed) {
    if (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()) {
      return UUID.fromString(idempotencyKeyHeader.trim());
    }
    return parsed.idempotencyKey();
  }

  /**
   * Serialize the request to a canonical JSON form so two requests carrying the same logical
   * payload fingerprint identically regardless of input whitespace or property order.
   *
   * @param request the request to canonicalize
   * @return the deterministic JSON encoding of the request
   */
  private String canonical(CreateRunRequest request) {
    try {
      return objectMapper.writeValueAsString(request);
    } catch (tools.jackson.core.JacksonException e) {
      // A record can never fail its own serialization; treat it as a programming error.
      throw new IllegalStateException("the create-run request could not be canonicalized", e);
    }
  }

  /**
   * Parse the raw request body into the {@link CreateRunRequest} contract, rejecting anything that
   * is not the expected JSON (a non-{@code workflowId} body is a {@code 400}).
   *
   * @param rawBody the raw payload JSON
   * @return the parsed request
   * @throws org.springframework.web.server.ResponseStatusException {@code 400} when the body is not
   *     the expected JSON
   */
  private CreateRunRequest parse(String rawBody) {
    try {
      return objectMapper.readValue(
          rawBody.getBytes(StandardCharsets.UTF_8), CreateRunRequest.class);
    } catch (tools.jackson.core.JacksonException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "the create-run request body is not valid JSON");
    }
  }
}
