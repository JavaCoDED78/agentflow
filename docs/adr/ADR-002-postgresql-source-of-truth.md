# ADR-002. PostgreSQL is the source of truth for workflow state

- **Status:** Accepted
- **Date:** 2026-10-20
- **Task:** S02-06 (Sprint 2 — bounded contexts and automated architecture constraints)
- **Supersedes:** none. First of the core consistency ADRs (`ADR-002`…`ADR-004`, reserved in
  the plan §3). The formal cross-referencing ADR register is completed in S02-16.

## Context

AgentFlow runs three runtime roles — the REST API, the workflow worker, and the document
ingestion worker — against PostgreSQL, Kafka and Redis. The core deliverable is a durable
workflow engine that must survive a worker restart, tolerate duplicate message delivery, and
never lose a run because a cache or a broker went down. The plan (§2) fixes PostgreSQL 18 as
the primary database; `AGENTS.md` invariant 2 ("PostgreSQL is the source of truth") states
that losing Redis must not lose a run; `docs/domain-glossary.md` treats the outbox as the
DB-side write path.

Before durable execution can be trusted, the store of record for run/step state has to be
explicit. If Kafka (delivery) or Redis (cache) were treated as the store of record, a broker
restart, a consumer rebalance, or a cache eviction could silently lose, double-apply, or
resurrect a run — exactly the failure the durable engine exists to prevent.

## Decision

**PostgreSQL is the single source of truth for workflow state.** Every run/step state
transition is durably persisted, and the new state becomes effective only when that write
commits. State and any outbound event write share the transaction (see `ADR-004`); inbox
deduplication shares the transaction with the business change (see `ADR-003`).

Kafka delivers notifications and commands; Redis is used only for caching and short-lived
coordination. **Redis and Kafka are not dependencies for correctness:** losing either one
must not lose a run, duplicate a side effect without idempotency, or resurrect a terminal
run. A run recorded in PostgreSQL is recoverable after any worker, Kafka, or Redis outage.

## Alternatives considered

1. **Kafka as the store of record (event-log / read-model).** Pro: natural durability of the
   event log. Con: broker restart, a consumer crash before ack, or a rebalance can lose or
   reprocess state, and per-run reads require materialized read models — the opposite of
   "losing Kafka must not lose a run." Rejected.
2. **Redis as the store of record.** Pro: fast. Con: Redis is an ephemeral cache; a flush,
   restart, or failover loses all workflow state. Directly contradicts invariant 2. Rejected.
3. **PostgreSQL as source of truth, Kafka/Redis as delivery and cache (chosen).** Keeps the
   durable guarantee, stays simple, and matches the plan's stack choice.

## Consequences

- **Positive:**
  - A run that has been accepted is durable even if the worker, Kafka, or Redis is
    restarted — the core durable-execution guarantee.
  - Simple, well-understood consistency: one store of record, one lock ordering, no
    distributed two-phase commit for state.
  - Tenant isolation and optimistic locking live naturally on the PostgreSQL rows (invariant
    1), and the tenant filter is part of the query, not a post-filter.
- **Negative / operational:**
  - PostgreSQL is a single store of record, so HA, backups, and point-in-time recovery are
    required; record them in the deployment guide (S15) and runbooks.
  - Reads must not depend on a Redis cache hit for correctness: the cache is an optimization,
    never the authority. A cache miss recomputes; it never means "no run exists."
- **Follow-ups:**
  - The deployment guide (S15) records PostgreSQL HA/backup and the "Redis down" recovery
    path (cache miss ⇒ recompute, never treat as a missing run).
  - Kafka outage during create-run is handled in S06: the outbox commits first, and the
    publisher delivers later after Kafka returns.
