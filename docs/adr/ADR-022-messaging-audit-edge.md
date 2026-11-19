# ADR-022. The DLQ inspection audit is written through a new `messaging -> audit` compile edge

- **Status:** Accepted
- **Date:** 2026-11-19
- **Task:** S12-11 (Sprint 12 — usage, budgets, audit catalogue, DLQ; S12-10 builds the DLQ store, S12-11 inspects it)
- **Supersedes:** none. Extends the audit-edge precedent (`ADR-010`, `ADR-018`) to the DLQ inspection surface.
- **Superseded by:** none

## Context

Inspecting a dead-letter-queue message is a security-sensitive action: the detail view exposes the
message's full failure record (metadata plus the redacted failure detail), and *who* inspected
*which* message must be recorded on the append-only audit trail (glossary §10, `ADR-010`,
`docs/security/audit-contract.md` §4.6). The inspection is owned by the `messaging` module — it owns
the `dlq_messages` table and the `GET /api/v1/ops/dlq` (and `{dlqId}`) endpoints (`S12-11`). The
plan is explicit that the inspection of a sensitive detail view leaves an audit entry: the
audit-contract §4.6 ops vocabulary records operator inspection actions, reusing the `READ` verb and
the `DLQ` resource type rather than introducing new ones.

The `messaging` module's allowed compile-dependency set is `{messaging, common}` (module-map arrows);
`audit` is not in it. Writing the audit row through `audit.application.AuditService` — the same
public port `workflowdefinition` uses (`ADR-010`) and `workflowexecution` uses (`ADR-018`) — therefore
needs a new compile edge `messaging -> audit`, which the module map forbids without an ADR (root
`AGENTS.md`).

## Decision

**Grant `messaging` a new compile edge to `audit`, recorded here, and record each sensitive DLQ
inspection through the public `audit.application.AuditService` port.** `DlqInspectionService#inspectDlqMessage`
resolves the row by id and then calls `AuditService.record(tenantId, actor, AuditAction.READ,
AuditResourceType.DLQ, dlqId, null)`; the operator's tenant and actor come from the trusted security
context, never from the request (invariant 1, `ADR-012`). The detail view is the only view that
carries the failure text — the list overview withholds it (invariant 7: documents and tool outputs
are untrusted content, and the DLQ stores only a pointer to the failed event, never the envelope
inline).

The edge is the narrowest possible: `messaging` reaches `audit` only through its public
`application`/`domain` types (`AuditService`, `AuditAction`, `AuditResourceType`), never
`audit.persistence` or the Kafka listeners. The new `AuditResourceType.DLQ` constant is the
operator resource family owned by `messaging`; the `READ` verb is reused for inspection (no new
verb), so the closed catalogue in `docs/security/audit-contract.md` stays closed except for the one
resource-type line that names the operator family.

## Alternatives considered

1. **Audit the inspection from a Kafka event the DLQ writes.** Rejected: there is no durable DLQ
   event emitted on inspection (inspecting is a read), and inventing an outbox event for a pure
   read would couple the audit to `messaging`'s own publish path and to delivery semantics the
   append-only trail does not need. The `READ` is a direct, synchronous `AuditService.record`.
2. **Record the inspection with structured logging only (no `audit_events` row).** Rejected: the
   trail is the durable, tamper-evident record the operator needs to answer "who opened which
   message"; a log line is not the append-only audit and is not queryable as one. The
   audit-contract §4.6 explicitly frames the operator inspection surface as an audit-trail action.
3. **Audit the inspection through `workflowdefinition` or another module that already depends on
   `audit`.** Rejected: no such module owns the DLQ; putting `messaging`'s inspection on a foreign
   edge smuggles a new responsibility into the wrong owner and couples two modules for no reason.
4. **Add the edge without an ADR.** Rejected: root `AGENTS.md` requires an ADR for every new
   module-to-module dependency edge; the architecture gate (`ArchitectureBoundaryTest`,
   `PackageDependencyGraphTest`) is updated in the same change so the map and the gate never
   disagree.

## Consequences

- **Positive:**
  - Each sensitive DLQ inspection leaves a durable `READ` entry on the `DLQ` resource (invariant 3);
    the audit-contract §4.6 ops vocabulary is wired end-to-end (code + contract).
  - `messaging` uses the same `AuditService` port as `workflowdefinition` (`ADR-010`) and
    `workflowexecution` (`ADR-018`), so the audit model stays uniform across modules.
- **Negative / operational:**
  - `messaging` gains a compile dependency on `audit`; the reach must stay narrow (public
    `application`/`domain` types only).
  - `AuditService` null-injects its repository when no `DataSource` is configured and fails loudly
    at call time; the inspection endpoint reports `503` before reaching it, exactly like the other
    audited flows (`S01-02`).
- **Follow-ups:**
  - `S12-12` adds the redrive path and may add an `AUDIT`/`REDRIVE` verb to the same `DLQ` family.
  - `S12-13` closes the remaining audit-catalogue wiring.

## When to write an ADR

A new dependency or trust edge between modules (respecting the no-cycle and module-ownership
rules) — exactly this case (root `AGENTS.md`; `docs/architecture/module-map.md`).
