package com.example.agentflow.audit.domain;

/**
 * The audited action verb for a {@code workflow_definition} mutation (glossary §10).
 *
 * <p>The value is the verb; the affected entity is carried separately by {@link AuditResourceType}.
 * This is the bounded vocabulary for the definition-mutation lifecycle that the {@code
 * workflowdefinition} module owns (versioning + activation, module-map business-rule ownership),
 * the tool-invocation verb the {@code toolexecution} module owns (S08-13: a side-effect tool call
 * is audited, read-only calls are not) and the approval-decision verb the {@code workflowexecution}
 * module owns (S11-04, {@code ADR-018}: a human-approval decision is a security-sensitive action,
 * audited in the same transaction as the decision). A new audited entity adds its own resource
 * type, not a new entry here.
 */
public enum AuditAction {
  CREATE,
  UPDATE,
  ACTIVATE,
  INVOKE,
  /**
   * A human-approval decision to let the gated side effect run (S11-04; reject/expiry land in
   * S11-05).
   */
  APPROVE,

  /** A human-approval decision to block the gated side effect (S11-05). */
  REJECT,

  /**
   * The approval request outlived its {@code expiry} before a decision and the expiry sweep blocked
   * the gated side effect (S11-05) — the system actor, never a human one.
   */
  EXPIRE
}
