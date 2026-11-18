package com.example.agentflow.audit.domain;

/**
 * The audited action verb (glossary §10, `audit_events.action`).
 *
 * <p>The verb lives here; the entity it refers to is carried separately by {@link
 * AuditResourceType}. The value is the verb; the affected entity is carried by the resource type,
 * so the same verb can refer to different resources (for example {@link #INVOKE} a {@link
 * AuditResourceType#TOOL}).
 *
 * <p>This is the complete, canonical vocabulary for the security- and business-sensitive actions
 * the audit catalogue enumerates
 * ([`docs/security/audit-contract.md`](../../../docs/security/audit-contract.md), `S12-07`): the
 * definition-mutation lifecycle the {@code workflowdefinition} module owns (versioning +
 * activation, {@link #CREATE} / {@link #UPDATE} / {@link #ACTIVATE}), the run lifecycle the {@code
 * workflowexecution} module owns ({@link #START} / {@link #CANCEL}), the document lifecycle the
 * {@code knowledgebase} module owns ({@link #UPLOAD} / {@link #READ}), the approval decision the
 * same module owns (human-approval {@link #APPROVE} / {@link #REJECT} / system {@link #EXPIRE},
 * S11-04 / S11-05 / `ADR-018`) and the tool authorization the {@code toolexecution} / {@code
 * agentcatalog} modules own — the side-effect {@link #INVOKE} (S08-13, read-only calls are not
 * audited) plus the {@link #ALLOW} / {@link #DENY} authorization decisions. A new audited entity
 * adds its own {@link AuditResourceType}, not a new verb here. Ops actions (the `S12-11`…`S12-13`
 * inspection/redrive surface) reuse the verbs of the resource they act on rather than introducing
 * an ops-specific verb.
 */
public enum AuditAction {
  CREATE,
  UPDATE,
  ACTIVATE,

  /** Start a run (`workflowexecution`). */
  START,

  /** Cancel a run (`workflowexecution`). */
  CANCEL,

  /** Upload a document (`knowledgebase`). */
  UPLOAD,

  /** Read / retrieve a document or its chunks (`knowledgebase`). */
  READ,

  /** The side-effect tool call itself — {@code WRITE} / {@code EXTERNAL_SIDE_EFFECT} (S08-13). */
  INVOKE,

  /**
   * An authorization {@code ALLOW} decision for a tool invocation (toolexecution / agentcatalog).
   */
  ALLOW,

  /**
   * An authorization {@code DENY} decision for a tool invocation (toolexecution / agentcatalog).
   */
  DENY,

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
