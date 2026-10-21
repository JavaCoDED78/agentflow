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
| [`ADR-002`](ADR-002-postgresql-source-of-truth.md) | PostgreSQL is the source of truth for workflow state | 2026-10-20 | S02-06 | Accepted |
| [`ADR-003`](ADR-003-at-least-once-idempotency.md) | At-least-once delivery + idempotency | 2026-10-20 | S02-06 | Accepted |
| [`ADR-004`](ADR-004-transactional-outbox.md) | Transactional outbox between PostgreSQL and Kafka | 2026-10-20 | S02-06 | Accepted |
| — | S3-compatible object storage: Garage for the local stack | 2026-10-18 | S01-15 | Accepted |

### Template

New ADRs start from [`000-template.md`](000-template.md): the header metadata
(`Status`, `Date`, `Task`, `Supersedes`), the `Context` → `Decision` →
`Alternatives considered` → `Consequences` sections, the rules for *when an ADR
is needed*, and the copy-paste register steps. See the pitfall in `docs/adr/`
about one ADR per referenced number.

Scaffolded in **S01-03**. The first core ADR (`ADR-001 Modular monolith first`)
is added in **S02-05**; the remaining core ADRs (consistency/outbox, at-least-once,
tenant isolation, …) follow in **S02-06 – S02-08**.
