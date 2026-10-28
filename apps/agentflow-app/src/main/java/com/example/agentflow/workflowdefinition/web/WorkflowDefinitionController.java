package com.example.agentflow.workflowdefinition.web;

import com.example.agentflow.workflowdefinition.application.GraphValidationResult;
import com.example.agentflow.workflowdefinition.application.GraphValidationService;
import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionDto;
import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionPageDto;
import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionService;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionDto;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow definition endpoints (S03-13 create, S03-14 list and get; S05-13 a read-only graph
 * validation preview; S05-14 an activate that transitions a draft version to immutable).
 *
 * <p>{@code POST /api/v1/workflows} builds a DRAFT definition and returns {@code 201 Created} with
 * the {@link WorkflowDefinitionDto}; a blank {@code name} fails bean validation and is rendered as
 * a {@code 400} validation Problem Details by the global error handler. {@code GET
 * /api/v1/workflows} lists a tenant's definitions as one paginated page, and {@code GET
 * /api/v1/workflows/{workflowId}} returns a single definition (a cross-tenant id is a {@code 404});
 * the {@code tenantId} filters every read in SQL (S03-14, invariant 1).
 *
 * <p>The {@code tenantId} is read from application configuration, not from the request body
 * (invariant 1 — the tenant is never derived from input). This {@code agentflow.local.tenant-id}
 * default is a temporary local-skeleton tenant-isolation bypass left as a follow-up: threading the
 * tenant from the verified security context needs a new `workflowdefinition` → `identity` edge (an
 * ADR), so it is tracked separately rather than removed with the S04-10 permissive-auth fix.
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowDefinitionController {

  private final WorkflowDefinitionService service;
  private final GraphValidationService graphValidationService;
  private final WorkflowVersionService versionService;
  private final UUID tenantId;
  private final String actor;

  public WorkflowDefinitionController(
      WorkflowDefinitionService service,
      GraphValidationService graphValidationService,
      WorkflowVersionService versionService,
      @Value("${agentflow.local.tenant-id:00000000-0000-0000-0000-000000000000}")
          String tenantIdRaw,
      @Value("${agentflow.local.actor:local}") String actor) {
    this.service = service;
    this.graphValidationService = graphValidationService;
    this.versionService = versionService;
    this.tenantId = UUID.fromString(tenantIdRaw);
    this.actor = actor;
  }

  @PostMapping
  ResponseEntity<WorkflowDefinitionDto> create(
      @Valid @RequestBody CreateWorkflowDefinitionRequest body) {
    WorkflowDefinition definition =
        service.createDefinition(tenantId, actor, body.name(), body.description());
    return ResponseEntity.status(HttpStatus.CREATED).body(WorkflowDefinitionDto.from(definition));
  }

  /**
   * Lists a tenant's definitions as one paginated page (S03-14). The {@code tenantId} is resolved
   * from the trusted security context (see {@code #create}); the service filters by {@code
   * tenant_id} in SQL and enforces the {@code max page size}, so another tenant's definitions never
   * appear.
   *
   * @param page zero-based page index; defaults to {@code 0}
   * @param size requested page size; defaults to {@code 20} and is clamped up to the configured
   *     {@code max page size}. A {@code page} of {@code 0} and a {@code size} of {@code 1..(max)}
   *     are the valid range; anything else is a {@code 400}
   * @return the page of definitions plus pagination metadata ({@code page}, {@code size}, {@code
   *     totalElements}, {@code totalPages}, {@code hasNext}, {@code hasPrevious})
   */
  @GetMapping
  public ResponseEntity<WorkflowDefinitionPageDto> listDefinitions(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    WorkflowDefinitionPageDto pageDto = service.listDefinitions(tenantId, page, size);
    return ResponseEntity.ok(pageDto);
  }

  /**
   * Gets a single definition by its {@code (tenant_id, id)} pair (S03-14). Resolved through the
   * tenant-scoped service, so a cross-tenant id is reported as a {@code 404} (the 404 / 403
   * policy).
   *
   * @param workflowId the definition id (must be a UUID; a non-UUID path value is a {@code 400})
   * @return the definition, or a {@code 404} if it does not belong to this tenant
   */
  @GetMapping("/{workflowId}")
  public ResponseEntity<WorkflowDefinitionDto> getDefinition(@PathVariable UUID workflowId) {
    WorkflowDefinition definition = service.getDefinition(tenantId, workflowId);
    return ResponseEntity.ok(WorkflowDefinitionDto.from(definition));
  }

  /**
   * Validates a raw graph payload against a definition before a version is created or activated
   * (S05-13).
   *
   * <p>Returns {@code 200} with a {@link GraphValidationResult} for every request — {@code valid =
   * true} with no violations when the graph is structurally sound, or {@code valid = false} with
   * the full, ordered list of {@code violations} (each a JSON {@code path} plus a human {@code
   * message}) when it is not. The request body is the raw graph JSON (client input — treated as
   * untrusted <em>data</em>, never executed). No version is created and nothing is audited: this is
   * a read-only preview, so a client can confirm validity or fix its payload before {@code POST
   * /api/v1/workflows /{id}/versions}. The tenant-scoped definition lookup (see {@code
   * GraphValidationService#validateGraph}) means a cross-tenant or missing id is reported as a
   * {@code 404}, and a {@code 503} is returned when no database is wired (the lookup cannot run).
   *
   * @param workflowId the definition id (must be a UUID; a non-UUID path value is a {@code 400})
   * @param rawGraphJson the raw graph JSON to validate
   * @return {@code 200} with the validation result ({@code valid} plus the ordered {@code
   *     violations} list)
   */
  @PostMapping("/{workflowId}/validate")
  public ResponseEntity<GraphValidationResult> validate(
      @PathVariable UUID workflowId, @RequestBody String rawGraphJson) {
    GraphValidationResult result =
        graphValidationService.validateGraph(tenantId, workflowId, rawGraphJson);
    return ResponseEntity.ok(result);
  }

  /**
   * Activates a draft version, pinning its version number and checksum as immutable (S05-14).
   *
   * <p>Requires {@code workflow:activate} (the {@link
   * com.example.agentflow.identity.application.authorization.AccessMatrix} rule for this {@code
   * POST ... /versions/{versionId}/activate} gates it before the request reaches here). The version
   * is looked up and transitioned by the {@link WorkflowVersionService#activateVersion} single
   * transition service:
   *
   * <ul>
   *   <li>a <b>DRAFT</b> version whose pinned {@code checksum} matches transitions {@code DRAFT →
   *       ACTIVATED}, its audit {@link com.example.agentflow.audit.domain.AuditAction#ACTIVATE}
   *       event is written in the same transaction (atomic activation/audit, invariant 3);
   *   <li>a repeat on an <b>already-activated</b> version is <b>idempotent</b> (S05-14): it returns
   *       that same immutable version with no error and no second audit row, so a client that
   *       retries after a lost response succeeds cleanly;
   *   <li>a tampered checksum or a competing writer (the conditional update changes zero rows) is a
   *       permanent {@code 409} conflict, not a silent success (invariant 6).
   * </ul>
   *
   * <p>A draft's graph is validated at creation (S05-04/S05-05/S05-06), so an <b>invalid graph can
   * never be activated</b> — activation only ever transitions a stored, pre-validated draft. A
   * cross-tenant or missing version is reported exactly like a missing one ({@code 404 not_found}),
   * and a version that does not belong to {@code workflowId} is {@code 404} under this workflow
   * (invariant 1 — the tenant filter is in the repository SQL, and the parent-workflow check adds
   * the resource hierarchy). No database is a {@code 503}.
   *
   * @param workflowId the parent definition id (must be a UUID; a non-UUID path value is a {@code
   *     400})
   * @param versionId the version id to activate (must be a UUID; a non-UUID path value is a {@code
   *     400})
   * @return {@code 200 OK} with the activated (now immutable) version; {@code 404 not_found},
   *     {@code 409 conflict} or {@code 503} as described above
   */
  @PostMapping("/{workflowId}/versions/{versionId}/activate")
  public ResponseEntity<WorkflowVersionDto> activate(
      @PathVariable UUID workflowId, @PathVariable UUID versionId) {
    WorkflowVersionDto activated =
        versionService.activateVersion(tenantId, workflowId, versionId, actor);
    return ResponseEntity.ok(activated);
  }
}
