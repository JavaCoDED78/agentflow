# Metrics catalogue

- **Standards:** [`standards.md`](standards.md) — metric naming (`agentflow.<module>.<object>.<measure>[.unit]`),
  unit-suffixed names, bounded state labels, no identifiers in labels (invariant 9), no tenant/run/user ids
  anywhere (invariant 9). Every metric here registers on `io.micrometer.core.instrument.MeterRegistry`
  (the shared Prometheus registry in the app), never a hand-rolled global.
- **Scope:** the `workflowexecution` family (`S13-11`) and the `messaging` family (`S13-12`). The
  `ai`/`usage`/`knowledgebase` (AI/RAG families) catalogue lands with `S13-13` and is intentionally
  **not** listed here.

---

## Workflow-execution metrics (`S13-11`)

- **Owner:** `workflowexecution` (`com.example.agentflow.workflowexecution`) — the runs, steps,
  retries, expired leases and cancellations a worker drives.

| Metric | Type | Unit / dimension | Labels | Meaning |
|---|---|---|---|---|
| `agentflow.workflowexecution.run.total` | Counter | count | `state` (target state: lowercased `WorkflowRunState`, bounded set) | Successful run transitions, one increment per transition, labelled with the **target** state. A terminal transition increments exactly once; the state label is the only label and never carries an id. |
| `agentflow.workflowexecution.run.duration.seconds` | Timer | seconds | none | A terminal run transition (`SUCCEEDED`/`FAILED`/`CANCELLED`/`TIMEOUT`) records one sample per run: `run.created_at` → the terminal transition (`Clock.instant()`, clamped at 0). A readable `RunView` is required; when absent the sample is skipped (no exception, no partial metric). |
| `agentflow.workflowexecution.step.duration.seconds` | Timer | seconds | none | One sample per handler invocation, measured from just before the handler (after the HUMAN_APPROVAL short-circuit and the pre-step limit check) through the step's conclusion — success, fault, or failure. No label (high-cardinality ids never appear). |
| `agentflow.workflowexecution.step.retry.total` | Counter | count | none | One increment per step actually moved to `RETRY_WAIT`. A step that exhausts the per-run retry budget and ends `FAILED` does **not** increment (no retry happened). |
| `agentflow.workflowexecution.step.cancelled.total` | Counter | count | none | One increment per successful step transition to `CANCELLED` (invariant 4 — no other path sets a step to CANCELLED). |
| `agentflow.workflowexecution.lease.expired.total` | Counter | count | none | One increment per expired-lease candidate scanned by the recovery scheduler (`findExpiredLeaseRuns`). Runs reclaimed AND runs that lost the claim increment this — it measures the **scan load**, not only re-claims. |
| `agentflow.workflow.recoveries` | Counter (existing, S07-09) | count | none | Successful recovery re-claims. Kept verbatim (predates the catalogue) — same semantics, do not rename; `agentflow.workflowexecution.lease.expired.total` is the S13-11 addition. |
| `agentflow.workflow.approvals.expired` | Counter (existing) | count | none | Approval expirations (existing). |

### Conventions

- **Never** add `tenantId`, `runId`, `userId`, `documentId`, `agentId` or any other identifier as
  a label — the `<module>` in the name carries the signal's home (`workflowexecution`), not the run.
- Unit suffixes: durations `*.seconds` (Timer), counts `*.total` (Counter), rates `*.rate` (counter
  + rate calculation at the dashboard, never a raw label).
- A counter increments exactly when a successful transition/claim occurs; a failed transition
  (`400` gate rejection, `409` lost race, step rejected by `enforcePreStepLimits`) increments
  nothing.
- Metering never fails a business operation: the run-duration and counter recorders are wrapped
  so a metering error is a debug-level log, never a transition failure.
- Two views of the same fact exist on purpose: `run.total{state}` (from the transition service,
  the authoritative state-machine path) and the worker's `step.duration.seconds` / `step.retry.total`
  (measured around the handler). A dashboard must not sum them as "steps" — they are distinct.

## Messaging metrics (`S13-12`)

- **Owner:** `messaging` (`com.example.agentflow.messaging`) — the outbox publisher, the inbox consumer
  and the DLQ path; the ingester (`ingestion.worker.DocumentIndexingWorker`) contributes the
  consumer-lag gauge.
- **Standards / task:** `S13-12` (P0) — the metrics that drive the outbox/inbox/DLQ dashboards
  (S13-14…S13-16), alerts (S13-17) and SLOs (S13-18).

| Metric | Type | Unit / dimension | Labels | Meaning |
|---|---|---|---|---|
| `agentflow.messaging.outbox.pending.events` | Gauge | count | none | Rows due for publish in the outbox (PENDING due / FAILED due / CLAIMED stale), read at scrape time from the same due set (`now - stale_interval`) the publisher claims (S06-07). One number per deployment — the due set is not sharded by id. |
| `agentflow.messaging.outbox.oldest.age.seconds` | Gauge | seconds | none | Age of the oldest due outbox row, in seconds (0 when none; clamped at 0): `now - created_at` of the oldest row in the due set, read at scrape time from the live database. |
| `agentflow.messaging.outbox.publish.attempts.total` | Counter | count | `outcome` (bounded: `delivered` / `transient_failure` / `ambiguous` / `permanent_failure`) | One increment per claimed outbox row. `failed` is deliberately not a label value — an exhausted `FAILED` row has no chance to publish, it is a permanent failure. |
| `agentflow.messaging.outbox.publish.retries.total` | Counter | count | none | One increment per outbox row scheduled for a bounded-backoff retry (`PENDING` → `CLAIMED` via `rescheduleForRetry`). |
| `agentflow.messaging.consumer.lag.count` | Gauge | count (records) | none | Total retained-but-unprocessed record count across the assigned partitions of the ingester, in records: `max(0, end - position)` per assigned partition, where `end` is the broker's reported end offset and `position` the consumer position. Re-read after every poll pass; the gauge reports the last known value (0 when no partitions are assigned or the broker read fails). |
| `agentflow.messaging.consumer.processing.failures.total` | Counter | count | `outcome` (bounded: `retry` / `dead_letter`) | One increment per consumer-side processing failure before the DLQ record (`permanent_failure` outcomes are counted once, tagged `dead_letter`, not re-tagged per retry). |

### Conventions

- Bounded labels only: `outcome` is a closed vocabulary (`delivered` / `transient_failure` /
  `ambiguous` / `permanent_failure` for the outbox; `retry` / `dead_letter` for the consumer).
  Never add `eventId`, `aggregateId`, `tenantId`, `runId`, topic names or payload excerpts — the
  `<module>` in the name carries the signal's home (`messaging`).
- The outbox/consumer metering never fails a business operation: the gauge reads and the counter
  increments are wrapped so a metering error is a debug-level log, never a publish/poll failure.
  A null registry (the test/standby path) skips metering silently — the publisher and consumer
  work identically and unchanged when no registry is wired.
- The outbox gauges are registered once at publisher construction; the publisher poll path is
  unchanged — `recordAttempt` / `recordRetry` run inside the existing success/failure paths.
- The consumer-lag gauge is a `Gauge.builder` that reports the last known lag; it is never refreshed from the broker at scrape time and never blocks the poll.

## Dashboard / alert hooks (downstream, not yet defined)

Expected first consumers (S13-14 dashboards, S13-17 alerts):

- `workflowexecution.run.total{state}` — rate of run transitions; the `failed`/`timeout`/`cancelled` series are the headline health signal.
- `workflowexecution.run.duration.seconds` — p50/p99 by control-group (not by id); a run that is still in flight contributes nothing (its terminal transition is what samples it).
- `workflowexecution.step.duration.seconds` — the step-level tail latency.
- `workflowexecution.step.retry.total` / `workflowexecution.lease.expired.total` / `recoveries` — retry storms and recovery-scan load; an expiry that is not reclaimed is a real incident signal (the stale run is not re-claimed).
- `workflowexecution.step.cancelled.total` — cancellations from all paths (user cancel, run terminal, lease fence).
- `messaging.outbox.pending.events` — the headline outbox lag; a sustained non-zero value means the publisher is falling behind.
- `messaging.outbox.oldest.age.seconds` — the age of the oldest due row; a long-tail value means the publisher is stuck.
- `messaging.outbox.publish.attempts.total{outcome}` — the per-attempt outcome mix; `permanent_failure` / `ambiguous` rates are the incident signals.
- `messaging.consumer.lag.count` — the ingester's backlog; pairs with `messaging.consumer.processing.failures.total{outcome}` for DLQ storms.

No alert, SLO or dashboard is defined in this document — those land with `S13-17`/`S13-18`.
