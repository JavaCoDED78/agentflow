package com.example.agentflow.workflowexecution.web;

import com.example.agentflow.common.Fingerprint;
import com.example.agentflow.workflowexecution.application.ApprovalDecisionDto;
import com.example.agentflow.workflowexecution.application.ApprovalDecisionRequest;
import com.example.agentflow.workflowexecution.application.ApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * The human-approval decision endpoint — {@code POST /api/v1/runs/{runId}/approvals/{approvalId}}
 * (S11-04 approve, S11-05 reject, glossary §9).
 *
 * <p>Decides the gated side effect of one {@code HUMAN_APPROVAL} step through {@link
 * ApprovalService#approve} / {@link ApprovalService#reject}: the decision, its audit row, the
 * run/step completion and the outbox continuation/refusal commit atomically ({@code ADR-018},
 * {@code ADR-004}), and a repeat — with or without the client's {@code Idempotency-Key} — replays
 * the recorded decision instead of writing a second one. The endpoint is gated on the {@code
 * run:approve} authority by the access matrix (S04-08), so a caller without that capability is
 * denied before it ever reaches this controller; the tenant/resource checks inside the service make
 * another tenant's approval indistinguishable from a missing one (invariant 1, the S11-04
 * acceptance).
 *
 * <p>The {@code tenantId} and {@code actor} are read from application configuration, not from the
 * request (invariant 1 — never derived from input), exactly as {@link WorkflowRunController} does;
 * threading them from the verified security context is the follow-up ADR recorded for the whole
 * local skeleton ({@code ADR-012}).
 */
@RestController
@RequestMapping("/api/v1/runs")
public class ApprovalController {

  private final ApprovalService approvalService;
  private final ObjectMapper objectMapper;
  private final UUID tenantId;
  private final String actor;

  public ApprovalController(
      ApprovalService approvalService,
      @Value("${agentflow.local.tenant-id:00000000-0000-0000-0000-000000000000}")
          String tenantIdRaw,
      @Value("${agentflow.local.actor:local}") String actor) {
    this.approvalService = approvalService;
    this.objectMapper = new ObjectMapper();
    this.tenantId = UUID.fromString(tenantIdRaw);
    this.actor = actor;
  }

  /**
   * Decide a human-approval request (approve or reject), idempotently.
   *
   * <p>Returns {@code 200 OK} with the persisted {@link ApprovalDecisionDto} — on the first
   * decision and on every state- or key-based replay alike, so a client retrying a lost response
   * succeeds cleanly (invariant 6). Or:
   *
   * <ul>
   *   <li>{@code 400 bad_request} when the body is not the decision JSON the contract expects, the
   *       {@code decision} is neither {@code "approve"} nor {@code "reject"}, or the {@code
   *       Idempotency-Key} header is not a UUID;
   *   <li>{@code 404 not_found} when no approval matches {@code (tenant_id, run_id, approvalId)} —
   *       another tenant's approval is indistinguishable from a missing one (invariant 1);
   *   <li>{@code 409 conflict} when the approval is already decided otherwise, expired, its step
   *       moved on, or a reused {@code Idempotency-Key} carries a different payload;
   *   <li>{@code 503 unavailable} when no database is configured.
   * </ul>
   *
   * @param runId the run the approval gates (path)
   * @param approvalId the approval record to decide (path)
   * @param idempotencyKey an optional client idempotency key: a repeat with the same payload under
   *     this key replays the recorded decision; a different payload under the same key is a {@code
   *     409} (S11-04, {@code V3_3_23})
   * @param rawBody the raw decision JSON ({@code {"decision":"approve"|"reject","comment":"…"}})
   * @return {@code 200 OK} with the decision snapshot (original or replayed)
   */
  @Operation(
      operationId = "decideApproval",
      summary =
          "Decide a human-approval request: approve resumes the run, reject fails it (idempotent)",
      description =
          "Records the human decision on a PENDING approval and its audit row in one transaction "
              + "(S11-04/S11-05, ADR-018). approve resumes the run (WAITING_FOR_APPROVAL -> "
              + "RUNNING, workflow.run.resumed.v1 through the outbox); reject refuses the side "
              + "effect (run and gated step -> FAILED, workflow.run.rejected.v1) — the gated side "
              + "effect never executes. Idempotent: a repeat — with or without the "
              + "Idempotency-Key — replays the recorded decision instead of writing a second one; "
              + "a reused key with a different payload is a 409. Requires the run:approve "
              + "authority (S04-08); tenant-scoped so another tenant's approval is a 404, never a "
              + "leak.")
  @ApiResponse(responseCode = "200", description = "the decision snapshot (original or replayed)")
  @ApiResponse(
      responseCode = "400",
      description =
          "the body is not the decision JSON, the decision is neither 'approve' nor 'reject', or the key is not a UUID")
  @ApiResponse(
      responseCode = "404",
      description = "no approval matches (tenant_id, run_id, approvalId)")
  @ApiResponse(
      responseCode = "409",
      description =
          "already decided otherwise, expired, the step moved on, or a reused key carries a different payload")
  @ApiResponse(responseCode = "503", description = "no database connection is configured")
  @PostMapping("/{runId}/approvals/{approvalId}")
  ResponseEntity<ApprovalDecisionDto> decide(
      @PathVariable UUID runId,
      @PathVariable UUID approvalId,
      @Parameter(
              description =
                  "A client-chosen idempotency key. A repeat with the same payload under this "
                      + "key replays the recorded decision (S11-04); a different payload under "
                      + "the same key is a 409 conflict. Omit for an unkeyed decision — the "
                      + "state-based replay still answers a repeat.")
          @RequestHeader(value = "Idempotency-Key", required = false)
          String idempotencyKey,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description =
                  "The decision body — {\"decision\":\"approve\",\"comment\":…}. Consumed as the raw "
                      + "bytes so the idempotency fingerprint is canonical (wire-format independent).",
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = ApprovalDecisionRequest.class)))
          @RequestBody
          String rawBody) {
    ApprovalDecisionRequest parsed = parse(rawBody);
    UUID key = parseKey(idempotencyKey);
    String fingerprint =
        key == null
            ? null
            : Fingerprint.sha256Hex(canonical(new DecisionPayload(approvalId, parsed)));
    // The closed decision vocabulary (S11-04 approve, S11-05 reject): anything else is a 400 at
    // the edge, never a service-level surprise.
    if ("approve".equals(parsed.decision())) {
      return ResponseEntity.ok(
          approvalService.approve(
              tenantId, actor, runId, approvalId, parsed.comment(), key, fingerprint));
    }
    if ("reject".equals(parsed.decision())) {
      return ResponseEntity.ok(
          approvalService.reject(
              tenantId, actor, runId, approvalId, parsed.comment(), key, fingerprint));
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "decision must be \"approve\" or \"reject\"");
  }

  /**
   * The key to deduplicate under: the {@code Idempotency-Key} header, or {@code null} when absent
   * or blank. A malformed (non-UUID) key is a {@code 400} — the client should retry with a valid
   * key rather than be silently treated as unkeyed.
   */
  private static UUID parseKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(idempotencyKey.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be a UUID");
    }
  }

  /**
   * Serialize the decision payload to a canonical JSON form so two requests carrying the same
   * logical payload fingerprint identically regardless of input whitespace or property order. The
   * approval id is included so a key reused across two different approvals can never replay as the
   * same payload.
   */
  private String canonical(DecisionPayload payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (tools.jackson.core.JacksonException e) {
      // A record can never fail its own serialization; treat it as a programming error.
      throw new IllegalStateException("the decision payload could not be canonicalized", e);
    }
  }

  /**
   * Parse the raw request body into the {@link ApprovalDecisionRequest} contract, rejecting
   * anything that is not the expected JSON.
   *
   * @param rawBody the raw payload JSON
   * @return the parsed request
   * @throws ResponseStatusException {@code 400} when the body is not the expected JSON
   */
  private ApprovalDecisionRequest parse(String rawBody) {
    try {
      return objectMapper.readValue(
          rawBody.getBytes(StandardCharsets.UTF_8), ApprovalDecisionRequest.class);
    } catch (tools.jackson.core.JacksonException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "the approval-decision body is not valid JSON");
    }
  }

  /**
   * The fingerprinted decision payload: the approval id (path) plus the body's decision and
   * comment. Bound into the fingerprint so an {@code Idempotency-Key} can never replay a decision
   * across two different approvals.
   */
  private record DecisionPayload(UUID approvalId, String decision, String comment) {
    private DecisionPayload(UUID approvalId, ApprovalDecisionRequest request) {
      this(approvalId, request.decision(), request.comment());
    }
  }
}
