package com.example.agentflow.messaging.web;

import com.example.agentflow.messaging.application.DlqInspectionService;
import com.example.agentflow.messaging.application.DlqMessageDetailDto;
import com.example.agentflow.messaging.application.DlqMessagePageDto;
import com.example.agentflow.messaging.application.DlqRedriveRequest;
import com.example.agentflow.messaging.application.DlqRedriveResult;
import com.example.agentflow.messaging.application.DlqRedriveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dead-letter queue inspection endpoints (S12-11): a paginated, read-only {@code GET
 * /api/v1/ops/dlq} overview and an audited {@code GET /api/v1/ops/dlq/{dlqId}} detail view; plus
 * the operator-only {@code POST /api/v1/ops/dlq/{dlqId}/redrive} that re-publishes the failed
 * envelope under a fresh id (S12-12).
 *
 * <p>All three endpoints are <b>operator-only</b>: the {@code GET} / {@code POST} methods are gated
 * before the request reaches here by the {@code ops:dlq} rule in {@link
 * com.example.agentflow.identity.application.authorization.AccessMatrix} (see {@link
 * com.example.agentflow.config.SecurityConfiguration}), so an ordinary {@code tenant:*} principal
 * is denied {@code 403} (the sole acceptance criterion of S12-11). The controller itself decides
 * only the data shape: the list is paginated with clamped page size and shows the overview metadata
 * (no failure text); the detail view resolves one message by id, exposes the redacted {@code
 * lastError}, and is audited (a {@code 404} for a missing id, a {@code 503} when no database is
 * wired — the DLQ is not tenant-scoped, so there is only "exists" or "does not exist"); the redrive
 * re-publishes the original envelope under a fresh id, guarded by the inbox source of truth,
 * recorded idempotently and audited (a {@code 400} for a blank reason, a {@code 404} for a missing
 * id / envelope, a {@code 409} for a completed / in-flight inbox row, a {@code 503} when no
 * database is wired).
 *
 * <p>{@code tenantId} / {@code actor} are read from configuration, not the request (invariant 1 —
 * the tenant is never derived from input). This {@code agentflow.local.tenant-id} / {@code
 * agentflow.local.actor} default is the same temporary local-skeleton bypass as the other
 * controllers.
 */
@RestController
@RequestMapping("/api/v1/ops/dlq")
public class DlqInspectionController {

  private final DlqInspectionService inspectionService;
  private final DlqRedriveService redriveService;
  private final UUID tenantId;
  private final String actor;

  public DlqInspectionController(
      DlqInspectionService inspectionService,
      DlqRedriveService redriveService,
      @Value("${agentflow.local.tenant-id:00000000-0000-0000-0000-000000000000}")
          String tenantIdRaw,
      @Value("${agentflow.local.actor:local}") String actor) {
    this.inspectionService = inspectionService;
    this.redriveService = redriveService;
    this.tenantId = UUID.fromString(tenantIdRaw);
    this.actor = actor;
  }

  /**
   * List the DLQ store as one paginated page (S12-11), newest first.
   *
   * @param page zero-based page index; defaults to {@code 0}
   * @param size requested page size; defaults to {@code 20} and is clamped up to the configured
   *     {@code max page size}
   * @return the page of overview records (metadata only, no failure text) plus pagination metadata,
   *     or a {@code 503} when no database is wired
   */
  @Operation(
      description =
          "Operator-only, read-only, paginated DLQ overview (metadata only, no raw payload). "
              + "Requires the {@code ops:dlq} permission; an ordinary tenant principal is denied "
              + "(403).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "the DLQ page plus pagination metadata"),
    @ApiResponse(responseCode = "400", description = "invalid pagination (page < 0 or size < 1)"),
    @ApiResponse(responseCode = "403", description = "caller lacks the ops:dlq permission"),
    @ApiResponse(responseCode = "503", description = "no database connection is configured")
  })
  @GetMapping
  public DlqMessagePageDto list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return inspectionService.listDlqMessages(page, size);
  }

  /**
   * Inspect one dead-letter message by id (S12-11): resolve the row, expose its redacted {@code
   * lastError}, and record an operator {@code READ} on the DLQ in the audit trail (the sensitive
   * detail view is audited). The DLQ is not tenant-scoped, so an id that does not exist is a {@code
   * 404} (there is no cross-tenant distinction).
   *
   * @param dlqId the DLQ row id (must be a UUID; a non-UUID path value is a {@code 400})
   * @return the full inspection view (metadata plus the redacted failure detail)
   * @throws org.springframework.web.server.ResponseStatusException {@code 404} if no DLQ row has
   *     the id, {@code 403} if the caller lacks {@code ops:dlq}, {@code 503} when no database is
   *     wired
   */
  @Operation(
      description =
          "Operator-only, read-only, audited DLQ detail view (opens one message, records a READ on "
              + "the DLQ in the audit trail). Requires {@code ops:dlq}; an ordinary tenant principal "
              + "is denied (403).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "the full inspection view (metadata plus the redacted failure detail)"),
    @ApiResponse(responseCode = "404", description = "no DLQ message with that id"),
    @ApiResponse(responseCode = "403", description = "caller lacks the ops:dlq permission"),
    @ApiResponse(responseCode = "503", description = "no database connection is configured")
  })
  @GetMapping("/{dlqId}")
  public DlqMessageDetailDto inspect(@PathVariable UUID dlqId) {
    return inspectionService.inspectDlqMessage(dlqId, tenantId, actor);
  }

  /**
   * Redrive one dead-lettered message (S12-12): re-publish the failed envelope under a fresh id,
   * guarded by the inbox source of truth, recorded idempotently and audited. The endpoint is
   * operator-only (the {@code ops:dlq} rule denies a {@code 403}); the service decides the data
   * shape — a {@code 400} for a blank reason, a {@code 404} for a missing DLQ row / original
   * envelope, a {@code 409} for a completed / in-flight inbox row (the business change must not be
   * duplicated), a {@code 503} when no database is wired, and a {@code 200} with the re-published
   * envelope id (or the existing redrive on a replay, so the command is idempotent).
   *
   * @param dlqId the DLQ row id (must be a UUID; a non-UUID path value is a {@code 400})
   * @param request the body carrying the operator-supplied {@code reason} (non-blank)
   * @return the redrive result (the re-published envelope id plus the operator context)
   * @throws org.springframework.web.server.ResponseStatusException {@code 400} (blank reason),
   *     {@code 403} (caller lacks {@code ops:dlq}), {@code 404} (no DLQ row / original envelope),
   *     {@code 409} (a completed / in-flight inbox row), {@code 503} (no database wired)
   */
  @Operation(
      description =
          "Operator-only, controlled redrive (S12-12): re-publish the failed envelope under a fresh "
              + "id, guarded by the inbox source of truth, recorded idempotently and audited. "
              + "Requires {@code ops:dlq}; an ordinary tenant principal is denied (403).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description =
            "the redrive result (re-published envelope id, or the existing redrive on a replay)"),
    @ApiResponse(responseCode = "400", description = "a blank operator reason"),
    @ApiResponse(responseCode = "403", description = "caller lacks the ops:dlq permission"),
    @ApiResponse(
        responseCode = "404",
        description = "no DLQ message / original outbox envelope with that id"),
    @ApiResponse(
        responseCode = "409",
        description =
            "the message is already processed (terminal) or in-flight — redrive refused, the business "
                + "change must not be duplicated"),
    @ApiResponse(responseCode = "503", description = "no database connection is configured")
  })
  @PostMapping("/{dlqId}/redrive")
  public DlqRedriveResult redrive(
      @PathVariable UUID dlqId, @RequestBody DlqRedriveRequest request) {
    return redriveService.redrive(dlqId, tenantId, actor, request);
  }
}
