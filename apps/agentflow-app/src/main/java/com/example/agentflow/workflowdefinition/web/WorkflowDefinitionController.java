package com.example.agentflow.workflowdefinition.web;

import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionDto;
import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionPageDto;
import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionService;
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
 * Workflow definition endpoints (S03-13 create, S03-14 list and get).
 *
 * <p>{@code POST /api/v1/workflows} builds a DRAFT definition and returns {@code 201 Created} with
 * the {@link WorkflowDefinitionDto}; a blank {@code name} fails bean validation and is rendered as
 * a {@code 400} validation Problem Details by the global error handler. {@code GET
 * /api/v1/workflows} lists a tenant's definitions as one paginated page, and {@code GET
 * /api/v1/workflows/{workflowId}} returns a single definition (a cross-tenant id is a {@code 404});
 * the {@code tenantId} filters every read in SQL (S03-14, invariant 1).
 *
 * <p>The {@code tenantId} is read from application configuration, not from the request body
 * (invariant 1 — the tenant is never derived from input). This is a temporary local-skeleton
 * bypass; the tenant comes from the verified security context (S04-10) in later sprints.
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowDefinitionController {

  private final WorkflowDefinitionService service;
  private final UUID tenantId;
  private final String actor;

  public WorkflowDefinitionController(
      WorkflowDefinitionService service,
      @Value("${agentflow.local.tenant-id:00000000-0000-0000-0000-000000000000}")
          String tenantIdRaw,
      @Value("${agentflow.local.actor:local}") String actor) {
    this.service = service;
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
}
