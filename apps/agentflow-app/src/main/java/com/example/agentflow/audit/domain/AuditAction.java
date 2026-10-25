package com.example.agentflow.audit.domain;

/**
 * The audited action verb for a {@code workflow_definition} mutation (glossary §10).
 *
 * <p>The value is the verb; the affected entity is carried separately by {@link AuditResourceType}.
 * This is the bounded vocabulary for the definition-mutation lifecycle that the {@code
 * workflowdefinition} module owns (versioning + activation, module-map business-rule ownership). A
 * new audited entity adds its own resource type, not a new entry here.
 */
public enum AuditAction {
  CREATE,
  UPDATE,
  ACTIVATE
}
