# `docs/adr/` — architecture decision records

Architecture decision records (ADRs) follow the Context → Decision → Alternatives
→ Consequences → Status pattern. Statuses are `Proposed`, `Accepted`,
`Superseded`, `Deprecated`. A superseded ADR is not deleted; it links to its
successor. The full cross-referencing register is completed in **S02-16**; each
ADR below links to its record.

## ADR register

| ID | Title | Date | Task | Status |
|---|---|---|---|---|
| [`ADR-001`](ADR-001-modular-monolith.md) | Modular monolith first | 2026-10-20 | S02-05 | Accepted |
| — | S3-compatible object storage: Garage for the local stack | 2026-10-18 | S01-15 | Accepted |

Scaffolded in **S01-03**. The first core ADR (`ADR-001 Modular monolith first`)
is added in **S02-05**; the remaining core ADRs (consistency/outbox, at-least-once,
tenant isolation, …) follow in **S02-06 – S02-08**.
