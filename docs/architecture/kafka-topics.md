# Kafka Topics — AgentFlow

> **Status:** Accepted
> **Owner:** `messaging` (owner of the `agentflow.*` topic namespace, the envelope,
> topic/versioning, the publisher/consumer, and the DLQ) — with one consuming
> module per topic
> **Task:** `S06-02`
> **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (§7 Kafka topics and
> event contracts; §8.1 `outbox_events`/`inbox_messages`) · `docs/domain-glossary.md`
> (§6 Events and messaging; canonical topic names) · `docs/architecture/module-map.md`
> (owners, dependency direction) · `docs/architecture/module-api.md` (the `messaging`
> public API: `EventEnvelope`, `EventKey`, `TopicName`/`EventType`) ·
> `docs/adr/ADR-002`, `ADR-003`, `ADR-004` (PostgreSQL source of truth; at-least-once
> + idempotency; transactional outbox) · `contracts/events/json-schema/envelope-v1.json`
> (the envelope every record carries) · root `AGENTS.md` (invariants 1, 3, 7, 9)
> **Purpose:** the canonical catalogue of the AgentFlow Kafka topics — one row per
> topic with its purpose, partition key, consumer owner, retention and partition
> count — so a producer, a consumer, a reviewer, and CI all agree on the *same*
> name, key and semantics. It is the human counterpart to the machine-readable event
> contract family in [`contracts/events/`](../../contracts/events/) and the event
> envelope schema (`S06-01`).

---

## Why a topic catalogue

The durable engine survives a crash between the PostgreSQL commit and the Kafka
publish (`S06-05`…`S06-08`), and every worker re-checks ownership against
PostgreSQL. For that to work, a message that lands on a topic must mean the *same*
thing to the producer that wrote it to the outbox, the publisher that forwarded it,
the consumer that applied it to its `inbox`, and the test that checks the contract:

1. **One name.** A topic is `agentflow.<domain>.<event>.v<N>` everywhere — in the
   outbox row (`outbox_events.topic`), the envelope `eventType`, the producer config
   and the consumer config. A typo that publishes to a differently-named topic is a
   silent new topic with a stranded consumer.
2. **One key.** The partition key decides which partition (and therefore which
   single consumer in a group) a record goes to, and — for an ordered aggregate —
   guarantees that all records of that aggregate hit the *same* partition.
3. **One owner.** Exactly one module owns the topic's contract and, per topic, one
   module owns consuming it. Nobody invents a consumer for an undocumented topic.
4. **One guarantee.** Delivery is **at-least-once with idempotency** (`ADR-003`);
   exactly-once is **never** promised between PostgreSQL and Kafka.

This document is the single source of truth for topics. `module-map.md` says *who
owns* each table and rule; this document extends that to the cross-process (Kafka)
boundary — *who produces, who consumes, and on what key*.

---

## The topics

Six topics today, all owned by the `messaging` module (envelope + topic namespace).
The consumer column names the module whose worker consumes the topic; the producer
column names the module that publishes it through the outbox (`ADR-004`).

| Topic (name · `vN`) | Aggregate | Partition key | Producer → Consumer | Purpose |
|---|---|---|---|---|
| `agentflow.workflow.run-requested.v1` | `workflow_run` (`runId`) | `tenantId:runId` | API/outbox → **workflow execution** worker | A new run has been created and committed to PostgreSQL; it is ready to be scheduled (`S06-05`). |
| `agentflow.workflow.step-ready.v1` | `workflow_run` (`runId`) | `tenantId:runId` | workflow engine → **workflow execution** worker | The next step of a run is ready to execute. |
| `agentflow.workflow.run-cancelled.v1` | `workflow_run` (`runId`) | `tenantId:runId` | API/outbox → **workflow execution** worker | A run cancellation was requested and committed; the run must stop on a safe boundary. |
| `agentflow.workflow.run-finished.v1` | `workflow_run` (`runId`) | `tenantId:runId` | workflow execution worker → downstream | A run reached a terminal state (`SUCCEEDED` / `FAILED`), including an ambiguous external-call outcome. |
| `agentflow.document.ingestion-requested.v1` | `document` (`documentId`) | `tenantId:documentId` | API/outbox → **ingestion** worker | A document is committed and ready to be extracted → chunked → embedded → indexed (`S06-05`). |
| `agentflow.document.indexed.v1` | `document` (`documentId`) | `tenantId:documentId` | **ingestion** worker → downstream (workflow execution / API) | A document finished ingestion and is ready for tenant-scoped search. |

> **Naming.** `kebab-case` domain + event, versioned with `.v<N>` — e.g.
> `agentflow.workflow.run-requested.v1` (see the naming table in
> `engineering-conventions.md`). A breaking change to a topic's key or payload
> creates a **new major version** (`...v2`), never an in-place change
> (`contracts/AGENTS.md`). New event types are additive.

---

## Partition key (stable aggregate key)

The partition key is `tenantId:<aggregateId>` — a **stable aggregate key**: the
aggregate and its id do not change for the life of the aggregate.

| Aggregate | Key | Applied to |
|---|---|---|
| `workflow_run` | `tenantId:runId` | `run-requested`, `step-ready`, `run-cancelled`, `run-finished` |
| `document` | `tenantId:documentId` | `ingestion-requested`, `indexed` |

Consequences:

- **Per-aggregate ordering.** All state events of one **run** hash to the same
  partition, so they are seen by one consumer in one group and arrive in order —
  `run-requested → step-ready → … → run-finished`. The key is stable because `runId`
  is fixed when the run is created (`outbox_events.event_key = tenantId:runId`), so a
  re-published or retried record of the same run still lands in the same partition.
- **Not per-tenant.** The key orders a **run**, not a tenant. A tenant with many runs
  is intentionally spread across partitions for parallelism — one tenant does not
  monopolise one partition (`AGENTFLOW_ARCHITECTURE_AND_PLAN.md` §7).
- **Tenant isolation travels with the key**, but the key is **not** authorization.
  The consumer still re-checks `(tenantId, resourceId)` against PostgreSQL before it
  applies anything (invariant 1); the envelope `tenantId` is routing/correlation only.

The `EventKey` public type (`messaging`) carries `tenantId:<aggregateId>`; the
outbox row stores the same value in `outbox_events.event_key` so a re-published
record is idempotent and re-routable (`ADR-003`).

---

## Consumer groups and owners

Each independent consumer runs in its own group; Kafka delivers a record to only one
member of a group, and a committed offset is per-group.

| Consumer group | Consumes | Owning module | Re-checks ownership against PostgreSQL |
|---|---|---|---|
| `agentflow.workflow-worker` | `run-requested`, `step-ready`, `run-cancelled`, `run-finished` | `workflowexecution` (the worker role) | yes |
| `agentflow.ingestion-worker` | `ingestion-requested` | `ingestion` (the worker role) | yes |
| `agentflow.run-await` / API poller | `indexed` (downstream) | `workflowexecution` / API | yes |
| `agentflow.dlq` (S06-11) | per-topic dead letters | `messaging` (`DlqService`) | n/a — investigation only |

Notes:

- The `workflowexecution` worker applies `run-requested`/`step-ready` by advancing the
  run/step state machine through the single transition service (`S05-11`), publishing
  the next event back through the outbox.
- `run-finished` and `indexed` are **downstream** triggers: a step awaiting a document,
  or a client polling document status, reacts to them. They carry no side effect of
  their own — the consumer performs its own tenant-scoped work.
- **Workers are a separate runtime role from the API.** The API pods never consume
  worker topics (`infra` / deployment rule); the worker Deployment is scaled
  independently.
- The `messaging` module owns every `agentflow.*` topic's contract (envelope, key,
  versioning, publisher, consumer, DLQ) — no other module publishes or consumes a
  topic directly.

---

## Delivery and idempotency (the reason the topics exist)

The topics exist to carry the boundary that the durable engine closes (`S06-05`…`S06-12`,
`ADR-003`):

- **Transactional outbox.** A run/step state change and its `outbox_events` row are
  written in **one transaction**; the publisher polls `outbox_events` and publishes —
  it never writes the row and publishes later. Publish is **idempotent by `event_id`**
  (`outbox_events.event_id`), so a publisher restart after a crash is safe.
- **At-least-once, never exactly-once.** A crash between the PostgreSQL commit and the
  broker `ack` can re-deliver a record. Consumers **deduplicate** with
  `(consumer_name, event_id)` in `inbox_messages`, written in the **same transaction**
  as the business change (`S06-10`), so a duplicate changes business state only once.
- **Ack only after durable commit.** The consumer commits the Kafka offset only after
  the inbox dedup + business change commit. No ack gap means no lost or double work.
- **Ambiguous external calls are honest.** A step that calls a model/tool and crashes
  before recording the outcome may be retried; the attempt and its ambiguous outcome are
  marked in usage/history (`ADR-003`, invariant 6). `run-finished` therefore records
  success *and* ambiguous/failed completion.
- **Retry + DLQ.** Transient faults retry with bounded backoff; permanent/malformed
  messages go to a per-topic DLQ for investigation, **not** unbounded retry (`S06-11`).

The per-run partition key supports this: because a run's records stay ordered in one
partition, a duplicate `step-ready` arrives behind the original and is dropped by the
inbox dedup before the step re-executes.

---

## Retention and partitioning

Retention and partition count are **topic-level configuration set by the owning
module (`messaging`)**, sized for consumer catch-up, replay and DLQ investigation —
**never guessed**. The concrete values are configuration, not code, and change with a
PR that updates this document.

| Concern | Default policy (tunable) | Reason |
|---|---|---|
| **Partitions — dev / local** | `1`–`3`, replication factor `1` (single-node KRaft) | enough to exercise key-based routing without over-provisioning; the S01-14 compose acceptance smoke used `--partitions 1`. |
| **Partitions — MVP** | `3`–`6` | the plan's MVP target (§7); a small non-zero count so partition key behaviour is real. |
| **Partitions — production** | derived from a load test + distribution keys (not hard-coded) | distribution is by `tenantId:runId` / `tenantId:documentId`; the count follows measured throughput and even-ness. |
| **Retention — workflow & document topics** | bounded window, e.g. `7 days` (default) | long enough for a consumer to catch up after an outage and for ordered replay of a run; an event-log, not a compacted key-value store. |
| **Retention — DLQ topics** | longer window, e.g. `30 days` (default), drained by an operator | messages are evidence for investigation, not to be reprocessed by default. |

The per-run ordering guarantee raises the retention floor: retention must cover the
worst-case consumer catch-up window, or a restarted consumer could replay an event
outside the window it was designed for. `run-requested`/`step-ready`/`run-cancelled`
share a run's partition and a run's retention window.

---

## Envelope identity

Every record on these topics carries the same envelope
([`contracts/events/json-schema/envelope-v1.json`](../../contracts/events/json-schema/envelope-v1.json),
`S06-01`): `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `tenantId`,
`aggregateType`, `aggregateId`, `correlationId`, `causationId`, `payload`.

- `eventType` + `aggregateId` identify *which* topic and *which* aggregate instance the
  record belongs to; `eventId` is the idempotency/dedup key across outbox and inbox.
- The `payload` is a typed object with a redacted field allowlist (defined per event,
  `S06-16`).

**No secret, full prompt, full document, API key or access token** in a payload or the
headers by default (`AGENTS.md` invariant 8). Correlation / trace context
(`traceparent`, correlation id, causation id) travels in the **headers**, and message
size is bounded (`AGENTFLOW_ARCHITECTURE_AND_PLAN.md` §7). `tenantId` in the envelope
is routing/correlation only — never an authorization source (invariant 1).

---

## Event and contract versioning

- **Topics and partition keys are versioned** (`topic.vN`). Changing/removing a field,
  its meaning, or the partition key is **breaking** and creates a new major topic
  version (`agentflow.<domain>.<event>.v<N>`), an explicit baseline update, a
  `CHANGELOG.md` entry and an ADR when needed (`contracts/AGENTS.md`).
- **Schema evolution is additive** while an envelope/payload is published: adding an
  optional field is compatible; anything else bumps the version.
- **MVP uses JSON + JSON Schema validation in tests.** A Schema Registry
  (Avro + Apicurio) is a later, separate increment after a stable envelope — not a
  blocker for the durable workflow.

---

## Security

- **Tenant isolation is enforced at the consumer, not the partition key.** The key
  carries the tenant, but the consumer re-checks `(tenantId, resourceId)` against
  PostgreSQL before applying the record (invariant 1); a foreign tenant's resource is
  indistinguishable from a missing one (`404`/`403` policy, `ADR-007`).
- **The model is an untrusted planner, not an authorization authority** (invariant 7).
  A topic's presence or a payload's contents never grant a permission; the allowlist
  and permissions are decided by the backend before any tool/step runs.
- **No secrets or high-cardinality identifiers in telemetry.** Metrics and logs use the
  topic name and `error_class` only — never `tenantId`, `runId`, `documentId` or a
  payload (invariant 9).

---

## Related

- [`module-map.md`](./module-map.md) — the module that owns each topic (`messaging`) and
  each consumer (`workflowexecution`, `ingestion`) and the allowed dependency edges.
- [`module-api.md`](./module-api.md) — the `messaging` public API boundary
  (`EventEnvelope`, `EventKey`, `TopicName`/`EventType`); `worker.*` listeners are
  internal, not part of the public API.
- [`domain-glossary.md`](../domain-glossary.md) §6 — the canonical topic names and the
  at-least-once + idempotency guarantee.
- **`events/README.md`** — the event catalogue, per-event payload schemas, retry/DLQ and the
  safe-contract-change rules (`⚠ planned`, `S06-16`).
- [`../../contracts/events/json-schema/envelope-v1.json`](../../contracts/events/json-schema/envelope-v1.json) — the envelope every record carries (`S06-01`).
- **`contracts/asyncapi/`** — the machine-readable AsyncAPI description of the same
  topics and producer/consumer roles (`⚠ planned`, P1).
- [`ADR-002`](../adr/ADR-002-postgresql-source-of-truth.md),
  [`ADR-003`](../adr/ADR-003-at-least-once-idempotency.md),
  [`ADR-004`](../adr/ADR-004-transactional-outbox.md) — PostgreSQL is the source of
  truth, at-least-once + idempotency, transactional outbox.
