package com.example.agentflow.audit.domain;

/**
 * The resource type an {@link AuditEvent} refers to (glossary §10).
 *
 * <p>The action verb lives in {@link AuditAction}; this is the entity that was mutated. New
 * entities (a run, a document, an approval) add their own entry here.
 */
public enum AuditResourceType {
  WORKFLOW_DEFINITION
}
