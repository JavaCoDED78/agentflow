# ADR-003. At-least-once delivery plus idempotency

- **Status:** Accepted
- **Date:** 2026-10-20
- **Task:** S02-06 (Sprint 2 — bounded contexts and automated architecture constraints)
- **Supersedes:** none. Part of the consistency model (`ADR-002`…`ADR-004`); the formal
  cross-referencing ADR register is completed in S02-16.

## Context

Messages between the API/outbox and the workers travel through Kafka. Like any distributed
transport, Kafka can deliver the same message more than once: a retry after a broker or
network error, a consumer crash before ack, or a rebalance. The durable engine must make
re-delivery safe. The plan (§1.3, §6) explicitly forbids an end-to-end exactly-once promise
and requires at-least-once delivery plus idempotent operations; `AGENTS.md` invariant 3
("at-least-once + idempotency") and invariant 6 ("an external call is not exactly-once")
encode this; `docs/domain-glossary.md` pins the guarantee as "at-least-once delivery with
idempotency (`ADR-003`)".

If delivery were assumed exactly-once, a duplicate message could re-run a side effect — a
double tool call, a double human approval, or a double usage/billing charge — a correctness
and billing bug, and a security exposure (an untrusted path applying a side effect twice).

## Decision

**Delivery is at-least-once only.** Consumers deduplicate with `event_id` / `idempotencyKey`
against `inbox_messages`; a business change and its inbox record are written in the **same
transaction** (inbox dedup in the transaction), so a duplicate message cannot change business
state twice. State transitions are guarded by optimistic locking and conditional updates
(`workflow_events` recorded in the same transaction). The outbox publish is idempotent by
`event_id` (`outbox_events`), so a duplicate or repeated publish is a no-op, not an error —
which is exactly why publisher restarts and retries are safe.

An **external LLM/tool call is never exactly-once.** A call that runs and then crashes before
the outcome is recorded may be retried; this is honest and visible, not hidden. The attempt is
persisted before the external call, and an ambiguous outcome is marked in usage/history and
the run/step record rather than being treated as a clean success. Because a call may repeat,
a workflow carries a cost budget and an attempt log (invariant 6).

## Alternatives considered

1. **Exactly-once semantics** (transactional Kafka producer + exactly-once source). Pro:
   fewer duplicate-handling concerns. Con: requires transactional producers, barriers, and
   strict ordering, and — critically — it still does **not** make an external LLM/tool call
   exactly-once: the boundary is outside Kafka. Rejected as over-engineering that does not
   reach the goal and that conflicts with "never promise exactly-once."
2. **At-least-once with idempotency (chosen).** Pro: works with any broker, no
   transactional-producer requirement, honest about the external-call boundary, and directly
   testable (duplicate-event and crash-after-call paths).
3. **At-least-once without idempotency.** Con: duplicates cause real, observable side effects
   (double charges, double approvals). Rejected.

## Consequences

- **Positive:**
  - Duplicate Kafka delivery, a consumer crash mid-processing, and a rebalance are all safe —
    the mandatory failure tests (duplicate event, worker crash after a model call) are
    covered by design.
  - Honest about external calls: usage, budget, and audit reflect what actually happened.
- **Negative / operational:**
  - Every consumer must implement dedup against `inbox_messages` (`(consumer_name, event_id)`
    primary key, plan §6); the table must exist before workers process messages.
  - Idempotency keys must be stable and tenant-scoped and must never be derived from
    untrusted input.
  - Repeated calls can repeat side effects, so external side effects must be made idempotent
    or guarded where possible (e.g. idempotency keys on the tool/external call itself).
- **Follow-ups:**
  - S06 builds the outbox (`outbox_events`) and inbox (`inbox_messages`) tables, the polling
    publisher, and the inbox dedup helper; S06 failure tests cover duplicate delivery and
    crash-after-call.
  - If ordered, per-topic "exactly-once-for-a-topic" semantics can be revisited later as a
    bounded experiment with transactional Kafka — never as a system-wide guarantee.
