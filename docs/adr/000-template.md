# ADR-XXX. <short title>

> Copy this file to `docs/adr/ADR-NNN-<slug>.md`, fill every placeholder, then delete
> this heading. The real ADRs (`ADR-001`…`ADR-007` in the plan §3) are the reference
> to match. Every section is mandatory; delete a section only if it genuinely has no
> content and say why on one line.

- **Status:** Proposed <!-- `Proposed` until reviewed, then `Accepted`. Never leave a real ADR at `Proposed` for long. -->
- **Date:** YYYY-MM-DD
- **Task:** S02-12 (Sprint 2 — bounded contexts and automated architecture constraints)
- **Supersedes:** none <!-- ADRs this record replaces, e.g. `ADR-001`. An ADR is never edited in place — a new ADR supersedes the old one. -->
- **Superseded by:** none <!-- If this is superseded, link the successor here and keep the file. -->

## Context

> What problem are we solving, and what forces constrain the answer? Describe the
> situation, the stakeholders, the hard requirements and the invariants this
> decision must respect (tenant isolation, PostgreSQL as source of truth,
> at-least-once + idempotency, … — see `AGENTS.md`). Do **not** describe the decision
> yet. A reader six months from now must understand *why* this decision had to exist.

## Decision

> What we decided. State it as one firm, atomic decision. Reference the ADRs and
> invariants it satisfies (e.g. `ADR-002` … `ADR-004`). Keep it short enough to quote
> in a review; the reasoning lives in *Context* and *Alternatives*.

## Alternatives considered

> The options that were genuinely evaluated — not a strawman. For each: one line on what
> it trades away and why it was accepted or rejected. A decision with no alternatives is a
> preference, not a decision.

## Consequences

- **Positive:** the upside the decision buys — ideally tied to an invariant or a prior ADR.
- **Negative / operational:** the cost, the failure modes, the load/ops burden, and how it is mitigated.
- **Follow-ups:** what this decision unlocks or forces later (tied to a task or a measured condition, not done preemptively).

## When to write an ADR

An ADR is required for a decision with lasting, cross-module consequences — or one that
changes consistency, security, persistence, API/event contracts, or deployment topology.
Concretely, an ADR is needed for:

- a new dependency or trust edge between modules (respecting the no-cycle and module-ownership rules);
- bypassing the dependency BOM;
- changing the persistence or migration approach;
- a new workflow node type or tool risk class;
- relaxing or abandoning an invariant.

Routine local implementation details (a single method, a field name, a test setup) are
**not** an ADR. See the plan §3 ADR inventory and `docs/README.md` for the full register.

## How to register

1. Copy this file to `docs/adr/ADR-NNN-<slug>.md`; replace `ADR-XXX` and the title.
2. Use the next free `NNN` from the plan §3 inventory (`ADR-001`…`ADR-007` are reserved, so
   the next free id is `ADR-008`). Never reuse a number.
3. Set **Status** to `Proposed`, then `Accepted` once reviewed.
4. Add a row to [`docs/adr/README.md`](README.md): ID, title, date, task, status.
5. Link the ADR from the architecture index (`docs/README.md`).
6. Run `git diff --check`; commit with the ADR in the message.
