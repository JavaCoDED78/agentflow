# ADR-018. The approval decision is audited through a new `workflowexecution -> audit` compile edge

- **Status:** Accepted
- **Date:** 2026-11-14
- **Task:** S11-04 (Sprint 11 — human approval, MCP and security of agent actions)
- **Supersedes:** none. Extends the audit-edge precedent (`ADR-010`) to the approval lifecycle.
- **Superseded by:** none

## Context

A human-approval decision is a security-sensitive action: it authorizes a gated side effect
(`WRITE` / `EXTERNAL_SIDE_EFFECT`, `ADR-017`), so *who* decided *what*, *when* must be recorded in
the append-only audit trail (glossary §10, `ADR-010`). The decision is written by the
`workflowexecution` module — it owns the `approvals` table, the run/step state machine and the
`POST /api/v1/runs/{runId}/approvals/{approvalId}` endpoint (`S11-04`). The plan is explicit that
the decision and the audit record commit atomically: "записывать decision/audit атомарно" — the
`approvals` row transition and the `audit_events` row are one durable fact, so an audit record can
never diverge from the decision it describes (invariant 3).

The `workflowexecution` module's allowed compile-dependency set is `{workflowexecution,
workflowdefinition, messaging}` (module-map arrows 12-13); `audit` is not in it. Writing the audit
row through `audit.application.AuditService` — the same public port `workflowdefinition` already
uses (`ADR-010`) and `toolexecution` uses for tool invocations — therefore needs a new compile edge
`workflowexecution -> audit`, which the module map forbids without an ADR (root `AGENTS.md`).

## Decision

**Grant `workflowexecution` a new compile edge to `audit`, recorded here, and record the approval
decision through the public `audit.application.AuditService` port in the same transaction as the
decision write.** `ApprovalService#approve` holds a single `@Transactional` boundary: the
conditional `approvals` update (`status PENDING -> APPROVED`, optimistic-lock `version` guard) and
the `AuditService.record(...)` append join the caller's transaction, so the decision and its audit
row commit together or roll back together (invariant 3). The audit row carries the actor (never a
token), the `APPROVE` verb, the `APPROVAL` resource type and the approval id; free-text decision
comments are *not* copied into the audit metadata — only the redacted `step=<key>` fact
(invariant 8).

The edge is the narrowest possible: `workflowexecution` reaches `audit` only through its public
`application`/`domain` types (`AuditService`, `AuditAction`, `AuditResourceType`), never
`audit.persistence` or the Kafka listeners.

## Alternatives considered

1. **Audit the decision from the `audit` module itself (a listener or trigger on `approvals`).**
   Rejected: there is no event for the decision in `S11-04` (the resume/continuation outbox event
   is `S11-05`), and a database trigger would hide the audit author inside the schema, outside the
   module-ownership and transaction conventions.
2. **Record the decision first and audit "afterwards, best effort".** Rejected: the plan requires
   the atomic write; a crash between the two writes would leave a decided approval without its
   audit row — exactly the gap the append-only trail exists to close.
3. **Route the audit through `workflowdefinition` or `toolexecution`** (which already depend on
   `audit`). Rejected: neither module owns the approval lifecycle; putting a foreign module's
   audit on their edge would smuggle a new responsibility into the wrong owner and couples two
   modules for no reason.
4. **Add the edge without an ADR.** Rejected: root `AGENTS.md` requires an ADR for every new
   module-to-module dependency edge; the architecture gate (`ArchitectureBoundaryTest`,
   `PackageDependencyGraphTest`) is updated in the same change so the map and the gate never
   disagree.

## Consequences

- **Positive:**
  - The approval decision and its audit row are one transactional fact (invariant 3); the S11-04
    acceptance "unauthorized user / another tenant cannot approve a step" is backed by an
    immutable record of every *successful* decision.
  - `workflowexecution` uses the same `AuditService` port as `workflowdefinition` (`ADR-010`) and
    `toolexecution`, so the audit model stays uniform across modules.
- **Negative / operational:**
  - `workflowexecution` gains a compile dependency on `audit`; the reach must stay narrow (public
    `application`/`domain` types only).
  - `AuditService` null-injects its repository when no `DataSource` is configured and fails loudly
    at call time; the approval endpoint reports `503` before reaching it, exactly like the other
    audited flows (S01-02).
- **Follow-ups:**
  - `S11-05` adds `reject` and the expiry lifecycle to the same service/endpoint and reuses this
    edge for the `REJECT`/`EXPIRE` audit verbs.
  - The run-state resume after a decision (outbox continuation) is `S11-05`; this ADR covers only
    the decision + audit write.

## When to write an ADR

A new dependency or trust edge between modules (respecting the no-cycle and module-ownership
rules) — exactly this case (root `AGENTS.md`; `docs/architecture/module-map.md`).
