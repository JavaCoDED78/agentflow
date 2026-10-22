package com.example.agentflow.workflowdefinition.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable domain model of a tenant-scoped workflow definition.
 *
 * <p>Owned by the {@code workflowdefinition} module. The repository (see {@code
 * workflowdefinition.persistence}) maps this record to and from a single PostgreSQL row. It is a
 * value model: no behaviour that mutates state, so it can safely cross module boundaries through
 * the owning module's public API.
 *
 * <p>See ADR-009 (single persistence approach) and the {@code database-conventions} rules ({@code
 * UUID} primary keys, {@code timestamptz}, {@code created_by}).
 */
public record WorkflowDefinition(
    UUID id,
    UUID tenantId,
    String name,
    String description,
    String status,
    int version,
    String createdBy,
    Instant createdAt) {

  /** The status a workflow definition has while it is being edited before activation. */
  public static final String DRAFT = "DRAFT";

  /** Construct a definition in the DRAFT state (the only non-terminal lifecycle state). */
  public static WorkflowDefinition draft(
      UUID tenantId, String name, String description, String createdBy) {
    return new WorkflowDefinition(
        UUID.randomUUID(), tenantId, name, description, DRAFT, 0, createdBy, Instant.now());
  }
}
