package com.example.agentflow.workflowdefinition.persistence;

import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import java.time.Instant;
import java.util.UUID;

/**
 * The persistence object for a single {@code workflow_definition_versions} row (S03-09).
 *
 * <p>This is the <em>persistence object</em>: the shape the persistence layer reads from and writes
 * to the database, distinct from any API DTO (the client-facing representation is a separate type
 * introduced with the create/activate endpoints, S03-13). It holds the owning module's {@link
 * WorkflowGraph domain model} alongside the row's identity and activation metadata.
 *
 * <p>Tenant isolation is <em>not</em> stored here — it flows through the parent {@code
 * workflow_definitions.tenant_id} (ADR-007), matching the migration.
 */
public record WorkflowVersion(
    UUID id,
    UUID workflowId,
    Integer version,
    WorkflowGraph graph,
    String checksum,
    String status,
    String createdBy,
    Instant createdAt) {}
