# Audit contract — the audit action catalogue

- **Status:** Accepted
- **Task:** `S12-07` (Sprint 12 — usage, budgets, audit catalogue, DLQ)
- **Supersedes:** none. Completes the audit-event sketch in glossary §10 and the `V3_3_5`
  migration comment into a fixed catalogue.
- **Superseded by:** none

> The audit trail is one of the platform's durability and accountability guarantees
> (`AGENTFLOW_ARCHITECTURE_AND_PLAN.md` §8.1; `AGENTS.md` invariants 3 and 8). This document is
> the **audit contract**: it fixes the vocabulary of audited actions, the mandatory fields every
> record must carry, the record schema, the redaction policy, and — honestly — which security- and
> business-sensitive actions already leave an entry and which are still pending. The code-side
> expression of the catalogue is the `AuditAction` / `AuditResourceType` / `AuditOutcome` enums
> (`audit.domain`); this document is the human-readable contract that names every entry and records
> its coverage.

## 1. Purpose

Every security- and business-sensitive action the platform performs is recorded in the append-only
`audit_events` table the `audit` module owns. This catalogue enumerates **all** such actions so that:

- a developer can see, in one place, exactly which actions are audited and which are not;
- a reviewer can verify the acceptance criterion — **every security-sensitive action leaves an
  `audit_events` row** — against a fixed list rather than a vague "audit the sensitive stuff";
- a follow-up task knows precisely which verbs/resources are left to wire, and to which module.

The catalogue is **canonical**: the `AuditAction` / `AuditResourceType` enums are the closed
vocabulary, so an audited action is always one of these rows and never free text.

## 2. Governing invariants

An audit record is only meaningful if it obeys the same invariants as the rest of the trail:

1. **Tenant isolation (invariant 1).** Every row is scoped to `tenant_id`; the tenant comes only
   from the trusted security context (HTTP) or the re-checked stored run (worker), never from the
   request body, query or Kafka headers. Another tenant's resource is indistinguishable from a
   missing one.
2. **Single owner, single write path (invariant 3).** `audit` owns `audit_events`; the only writer
   is `AuditService.record(...)`. No other module writes the table or its migration. The audit row
   joins the caller's transaction, so the state it records and the row commit (or roll back)
   together — audit can never diverge from the state.
3. **Append-only (invariant 3).** A re-check after a failure appends a corrected row; it never
   rewrites one. `outcome` is `SUCCEEDED` at the moment of the action; a later reconciliation may
   append a `FAILED` correction.
4. **No secrets, no full prompts (invariant 8).** `metadata_redacted` holds redacted free text
   (`tool=<name>:<version>`, `actor=<subject>`, …) — never a token, credential, API key, raw
   document, or full prompt. `metadata_redacted` is audit metadata, not a data store.
5. **High cardinality lives in traces, not labels (invariant 9).** `correlation_id` ties a row to
   its trace; it is a column, never a metric label. `tenant_id` / `run_id` / `document_id` are
   tenant-scoped values in the row, never metric labels.
6. **PostgreSQL is the source of truth (invariant 2).** Kafka delivers events; Redis caches. Losing
   Redis never loses a run or its audit row.

## 3. Mandatory fields

Every `audit_events` row carries these fields. Each is **mandatory** because it is what makes the
record attributable, isolatable, and replayable:

| Field | Type | Mandatory | Why it is mandatory |
|---|---|---|---|
| `id` | UUID (PK) | yes | Identity of the immutable row; enables dedup / replay on redelivery. |
| `tenant_id` | UUID (FK `tenants`) | yes | Tenant isolation — the row belongs to exactly one organization; a read is `(tenant_id, id)`. |
| `actor_subject` | TEXT | yes | **Who** performed the action. Never a token or credential — the verified principal's subject. |
| `action` | TEXT (`AuditAction`) | yes | **What** verb ran — the closed catalogue verb. |
| `resource_type` | TEXT (`AuditResourceType`) | yes | **Which** entity the action refers to — the closed catalogue resource type. |
| `resource_id` | UUID (nullable) | yes (value nullable) | The affected resource id; `NULL` when the action has no single resource (e.g. start a run family). |
| `outcome` | TEXT (`AuditOutcome`) | yes | `SUCCEEDED` at the moment of the action; a later reconciliation appends a `FAILED` correction. |
| `correlation_id` | UUID (nullable) | structural | Ties the row to the running trace for diagnosis; never in a metric label. |
| `metadata_redacted` | TEXT (nullable) | structural | Redacted free-text context (`tool=…`, `actor=…`); never raw arguments / payload / secrets. |
| `created_at` | TIMESTAMPTZ | yes | Immutable creation instant — the append-only log's ordering. |

The record type is `AuditEvent(tenantId, actorSubject, action, resourceType, resourceId, outcome,
correlationId, metadataRedacted, createdAt)` (`audit.domain`); the factory `AuditEvent.created(...)`
assigns `id`, `SUCCEEDED` and `createdAt`.

## 4. Canonical action catalogue

Grouped by the lifecycle each belongs to. **Coverage** column: `WIRED` (the code writes an entry
today), `CANON` (the verb/resource is in the catalogue but no call site exists yet). The `edge`
column names the audit compile edge the owner uses (`audit.application.AuditService`).

### 4.1 Workflow definition mutations — `workflowdefinition`

| Action | `AuditAction` | `AuditResourceType` | Coverage | Code reference | edge |
|---|---|---|---|---|---|
| Create definition | `CREATE` | `WORKFLOW_DEFINITION` | WIRED | `WorkflowDefinitionService.createDefinition` | `workflowdefinition → audit` (ADR-010) |
| Create version | `CREATE` | `WORKFLOW_DEFINITION` | WIRED | `WorkflowVersionService.createVersion` | `workflowdefinition → audit` (ADR-010) |
| Activate version | `ACTIVATE` | `WORKFLOW_DEFINITION` | WIRED | `WorkflowVersionService.activate` | `workflowdefinition → audit` (ADR-010) |
| Update definition | `UPDATE` | `WORKFLOW_DEFINITION` | CANON | — (verb defined) | `workflowdefinition → audit` (ADR-010) |

### 4.2 Run lifecycle — `workflowexecution`

| Action | `AuditAction` | `AuditResourceType` | Coverage | Code reference | edge |
|---|---|---|---|---|---|
| Start run | `START` | `RUN` | CANON | — (to wire in `RunService#startRun`) | `workflowexecution → audit` (ADR-018) |
| Cancel run | `CANCEL` | `RUN` | CANON | — (to wire in `RunService#cancel`) | `workflowexecution → audit` (ADR-018) |

The `workflowexecution → audit` edge already exists (ADR-018); wiring `START` / `CANCEL` reuses it,
no new ADR.

### 4.3 Document lifecycle — `knowledgebase`

| Action | `AuditAction` | `AuditResourceType` | Coverage | Code reference | edge |
|---|---|---|---|---|---|
| Upload document | `UPLOAD` | `DOCUMENT` | CANON | — (to wire in `DocumentService#upload`) | `knowledgebase → audit` (**new edge + ADR**) |
| Read / retrieve | `READ` | `DOCUMENT` | CANON | — (to wire in `RetrievalService` / `DocumentService#read`) | `knowledgebase → audit` (**new edge + ADR**) |

> **Scope note on `READ`.** A tenant reading its own document is tenant-isolated, not a privilege
> boundary, so auditing *every* read is high-volume / low-sensitivity. The catalogue records `READ`
> so the vocabulary is complete, but the decision to audit each read (vs. only uploads, or only
> deletes) is an operational one for the follow-up task; `UPLOAD` — a write boundary — is the
> security-sensitive half.

### 4.4 Tool authorization — `toolexecution` / `agentcatalog`

| Action | `AuditAction` | `AuditResourceType` | Coverage | Code reference | edge |
|---|---|---|---|---|---|
| Invoke side-effect tool | `INVOKE` | `TOOL` | WIRED | `ToolInvocationHistoryService` (non-`READ_ONLY` started calls, S08-13) | `toolexecution → audit` (edge 4) |
| Allow decision | `ALLOW` | `TOOL` | CANON | — (to wire where `ToolInvocationPolicy` decision is applied) | `toolexecution → audit` (edge 4) |
| Deny decision | `DENY` | `TOOL` | CANON | — (to wire where `ToolInvocationPolicy` decision is applied) | `toolexecution → audit` (edge 4) |

Read-only tools (`READ_ONLY`) are **not** audited — they change nothing, so auditing them would
only grow the trail with noise (S08-13). `INVOKE` covers `WRITE` / `EXTERNAL_SIDE_EFFECT` calls
that were started (`SUCCEEDED` / ambiguous `TIMED_OUT` / `FAULTED`); a `THROTTLED` call never
started, so there is nothing to audit.

### 4.5 Approval decisions — `workflowexecution`

| Action | `AuditAction` | `AuditResourceType` | Coverage | Code reference | edge |
|---|---|---|---|---|---|
| Approve | `APPROVE` | `APPROVAL` | WIRED | `ApprovalService#approve` (in the decision transaction, ADR-018) | `workflowexecution → audit` (ADR-018) |
| Reject | `REJECT` | `APPROVAL` | WIRED | `ApprovalService#reject` | `workflowexecution → audit` (ADR-018) |
| Expire | `EXPIRE` | `APPROVAL` | WIRED | `ApprovalService#expireOverdue` (system actor `agentflow-approval-expiry`) | `workflowexecution → audit` (ADR-018) |

The decision and its audit row commit atomically (invariant 3); the redacted metadata carries only
`step=<key>`, never the payload or the decision comment (invariant 8).

### 4.6 Ops actions — `messaging` / ops surface (S12-11 … S12-13)

The DLQ inspection / controlled redrive / admin surface (S12-11 … S12-13) is operator-scoped and
audited. Rather than introduce an ops-specific verb, those actions **reuse the verbs of the
resource they act on** (e.g. redriving a `workflow_events` record is a `START`/`INVOKE` on `RUN`;
inspecting a document is a `READ` on `DOCUMENT`) with an operator actor subject and the inspected
`resource_id`. The ops audit vocabulary is therefore a subset of the verbs above, not a new entry.

## 5. Coverage summary

| Category | Wired actions | Pending |
|---|---|---|
| Definition mutations | `CREATE` (definition + version), `ACTIVATE` | `UPDATE` |
| Run lifecycle | — | `START`, `CANCEL` |
| Document lifecycle | — | `UPLOAD`, `READ` |
| Tool authorization | `INVOKE` | `ALLOW`, `DENY` |
| Approval decisions | `APPROVE`, `REJECT`, `EXPIRE` | — |
| Ops actions | (reuse) | wiring in S12-11 … S12-13 |

The acceptance criterion — *every security-sensitive action leaves an `audit_events` row* — is fully
met for **definition mutations, tool side-effect invocation and approval decisions** today. The
pending verbs (`UPDATE`, `START`, `CANCEL`, `UPLOAD`, `READ`, `ALLOW`, `DENY`) are recorded in this
catalogue so the remaining wiring is explicit; `START` / `CANCEL` / `ALLOW` / `DENY` reuse existing
audit edges, and `UPLOAD` / `READ` need a new `knowledgebase → audit` edge (an ADR).

## 6. Redaction policy

- `metadata_redacted` may name the **structural identity** of what ran (`tool=<name>:<version>`,
  `step=<key>`, `actor=<subject>`), but never a token, API key, provider header, raw document text,
  full prompt, or unredacted argument map.
- The actor is the verified principal's subject, never a JWT / bearer token.
- Redaction is applied by the caller before `record(...)`, not inside `AuditService` — the owner of
  the action knows what is sensitive; `audit` stores only what it is given (the centralized
  redaction utility, `S12-09`, is the tool that standardizes this).

## 7. References

- Glossary §10 "Audit event" — [`docs/domain-glossary.md`](../domain-glossary.md).
- Migration (owner `audit`, forward-only): [`V3_3_5__create_audit_events.sql`](
  ../apps/agentflow-app/src/main/resources/migrations/V3_3_5__create_audit_events.sql).
- ADR-010 (definition → audit edge), ADR-018 (approval → audit edge).
- Module API — [`docs/architecture/module-api.md`](architecture/module-api.md) (`audit` section);
  module map — [`docs/architecture/module-map.md`](architecture/module-map.md).
- Agent-execution security guide (who audits what, and why): [`docs/security/agent-actions.md`](agent-actions.md).
- Code: `AuditAction` / `AuditResourceType` / `AuditOutcome` (`audit.domain`), `AuditEvent`,
  `AuditService` (`audit.application`).
