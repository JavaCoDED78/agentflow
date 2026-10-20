# ADR-004. Transactional outbox between PostgreSQL and Kafka

- **Status:** Accepted
- **Date:** 2026-10-20
- **Task:** S02-06 (Sprint 2 — bounded contexts and automated architecture constraints)
- **Supersedes:** none. Part of the consistency model (`ADR-002`…`ADR-004`); the formal
  cross-referencing ADR register is completed in S02-16.

## Context

The API records a run and must publish "run requested" to the worker; the worker records a
step result and must publish the next event. The two writes — persist state and notify — must
not be able to diverge: if state commits but the event is never published, a run stalls
forever; if the event is published but state never commits, a worker acts on a run that does
not exist. The plan (§1, §6) fixes a transactional outbox as the mechanism and forbids a
shared PostgreSQL/Kafka transaction; `AGENTS.md` invariant 3 requires state and outbox to
write in one transaction with a separate idempotent publisher.

A shared database–Kafka transaction (XA / two-phase commit) would couple the two systems and
make Kafka part of the state commit — directly conflicting with PostgreSQL being the source of
truth (`ADR-002`) and with "Kafka delivers, Redis caches; PostgreSQL is the source of truth."

## Decision

**Persist the domain state and the outbox event in the same PostgreSQL transaction**
(`outbox_events`, published idempotently by `event_id`). A separate outbox publisher polls
for unpublished rows, publishes to Kafka, and marks each row published; a second publish of
the same row is a no-op, so publisher restarts and retries are safe.

Because state and event share one commit, they cannot diverge: **a run is never accepted
without its outbox event, and an event is never published without durable state.** The
publisher is a long-running polling process (CDC/Debezium is explicitly deferred as a later
experiment, not a requirement).

Kafka is **not** part of the state commit. A Kafka outage delays publication but never blocks
the durable state write, and delivery stays at-least-once (`ADR-003`) with inbox dedup at the
consumer. The outbox therefore enforces `ADR-002` (PostgreSQL source of truth) and
`ADR-003` (at-least-once + idempotency) together.

## Alternatives considered

1. **Transactional outbox + polling publisher (chosen).** Pro: PostgreSQL stays the source of
   truth, no XA/2PC coupling, the publisher is a stateless poller that is safe under
   restarts/retries/duplicate publishes, and delivery honestly stays at-least-once.
2. **Shared PostgreSQL/Kafka transaction (XA / 2PC / transactional producer + exactly-once
   source).** Con: makes Kafka part of the state commit, couples and slows the critical
   write, and still does not make the external LLM/tool call exactly-once. Rejected —
   conflicts with `ADR-002` and the "never promise exactly-once" invariant.
3. **Publish directly to Kafka from the request handler (no outbox).** Con: the "state
   committed, publish failed" gap leaves a run stuck with no event forever. Rejected.
4. **CDC/Debezium on the state tables.** Con: powerful, but a separate infrastructure
   experiment; defer until the outbox works. Not required for the guarantee.

## Consequences

- **Positive:**
  - No lost and no orphaned events at the state/event boundary; the create-run transaction
    durability invariant (plan §6) holds.
  - The publisher is a stateless poller, safe under restarts, retries, and duplicate
    publishes.
  - A Kafka outage does not block durable state; delivery stays at-least-once.
- **Negative / operational:**
  - Polling adds publication latency and a poll load to budget; measure both (S06-15) and
    define the optional CDC migration gate against real numbers, not before.
  - The publisher must be highly available (run it in every worker role, or as a dedicated
    deployment) — a single dead poller stalls publication, which is an operational alert
    (outbox age), per S13.
- **Follow-ups:**
  - S06-07 builds the polling publisher; S06-15 measures its latency and load and defines the
    (optional) CDC migration gate.
  - The observability alerts (S13-11) cover outbox age and consumer lag.
