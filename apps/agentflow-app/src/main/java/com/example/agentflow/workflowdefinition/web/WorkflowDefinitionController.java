package com.example.agentflow.workflowdefinition.web;

import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionDto;
import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create workflow definition endpoint (S03-13).
 *
 * <p>{@code POST /api/v1/workflows} builds a DRAFT definition and returns {@code 201 Created} with
 * the {@link WorkflowDefinitionDto}; a blank {@code name} fails bean validation and is rendered as
 * a {@code 400} validation Problem Details by the global error handler.
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
}
