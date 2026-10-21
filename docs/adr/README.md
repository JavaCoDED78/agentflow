# `docs/adr/` — architecture decision records

Architecture decision records (ADRs) follow the Context → Decision → Alternatives
→ Consequences → Status pattern. Statuses are `Proposed`, `Accepted`,
`Superseded`, `Deprecated`. A superseded ADR is not deleted; it links to its
successor. The full cross-referencing register is completed in **S02-16**; each
ADR below links to its record.

This folder is the **ADR register** (`docs/adr/README.md`) plus one file per
decision. The register is the single source of truth for *which decisions exist,
what status each has, and how they relate*; the individual ADR files hold the
reasoning. The architecture index (`docs/architecture/README.md`) links back from
each standing constraint to its ADR, so a reader travels between the two.

## ADR register

| ID | Title | Date | Task | Status |
|---|---|---|---|---|
| [`ADR-001`](ADR-001-modular-monolith.md) | Modular monolith first | 2026-10-20 | S02-05 | Accepted |
| [`ADR-002`](ADR-002-postgresql-source-of-truth.md) | PostgreSQL is the source of truth for workflow state | 2026-10-20 | S02-06 | Accepted |
| [`ADR-003`](ADR-003-at-least-once-idempotency.md) | At-least-once delivery + idempotency | 2026-10-20 | S02-06 | Accepted |
| [`ADR-004`](ADR-004-transactional-outbox.md) | Transactional outbox between PostgreSQL and Kafka | 2026-10-20 | S02-06 | Accepted |
| — | S3-compatible object storage: Garage for the local stack | 2026-10-18 | S01-15 | Accepted |

## Statuses

Each ADR carries one of the four statuses in its header (and in the register
table). The status is the only field that changes without a new ADR *file* — the
lifecycle below governs how and when it changes.

- **Proposed.** The decision has been drafted and linked from a task but has not
  yet been reviewed and accepted. A `Proposed` ADR is a candidate; its decision
  must not yet be cited as architecture in the constraints list. Real ADRs should
  not stay `Proposed` for long — review quickly.
- **Accepted.** The decision is ratified and in force. Accepted ADRs are the ones
  referenced from the architecture index's *Current architectural constraints*
  and enforced by the architecture tests. This is the normal state for a settled
  core decision.
- **Superseded.** A later ADR made this one obsolete. The file is **not deleted**
  and keeps its original history; it records `Supersedes` (what it replaced) in
  its header and is linked *from* the successor's `Supersedes` field. Use it for a
  settled decision that was later replaced by a clearly better one (for example a
  first cut, then corrected). The register marks it `Superseded` with the
  successor's ID in the title cell.
- **Deprecated.** The decision is no longer recommended and is on a path to be
  retired, but there is no clean successor yet. Between `Deprecated` and
  `Superseded`: `Superseded` names a concrete successor; `Deprecated` means no
  successor exists yet and the change is tracked separately. Never delete a
  `Deprecated` ADR.

## Lifecycle

```text
Proposed ──review──▶ Accepted ──later decision──▶ Superseded
                          │
                          └─────────────────────────▶ Deprecated (retire path)
```

1. **Create.** Copy [`000-template.md`](000-template.md) to
   `docs/adr/ADR-NNN-<slug>.md` with the next free `NNN` (never reuse a number).
   Set **Status** to `Proposed`. An ADR is required for a decision with lasting,
   cross-module consequences — a new inter-module dependency, bypassing the BOM,
   a persistence/migration change, a new workflow node type or tool risk class, or
   relaxing an invariant (see the template's *When to write an ADR*).
2. **Accept.** After review, set **Status** to `Accepted` and add a row to this
   register (ID, title, date, task, status). Only an `Accepted` ADR may be cited
   from the architecture index's constraints.
3. **Supersede.** Never edit an accepted ADR in place. Add a **new** ADR whose
   `Supersedes` points at the old one; set the old ADR's **Status** to
   `Superseded` and its `Superseded by` to the successor. Keep both files.
4. **Deprecated.** When a decision is no longer recommended but has no clean
   successor, set **Status** to `Deprecated` and note the retire path; do not
   delete it.

Every status change is recorded in this register and, where the ADR is a standing
constraint, in the architecture index — in the same PR as the ADR that caused it.

## Marking superseded decisions

A superseded ADR is a historical record, not a lost one. To mark one:

1. Create the successor ADR (`ADR-NNN-<slug>.md`) and record in its header
   `Supersedes: ADR-0XX`. Set it to `Accepted` once reviewed.
2. In the superseded ADR (`ADR-0YY`), set **Status** to `Superseded`, add
   `Superseded by: ADR-NNN`, and point any prominent references at the successor
   while keeping the old body for context.
3. In this register, change the row's **Status** to `Superseded` and add
   `→ ADR-NNN` to the title cell so a reader sees the replacement at a glance.
4. Update the architecture index's constraint list if the old ADR is no longer a
   standing constraint — it moves out of *Current architectural constraints*.

The invariant is simple: an accepted ADR is never rewritten, and a superseded
record always resolves to its successor.

### Template

New ADRs start from [`000-template.md`](000-template.md): the header metadata
(`Status`, `Date`, `Task`, `Supersedes`), the `Context` → `Decision` →
`Alternatives considered` → `Consequences` sections, the rules for *when an ADR
is needed*, and the copy-paste register steps. See the pitfall in `docs/adr/`
about one ADR per referenced number.

Scaffolded in **S01-03**. The first core ADR (`ADR-001 Modular monolith first`)
is added in **S02-05**; the remaining core ADRs (consistency/outbox, at-least-once,
tenant isolation, …) follow in **S02-06 – S02-08**.
