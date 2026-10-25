# ADR-010. Audit dependency from workflowdefinition to audit

- **Status:** Proposed
- **Date:** 2026-10-25
- **Task:** S04-12 (Sprint 4 — audit on workflow definition mutations)
- **Supersedes:** none
- **Superseded by:** none

## Context

The plan (§6, glossary §10) fixes audit as an append-only, tenant-scoped trail of state
changes: every write of durable domain state must produce one `audit_events` row recording
who changed what, in the right transaction (`AGENTS.md` invariants 3 and 4). The `audit`
module owns the `audit_events` table and publishes a public API (`AuditService.record`,
per `docs/architecture/module-api.md`), and it already owns the only audit edge today:
`toolexecution → audit` (module-map edge 4).

`workflowdefinition` creates definitions (`S04-01`…`S04-02`) but records nothing today, so a
definition created through `WorkflowDefinitionController` leaves no trace in the trail the
invariant demands. Writing the row inside `workflowdefinition` would violate module
ownership (the table and its SQL are `audit`'s), and the dependency rules forbid importing a
module's persistence/internal packages — only its root public API. The single audit API
exists precisely so that non-`audit` modules can record through it without owning audit data.

## Decision

**Record definition mutations through the `audit` public API**
(`AuditService.record`), adding one new module edge `workflowdefinition → audit`
(`workflowdefinition` depends only on `audit`'s root package — the `AuditService` port and the
`AuditEvent`/`AuditAction`/`AuditResourceType` value types). The new edge mirrors the existing
`toolexecution → audit` edge:

- The write is **transactional** (the audit insert runs in the same commit as the definition
  insert) rather than async — a definition is never created without its audit trail, and a
  failed audit write rolls the definition back with it. This satisfies invariant 3 directly for
  this mutation. An outbox variant (`AuditService.recordOutbox`) is available for the same API if
  asynchronous delivery is later preferred.
- The recorded event links only the caller's `actor` subject — **no credential, token, or secret
  is carried** (`metadata_redacted` holds the redacted text, never the raw value), per the
  migration `V3_3_5` and the "no secrets" invariant.

This new edge is recorded here because a new cross-module trust edge changes the dependency
graph and module-ownership surface (module-map edge 4 + the no-cycle rule); the `audit` module
remains the sole owner of `audit_events`.

## Alternatives considered

1. **Record through the public `AuditService` port, new edge `workflowdefinition → audit` (chosen).**
   Pro: keeps `audit_events` owned by `audit`, no data duplication, satisfies invariant 3 for
   this mutation, and reuses the one API all non-`audit` modules use.
2. **`workflowdefinition` writes `audit_events` directly (own the table / own the row).** Con:
   violates module ownership (the table and its SQL belong to `audit`), breaks the "one owner
   per table" rule, and would need a second inserter duplicating the schema. Rejected.
3. **Publish an `audit_event` to Kafka from `workflowdefinition` (async, outbox).** Con: adds
   delivery/retry/dedup complexity for a mutation the invariant already makes transactional;
   only justified if audit writes become high-volume and need backpressure. Rejected for S04-12.

## Consequences

- **Positive:** every definition create now produces exactly one audit event, linked to the
  calling actor without a credential; `audit_events` stays owned by `audit`.
- **Negative / operational:**
  - The definition create is now **transactional**, not fire-and-forget: a transient DB failure
    on the audit write fails the definition create itself (they commit together). This is the
    intended trade-off (invariant 3) — audit loss is worse than a failed create — but it means a
    durable-audit outage is a create outage until S16-18.
  - One more cross-module edge to keep in the architecture tests (`ArchitectureBoundaryTest.ALLOWED`
    + `NoCyclicModuleDependenciesTest`) and the module-map.
- **Follow-ups:**
  - S04-13…S04-15 audit the other definition mutations (update/activate) through the same API.
  - S16-18 adds the read/write API and reports over `audit_events`.
  - Consider whether `metadata_redacted` should capture the definition's version, per the
    `V3_3_5` `metadata_redacted` column.
