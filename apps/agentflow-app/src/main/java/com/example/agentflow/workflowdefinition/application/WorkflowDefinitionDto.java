package com.example.agentflow.workflowdefinition.application;

import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import java.time.Instant;
import java.util.UUID;

/**
 * Public API representation of a {@link WorkflowDefinition} returned by the create endpoint
 * (S03-13).
 *
 * <p>Fields are camelCase ({@code docs/architecture/engineering-conventions.md}) and deliberately
 * distinct from the persistence object. This is the {@code workflowdefinition} module's public DTO
 * ({@code docs/architecture/module-api.md}); it is the permitted type the application layer exposes
 * across the module boundary.
 */
public record WorkflowDefinitionDto(
    UUID id,
    UUID tenantId,
    String name,
    String description,
    String status,
    int version,
    String createdBy,
    Instant createdAt) {

  /** Maps the domain model to the API DTO. A presentation concern of the controller. */
  public static WorkflowDefinitionDto from(WorkflowDefinition definition) {
    return new WorkflowDefinitionDto(
        definition.id(),
        definition.tenantId(),
        definition.name(),
        definition.description(),
        definition.status(),
        definition.version(),
        definition.createdBy(),
        definition.createdAt());
  }
}
