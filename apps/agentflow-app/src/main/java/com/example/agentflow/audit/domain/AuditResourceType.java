package com.example.agentflow.audit.domain;

/**
 * The resource type an {@link AuditEvent} refers to (glossary §10).
 *
 * <p>The action verb lives in {@link AuditAction}; this is the entity the action refers to. Each
 * security- or business-sensitive resource the audit catalogue enumerates has its own entry — a
 * run, a document, an approval, a tool, a workflow definition — so the resource a row refers to is
 * never free text. The complete, canonical vocabulary is fixed by the audit action catalogue
 * ([`docs/security/audit-contract.md`](../../../docs/security/audit-contract.md), `S12-07`); this
 * enum is the code-side expression of that catalogue. New entities add their own entry here.
 *
 * <p>Ops actions (the later `S12-11`…`S12-13` inspection/redrive surface) are an operator resource
 * family; they reuse a resource type from the module that owns the inspected resource rather than a
 * dedicated constant, keeping the vocabulary closed.
 */
public enum AuditResourceType {
  /** A workflow definition or its versioned graph (`workflowdefinition`). */
  WORKFLOW_DEFINITION,

  /** A run / step (`workflowexecution`). */
  RUN,

  /** A document / knowledge base entry (`knowledgebase`). */
  DOCUMENT,

  /** A human-approval record from the {@code approvals} table (S11-04). */
  APPROVAL,

  /** A registered tool (`toolexecution` / `agentcatalog`). */
  TOOL
}
