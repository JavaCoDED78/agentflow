# Metrics catalogue

- **Standards:** [`standards.md`](standards.md) — metric naming (`agentflow.<module>.<object>.<measure>[.unit]`),
  unit-suffixed names, bounded state labels, no identifiers in labels (invariant 9), no tenant/run/user ids
  anywhere (invariant 9). Every metric here registers on `io.micrometer.core.instrument.MeterRegistry`
  (the shared Prometheus registry in the app), never a hand-rolled global.
- **Scope:** the `workflowexecution` family (`S13-11`), the `messaging` family (`S13-12`), the
  `ai` / `usage` / `knowledgebase` / `toolexecution` / `ingestion` families (`S13-13`) and the
  Boot-native HTTP/JVM/DB-pool meters (`S13-14` — the dashboard's data source).

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

## AI/RAG metrics (`S13-13`)

- **Owner:** `ai` / `usage` / `knowledgebase` / `toolexecution` / `ingestion` (`com.example.agentflow`)
  — the provider wrappers (chat + embedding), the usage recorder, the bounded tool executor, the
  hybrid retrieval and the document-indexing pipeline. The rules: **provider latency, errors, tokens,
  estimated cost, tool duration, retrieval latency, chunks returned and ingestion failures** — every
  label bounded, no secret, no prompt/document text, no identifier.

| Metric | Type | Unit / dimension | Labels | Meaning |
|---|---|---|---|---|
| `agentflow.ai.provider.chat.latency.seconds` | Timer | seconds | none | Whole provider chat call (the retry loop, not each attempt) — one sample per call, on success and on fault. |
| `agentflow.ai.provider.chat.errors.total` | Counter | count | `reason` (bounded: `rate_limit` / `server_error` / `timeout` / `connection` / `client_error` / `unknown`, plus `concurrency_limited` for the throttle fault) | One increment per failed attempt; a permanent failure is recorded once (no retry), a retryable one per attempt. |
| `agentflow.ai.provider.chat.tokens.input.total` | Counter | tokens | none | Input tokens reported by the provider on a successful response (never an invented value). |
| `agentflow.ai.provider.chat.tokens.output.total` | Counter | tokens | none | Output tokens reported by the provider on a successful response. |
| `agentflow.ai.provider.embedding.latency.seconds` | Timer | seconds | none | Whole provider embedding call (the retry loop) — one sample per call. |
| `agentflow.ai.provider.embedding.errors.total` | Counter | count | `reason` (bounded, same vocabulary) | One increment per failed attempt. |
| `agentflow.ai.provider.embedding.tokens.total` | Counter | tokens | none | Tokens reported by the provider on a successful response. |
| `agentflow.usage.estimated.cost.usd` | Counter | USD | none | The estimated cost a successful usage record carries, one increment per record (never an invented value; a zero/absent cost records nothing). |
| `agentflow.toolexecution.tool.duration.seconds` | Timer | seconds | `outcome` (bounded: `succeeded` / `throttled` / `timed_out` / `faulted`) | One sample per concluded tool call, from just before the call through its conclusion (success or fault). |
| `agentflow.knowledgebase.retrieval.chunks.returned.total` | Counter | chunks | none | Fused chunks returned by one hybrid-retrieval call, incremented once when the retrieval concludes (never an invented value). |
| `agentflow.ingestion.index.failures.total` | Counter | passes | none | One increment per failed indexing pass — a transient provider fault, a permanent (extraction) failure or a dimension-mismatch failure. |

Pre-existing retrieval meters (`S10-16`, instrumented with the hybrid search before the catalogue existed — documented here because the AI/RAG dashboard (S13-16) renders them):

| Metric | Type | Unit / dimension | Labels | Meaning |
|---|---|---|---|---|
| `agentflow.knowledgebase.retrieval.hybrid` | Counter | calls | none | Hybrid searches performed — one increment per `search()` call (the two-halves hybrid is one call). |
| `agentflow.knowledgebase.retrieval.half-results` | Counter | results | none | Raw results produced by the two hybrid retrieval halves (dense + FTS), summed — one increment per raw result per half. |
| `agentflow.knowledgebase.retrieval.duration` | Timer | seconds | none | Hybrid retrieval latency — one sample per `search()` call. |

### Conventions

- Bounded labels only: `reason` is the closed `ProviderFailureReason` vocabulary (plus
  `concurrency_limited` for the throttle fault); `outcome` is the closed `ToolInvocationOutcome`
  vocabulary (`succeeded` / `throttled` / `timed_out` / `faulted`). Never add `tenantId`, `runId`,
  `userId`, `documentId`, `chunkId`, `model` or any other identifier — the `<module>` in the name
  carries the signal's home.
- Provider tokens are recorded only when the provider reports them (a null or zero usage records
  nothing); never an invented value.
- The estimated cost counter records only the `estimatedCost` a record carries; a record without a
  cost registers nothing.
- The retry loop wraps the per-attempt meters: latency and span see the whole call, errors see each
  attempt.
- Metering never fails a business operation: every recorder is wrapped so a metering error is a
  debug-level log, never a call failure. A null registry skips metering silently.
- **Provider-call span (S13-13):** when a `Tracer` is wired, the resilient chat/embedding wrappers
  open a closed `ResilientChatModel#chat` / `ResilientEmbeddingModel#embed` span with a single
  `agentflow.ai.model` attribute (the target model id). No prompt, no content, no tenant, no
  identifier. The span is closed in `finally`; a failed call marks the span an error.

## HTTP/JVM/DB-pool metrics (`S13-14`)

- **Owner:** `config` (`com.example.agentflow.config`) — the Boot-native meters the API/JVM/DB
  dashboard renders. These are NOT `agentflow.*` custom metrics: they are the standard Boot/Actuator /
  HikariCP meters, wired by Boot's auto-configuration plus the one explicit wiring class.

| Metric | Type | Unit / dimension | Labels | Meaning |
|---|---|---|---|---|
| `http.server.requests` (Prometheus: `http_server_requests_seconds`) | Timer | seconds | bounded tags (status, method, path, application) | Every HTTP request; `management.metrics.distribution.percentiles-histogram: http.server.requests: true` emits the bucket series so the dashboard can render `histogram_quantile(0.95, …)`. |
| `jvm.memory.used` / `jvm.memory.max` (Prometheus: `jvm_memory_used_bytes` / `jvm_memory_max_bytes`) | Gauge | bytes | `area` (bounded: heap/nonheap) | JVM heap used/max — the heap panels. |
| `jvm.gc.pause` (Prometheus: `jvm_gc_pause_seconds`) | Timer | seconds | bounded tags | GC pause time; the dashboard samples the count rate. |
| `process.cpu.usage` (Prometheus: `process_cpu_usage`) | Gauge | fraction (0..1) | none | Process CPU usage (the dashboard renders it as a percent). |
| `jvm.threads.live` / `jvm.threads.peak` (Prometheus: `jvm_threads_live_threads` / `jvm_threads_peak_threads`) | Gauge | threads | none | Live/peak thread counts (the dashboard renders both). |
| `hikaricp.connections` (Prometheus: `hikaricp_connections`) | Gauge | count | `pool` | Connections held by the pool (the dashboard renders active/idle/max/min/pending/timeouts). |
| `hikaricp.connections.acquire` / `.usage` / `.creation` (Prometheus: `hikaricp_connections_acquire_seconds` / `_usage_seconds` / `_creation_seconds`) | Timer | seconds | `pool` | Acquisition/usage/creation timers — wired explicitly (see below). |

### Why explicit wiring

Boot 4.x registers the generic `jdbc.connections.*` gauges from the pool metadata provider but
deliberately does **not** wire the HikariCP connection-level meters — they live in HikariCP's own
`MicrometerMetricsTracker`, which is opt-in. [`HikariDataSourceMetricsConfiguration`](../../apps/agentflow-app/src/main/java/com/example/agentflow/config/HikariDataSourceMetricsConfiguration.java)
hands the app's `MeterRegistry` to the one `HikariDataSource` Boot builds:
`dataSource.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry))`. The class is
`@ConditionalOnBean(HikariDataSource.class)` (the DB-less skeleton boots unchanged) and registers no
custom tag beyond the pool name — bounded, static, never an identifier.

### Conventions

- No identifier is recorded: the `application` tag (the app name) is the only service-level tag, and
  the pool tag is the single static pool name. Tenant/run/user/document ids never appear.
- The histogram boundaries for `http.server.requests` are the Boot defaults; S13-19 owns any tighter
  tuning.
- The DB pool panels show real data once the app borrows a connection (any authenticated API request);
  a zero-traffic boot keeps the idle/max series only. That is honest, not a defect.

## Dashboard / alert hooks

The **Workflow/Kafka operational dashboard (S13-15)** consumes this family — `agentflow-workflow-kafka.json` — with the following first-consumer mapping (the dashboard is provisioned; alert SLOs land with S13-17/S13-18):

- `workflowexecution.run.total{state}` — rate of run transitions; the `failed`/`timeout`/`cancelled` series are the headline health signal.
- `workflowexecution.run.duration.seconds` — p50/p99 by control-group (not by id); a run that is still in flight contributes nothing (its terminal transition is what samples it).
- `workflowexecution.step.duration.seconds` — the step-level tail latency.
- `workflowexecution.step.retry.total` / `workflowexecution.lease.expired.total` / `recoveries` — retry storms and recovery-scan load; an expiry that is not reclaimed is a real incident signal (the stale run is not re-claimed).
- `workflowexecution.step.cancelled.total` — cancellations from all paths (user cancel, run terminal, lease fence).
- `messaging.outbox.pending.events` — the headline outbox lag; a sustained non-zero value means the publisher is falling behind.
- `messaging.outbox.oldest.age.seconds` — the age of the oldest due row; a long-tail value means the publisher is stuck.
- `messaging.outbox.publish.attempts.total{outcome}` — the per-attempt outcome mix; `permanent_failure` / `ambiguous` rates are the incident signals.
- `messaging.consumer.lag.count` — the ingester's backlog; pairs with `messaging.consumer.processing.failures.total{outcome}` for DLQ storms.

The **AI/RAG/cost dashboard (S13-16)** consumes the AI/RAG family — `agentflow-ai-rag-cost.json` — with the following first-consumer mapping (the dashboard is provisioned; alert SLOs land with S13-17/S13-18):

- `ai.provider.chat.latency.seconds` / `ai.provider.embedding.latency.seconds` — p95/p99 whole-call latency, one series each; a persistent p99 spike is a degraded provider.
- `ai.provider.chat.errors.total{reason}` / `ai.provider.embedding.errors.total{reason}` — the **provider-error** signal; one panel per leg, so a fake-provider scenario (rate_limit / timeout / connection / server_error / client_error / unknown / concurrency_limited) is visible without waiting for an alert.
- `ai.provider.chat.tokens.input.total` / `.tokens.output.total` / `.embedding.tokens.total` — token throughput, one series per source, reported by the provider only.
- `usage.estimated.cost.usd` — the hourly estimated-cost gauge (increase over 1h); the honest cost line for a budget.
- `toolexecution.tool.duration.seconds{outcome}` — the **application-side failure** surface (throttled / timed_out / faulted), shown separately from the provider panels.
- `knowledgebase.retrieval.duration` — hybrid-retrieval p95/p99.
- `knowledgebase.retrieval.hybrid` vs `knowledgebase.retrieval.chunks.returned.total` — the **empty-retrieval** signal: continuous hybrid calls with zero chunks returned means an empty collection, no close-enough candidate, or a drifted embedding model; no panel is invented to mark it.
- `ingestion.index.failures.total` — the application-side indexing-failure rate.

No alert, SLO or SLO-level dashboard is defined in this document — those land with `S13-17`/`S13-18`. (The S13-14 API/JVM/DB, S13-15 Workflow/Kafka and S13-16 AI/RAG/cost dashboards are already provisioned from Git.)
