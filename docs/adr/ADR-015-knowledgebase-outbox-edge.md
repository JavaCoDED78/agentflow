# ADR-015. knowledgebase enqueues ingestion through the transactional outbox

- **Status:** Accepted
- **Date:** 2026-11-11
- **Task:** S10-06 (Sprint 10 — document metadata, ingestion enqueue, tenant-filtered search)
- **Supersedes:** none. Part of the document-lifecycle consistency model (`ADR-002`…`ADR-004`
  applied to the upload flow).
- **Superseded by:** none

## Context

The document lifecycle (`docs/architecture/document-lifecycle.md`, `S10-01`…`S10-13`) is
`UPLOADED → QUEUED → PROCESSING → INDEXED`. The transition `UPLOADED → QUEUED` is triggered by the
`agentflow.document.ingestion-requested.v1` event: the upload validates the payload, stores the
object under a server-generated `object_key`, commits the `documents` metadata row and — atomically
with that row — publishes `ingestion-requested` to the worker so it can extract → chunk → embed →
index (S10-06). `documents.status` is the source of truth for the object (`ADR-002`), so the row and
the event must be written together: a row committed without its event stalls the document forever at
`UPLOADED`, and an event published without a committed row makes the worker act on a document that
does not exist.

The only sanctioned way to publish through the transactional outbox is `messaging.application.OutboxService#enqueue(OutboxEvent)` (`ADR-004`), which the `workflowexecution` module already uses via
`RunService` (arrow 13). The upload flow, however, lives in the `knowledgebase` module (it owns the
`documents` table), and the module-allowed dependency set of `knowledgebase` is `{knowledgebase, ai,
workflowexecution}` — it does **not** contain `messaging`. Enqueuing the event from the upload service
therefore needs a compile edge `knowledgebase → messaging`, and the module map forbids any edge
outside the allowed set (root `AGENTS.md`; `docs/architecture/module-map.md`).

## Decision

**Grant `knowledgebase` a new compile edge to `messaging`, recorded here, and enqueue the
`ingestion-requested` event from `knowledgebase.application.DocumentService` through the same
`OutboxService` port the `workflowexecution` module already uses.** `DocumentService.upload` holds a
single `@Transactional` boundary that inserts the `documents` row in status `UPLOADED` and appends
`ingestion-requested` to the outbox in the same connection, so the row and the event commit together
or not at all (invariant 3, `ADR-004`). The event is tenant-scoped through the `outbox_events.tenant_id`
column and keyed by `(tenant_id, sha256)` at the consumer, so the upload is idempotent by content
(plan §8.2). The compile edge is the narrowest possible: `knowledgebase` reaches `messaging` only
through its public `application` package (`OutboxService`, `OutboxEvent`, `CorrelationHeaders`), never
its `persistence` or `worker` packages.

## Alternatives considered

1. **Own the enqueue in the `ingestion` module instead.** `ingestion` already depends on
   `messaging` (arrow 11) and on `knowledgebase` (arrow 6, indexing writes `documents`). Rejected:
   the `documents` row is owned by `knowledgebase` (one owning module per table); having `ingestion`
   insert it would split the upload across two owners and make the "commit row + event in one
   transaction" boundary fall between modules. The producer is the API/upload flow (`knowledgebase`),
   which `ADR-004` already treats as the outbox author.
2. **Route the enqueue through `workflowexecution`** (`knowledgebase` already depends on it via the
   `StepHandler` SPI, arrow 16). Rejected: that edge exists only for the step-orchestration SPI; a
   generic "append any event to the outbox" port on `workflowexecution` would be a new, unrelated
   responsibility on the wrong module and a confusing dependency.
3. **Publish the event directly from `knowledgebase` (KafkaTemplate, no outbox).** Rejected: the
   "row committed, publish failed" gap leaves a document stuck at `UPLOADED` forever; `ADR-004`
   forbids a direct publish and requires the transactional outbox.
4. **Skip the edge and return `UPLOADED`, letting a later step enqueue.** Rejected: the acceptance
   criterion (`S10-06`) requires the upload response to *not* wait for parsing/embedding, which is
   exactly the outbox enqueue — deferring it would defer the whole task.

## Consequences

- **Positive:**
  - The upload is the single, transactional author of both the `documents` row and its
    `ingestion-requested` event (invariant 3); a document is never committed without its event and
    the event is never published without durable state (`ADR-004`).
  - `knowledgebase` uses the same `OutboxService` port as `workflowexecution`, so the outbox model
    is uniform across modules.
- **Negative / operational:**
  - `knowledgebase` now has a compile dependency on `messaging` (a runtime-only dependency before),
    so it must keep its reach narrow: only the public `messaging.application` / `messaging.domain`
    outbox types, never `messaging.persistence` or the Kafka listeners (`messaging.worker`).
  - `OutboxService` null-injects its repository when no `DataSource` is configured and fails `503`
    on enqueue (S01-02), exactly as `RunService` relies on; an upload with no database still fails
    loudly rather than dropping the event.
- **Follow-ups:**
  - The HTTP upload endpoint that drives `DocumentService.upload` and returns `202` (the object is
    written to object storage by the follow-up S3/Garage client, `ADR-013`; the enqueue flow here is
    the asynchronous core that makes that response non-blocking on parsing/embedding).
  - The `QUEUED → PROCESSING → INDEXED` transitions are owned by the document state machine
    (`S10-01`…`S10-13`), which the outbox consumer drives.

## When to write an ADR

A new dependency or trust edge between modules (respecting the no-cycle and module-ownership rules)
— exactly this case (root `AGENTS.md`; `docs/architecture/module-map.md`).
