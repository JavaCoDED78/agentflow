# Sprint 13 — Observability: metrics pipeline as code

Detailed per-task changelog records for the observability thread. Slim one-line summaries with links are in the root [`CHANGELOG.md`](../../CHANGELOG.md).

## Contents

- [S13-01 — telemetry conventions](#s13-01-telemetry-conventions)
- [S13-02 — Micrometer/Actuator metrics](#s13-02--micrometeractuator-metrics)
- [S13-03 — OTLP tracing exporter](#s13-03-otlp-tracing-exporter)
- [S13-04 — OTel Collector config as code](#s13-04-otel-collector-config-as-code)
- [S13-05 — Prometheus as code](#s13-05-prometheus-as-code)
- [S13-06 — Grafana provisioning as code](#s13-06-grafana-provisioning-as-code)
- [S13-07 — Loki as code](#s13-07-loki-as-code)
- [S13-08 — Tempo as code](#s13-08-tempo-as-code)
- [S13-09 — standardized JSON logs](#s13-09-standardized-json-logs)
- [S13-10 — trace context over Kafka](#s13-10-trace-context-over-kafka)
- [S13-11 — workflow metrics instrumentation](#s13-11-workflow-metrics-instrumentation)
- [S13-12 — messaging metrics instrumentation](#s13-12-messaging-metrics-instrumentation)
- [S13-13 — AI/RAG metrics instrumentation](#s13-13-airag-metrics-instrumentation)
- [S13-14 — API/JVM/DB dashboard](#s13-14-apijvmdb-dashboard)
- [S13-15 — Workflow/Kafka dashboard](#s13-15-workflowkafka-dashboard)

---

## S13-09 — standardized JSON logs

### What changed

The JSON log line is now a single, pinned contract: a dedicated encoder owns the shape, the whitelisted MDC keys and the correlation id plumbing, and the wire path stays masked end to end.

- **`StructuredJsonLogstashEncoder`** (new, `observability`) — extends the logback logstash encoder with exactly the S13-09 shape: `@timestamp` (unchanged), `severity` (the level rendered as `severity`, not `level` — the vocabulary the [`standards.md`](../observability/standards.md) log-line contract uses), the redundant numeric `level_value` dropped, `@version` kept, and `message`/`logger_name`/`thread_name`/`exception` unchanged. The MDC is written **inline, whitelisted** — only `service`, `environment`, `traceId`, `spanId`, `correlationId` — so a stray high-cardinality id put into the MDC by any caller can never reach a log line.
- **`CorrelationIdFilter`** (new, `observability`) — a `OncePerRequestFilter` that seeds `correlationId` into the MDC for the duration of one HTTP request: the active `traceId` when tracing is wired (a request that already carries a trace id reports exactly that one), a fresh UUID otherwise, and removed in a `finally` block even when the chain throws — it never leaks into unrelated requests on a thread pool. The filter is observability-only: it never reads identity or business state and never changes what a controller does.
- **`GlobalExceptionHandler`** — `correlationId()` now reads the filter-seeded `correlationId` first (then `traceId`, then a fresh UUID), so the response's `correlation_id` is the same id that appears in every log line of the request. This is the one cross-cutting call site that consumes the correlation id; it is unchanged otherwise.
- **`logback-spring.xml`** — now points at the dedicated encoder (the single place the JSON log field contract lives — do not configure ad-hoc encoders per module; change the encoder here). Masking stays on the wire path: `MaskingJsonGeneratorDecorator` + `SecretRedactingValueMasker`, so `Bearer`/JWT/api-key/AWS-key/prompt/document-body shapes never reach stdout/Loki, canary-tested.

### Why

Until now the encoder shape was the logback-logstash default (`level`, `level_value`, and every MDC key written inline — an unbounded set), and every request's logs could not be found by one id: `traceId`/`spanId`/`correlationId` were only written when the tracing bridge populated them, and `correlationId` did not exist as a first-class log field at all. S13-09 closes that: the whitelisted, dedicated encoder fixes the shape, and the filter gives every request one id — so a log line of a request can be found in Loki/console logs by exactly that id.

### Acceptance

A log line of a request carries `@timestamp`, `severity`, `service`, `environment`, `correlationId` and (when tracing is wired) `traceId`/`spanId`; the canary secret is masked. A stray high-cardinality MDC key never reaches the line.

### Verification

`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.observability.*' --tests 'com.example.agentflow.common.error.*' --rerun-tasks` — green, JUnit XML verified fresh: `LoggingJsonFormatTest` (2 — structured fields incl. `severity`/`@timestamp`/`thread_name`, the exactly five whitelisted MDC keys inline with tenant/run/user ids dropped, secrets masked; the canary fixture `secrets/canary-openai-key.txt` never reaches the line), `CorrelationIdFilterTest` (5 — traceId-derived, fresh-UUID fallback, blank-traceId fallback, `finally` cleanup on exception, no overwrite of a stale id), `GlobalExceptionHandlerTest` (5 — correlation-id response header), `OtlpTracingExportTest`/`PrometheusMetricsEndpointTest`/`SecretRedactingValueMaskerTest` unchanged and green.

### Documentation

- `apps/agentflow-app/src/main/java/com/example/agentflow/observability/StructuredJsonLogstashEncoder.java` (new), `CorrelationIdFilter.java` (new).
- `apps/agentflow-app/src/main/resources/logback-spring.xml` (the encoder swap).
- `apps/agentflow-app/src/test/java/com/example/agentflow/observability/LoggingJsonFormatTest.java`, `CorrelationIdFilterTest.java` (new).
- `apps/agentflow-app/src/main/java/com/example/agentflow/common/error/GlobalExceptionHandler.java` (correlation-id precedence).
- `docs/devlog/sprint-13-observability.md` (this file) + `CHANGELOG.md` (Unreleased / Added).

### Scope / follow-up

The Kafka trace-propagation half (trace context over outbox/messaging) is S13-10; the metrics half is S13-11/S13-12. The encoder whitelist and the filter are per-request, in-process — nothing about them crosses a Kafka boundary yet.

## S13-10 — trace context over Kafka

### What changed

The producer and consumer legs of the outbox/inbox boundary now carry the W3C trace-context header end to end, and both legs create their own spans parented on whatever the headers say — the replay/queueing delay between PostgreSQL and the worker is no longer a trace break.

- **`MessagingTracing`** (new, `messaging.application`) — the single trace-context builder for the messaging boundary: `parentContext(traceparent)` (the extracted parent context — a remote span context for a valid header, `Context.root()` otherwise), `startSpan(tracer, name, parentContext, correlationId, causationId)` (a root span or a span parented on the remote parent, recording `correlation_id` / `causation_id` only as attributes when present — never tenant / run / document ids), `currentTraceparent()` (the active span's `traceparent` string — ready to be stored in `CorrelationHeaders#traceparent()`, `null` when no valid span is active). The `messaging` module is the home of the messaging boundary's trace-context builder (`S13-10`; `S13-01` fixed the conventions in `docs/observability/standards.md` — spans are built by the module that owns the operation); nothing else in the codebase builds spans for Kafka today.
- **`KafkaMessageDeliverer`** (producer) — derives the trace parent and the correlation/causation attributes from the record's `headers` JSON (the `OutboxService#enqueue` path threads the request traceparent into the event — the HTTP create-run path via the explicit `RunService` traceparent, every other traced enqueue automatically). The trace is now visible as `KafkaMessageDeliverer#deliver`, a span that brackets the blocking `send().get()` — parented on the producer-side request span (the `API → outbox → publisher` leg) and attributed `correlation_id` / `causation_id` from the headers when present. A permanent auth failure ends the span with an error immediately rather than leaking a never-ending publish span. The class requires an `ObjectProvider<SdkTracerProvider>` (like the repositories) so it boots without tracing — the provider is injected as `tracerProvider.getIfAvailable()`, and `null` disables the spans.
- **`DocumentIndexingWorker`** (consumer) — `pollOnce()` now runs each `handle()` inside a `DocumentIndexingWorker#handle` span (parent context taken from the record's `traceparent` header — the request context the producer wrote — never from the consumer's own poll span); `correlationId` / `causationId` from the record headers are recorded as attributes. The span is closed in a `finally` block so an exception in the action leaves the exporter with a proper span. The class requires an `ObjectProvider<SdkTracerProvider>`, consistent with the rest of the workers — the provider is injected as `tracerProvider.getIfAvailable()`, and `null` disables the spans (the worker behaves exactly as before).
- **`CorrelationHeaders`** (the immutable value type) — gains `parse(...)`, the symmetric reader of the `headers` JSON the producer writes (the producer previously could not read it back). The wire contract is unchanged: the same three fields, the same stable JSON order, only non-blank fields emitted.
- **`OutboxService`** (the outbox boundary) — `enqueue()` now threads the active span's `traceparent` into the event's `headers` JSON when the producer did not set one (blank, corrupt, or carry only correlation/causation), so an event persisted inside a traced request carries the trace end-to-end with no caller-side knowledge of OpenTelemetry. The same three rules: an explicit `traceparent` wins, a corrupt payload is left untouched, and a blank payload gets the threaded `traceparent`.
- **`OutboxEvent`** (domain) — gains the `withHeadersJson` accessor (the record already carried the raw `headersJson`; the new accessor is what `enqueue` uses to rewrite it). No new schema, no migration — the `headers` JSON shape is unchanged.

### Why

S13-09 fixes the log-line correlation contract, but until S13-10 nothing crossed a Kafka boundary: the producer's `traceparent` was silently dropped (the producer only forwarded `correlationId` / `causationId`) and the consumer ran blind. S13-10 closes that in one boundary — one shared builder (`MessagingTracing`) for both legs, the JSON headers keeping the same `correlationId`/`causationId`/`traceparent` contract end-to-end — so the API request, the queueing delay, the publisher and the worker processing all land in one trace.

### Acceptance

A trace view shows the API request → outbox publish → worker processing as one trace: the producer span (`KafkaMessageDeliverer#deliver`) is parented on the API request span, and the consumer span (`DocumentIndexingWorker#handle`) is parented on the same record `traceparent` the producer wrote — the API request span's — so all three land in one trace; correlation/causation ids are preserved across the boundary, as span attributes and as record headers.

### Verification

DB-free, JUnit XML verified fresh (`failures=0`, `errors=0`, `skipped=0`): `MessagingTracingTest` (10 — root/remote context, garbage/blank/unparseable input, active context, traceparent extraction, correlation/causation attributes, parent context valid/invalid), `RecordingSpanExporter` (the `SpanExporter` test double used by every span test below), `OutboxServiceTest` (5 — the active span's traceparent threads into an unthreaded event, an explicit traceparent is left untouched, a blank payload gets threaded, a no-span event is unchanged, a corrupt payload is untouched), `DocumentIndexingWorkerTest` (8 — 6 pre-existing + 2 new: a record with `traceparent`/`correlationId`/`causationId` headers is processed inside a span parented on the record's `traceparent` with the ids as attributes; a headerless record gets a root span with no attributes).

Integration, real KRaft broker (Testcontainers, Apache Kafka 4.3.1), `KafkaTraceContextIT` (1 — the request span, the producer span and the consumer span land in one trace across the broker: the producer and consumer spans are parented on the request span, the record carries the request span's `traceparent` plus the correlation/causation ids as headers, and both legs record correlation/causation as span attributes). Spotless applied. `./gradlew :apps:agentflow-app:check` — green.

### Documentation

- `apps/agentflow-app/src/main/java/com/example/agentflow/messaging/application/MessagingTracing.java` (new) + test (`MessagingTracingTest`).
- `apps/agentflow-app/src/main/java/com/example/agentflow/messaging/application/OutboxService.java`, `CorrelationHeaders.java`, `domain/OutboxEvent.java`, `worker/KafkaMessageDeliverer.java` (the trace-context contract).
- `apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/worker/DocumentIndexingWorker.java` (the consumer span).
- `apps/agentflow-app/src/test/java/com/example/agentflow/messaging/application/RecordingSpanExporter.java`, `OutboxServiceTest.java`, `MessagingTracingTest.java`, `DocumentIndexingWorkerTest.java` (the test double and span assertions).
- `apps/agentflow-app/src/integrationTest/java/com/example/agentflow/messaging/worker/KafkaTraceContextIT.java` (the real-broker end-to-end trace-context test).
- `docs/architecture/kafka-topics.md` (the trace-context propagation subsection), `docs/architecture/module-api.md` / `docs/architecture/module-map.md` / `docs/domain-glossary.md` / `docs/observability/standards.md` / `apps/agentflow-app/src/main/java/com/example/agentflow/observability/AGENTS.md` (the ownership + the real span names), `CHANGELOG.md` (this file's entry).

### Scope / follow-up

The metrics half (S13-11/S13-12) is untouched. The `WorkflowWorker` span is not added here — it is constructed with a tracer in a later observability task; the worker's own trace handling follows the same `MessagingTracing` builder.

## S13-11 — workflow metrics instrumentation

### What changed

The `workflowexecution` module now emits the Sprint 13 metrics the dashboards (S13-14…S13-16), alerts (S13-17) and SLOs (S13-18) build on — runs by state, run/step duration, retries, expired leases and cancellations — one metric per fact, every label bounded, nothing carrying an identifier.

- **`RunStateMachineService`** (`workflowexecution.application`) gains an optional `MeterRegistry` + `Clock` (a 7-arg full constructor; the existing 5-arg constructor delegates with a null registry — the meters live on a throwaway registry, no observability surface, and the tests are unchanged). After every successful **run** transition it increments `agentflow.workflowexecution.run.total{state}` (target state lowercased — `running`/`succeeded`/`failed`/`cancelled`/`timeout`/… — the bounded closed vocabulary), and on a **terminal** transition it additionally records one sample of `agentflow.workflowexecution.run.duration.seconds` (timer, `run.created_at` → `Clock.instant()`, clamped at 0, the newest `OffsetDateTime` read via `findRun`; skipped silently when the read is unavailable — never fails a transition). After every successful **step** transition to `CANCELLED` it increments `agentflow.workflowexecution.step.cancelled.total`. Metering is wrapped so an instrumenting failure is a debug log, never a transition failure.
- **`WorkflowWorker`** (`workflowexecution.application`) gains an optional `MeterRegistry` (a 12-arg full constructor; the 10- and 11-arg constructors delegate with a null registry, so the existing tests are untouched). Around each handler invocation it measures the step with a timer recording `agentflow.workflowexecution.step.duration.seconds` (`stepStartedAt` just before the handler, recorded in the `finally` after the step concludes — success, fault, or failure), and in the transient-fault catch path it increments `agentflow.workflowexecution.step.retry.total` once per step actually moved to `RETRY_WAIT` (a step that exhausts the retry budget and ends `FAILED` increments nothing — no retry happened).
- **`WorkflowRecoveryScheduler`** (`workflowexecution.worker`) increments `agentflow.workflowexecution.lease.expired.total` once per expired-lease candidate scanned by the pass (S07-09's `recoveries` counter is kept verbatim for successful re-claims — renaming a shipped metric would break dashboards).
- **`docs/observability/metrics.md`** (new) — the catalogue: names, types, units, labels and the meaning of every workflow-execution metric above, the S07-09 `recoveries` metric kept verbatim, the downstream dashboard/alert hooks (not yet defined), and the workflow-only scope note.

### Scope / follow-up

The `messaging` (outbox/inbox/DLQ) family landed with S13-12 (see the S13-12 section below); the `ai`/`usage`/`knowledgebase` (AI/RAG) family lands with S13-13 — this catalogue covers `workflowexecution` (S13-11) and `messaging` (S13-12). The run-duration sampler is skipped silently when the run is not readable (a cross-tenant read, an IT-mocked repo with no `findRun` stub) — the counter still increments, so a terminal transition is always visible through `run.total{state}`. No dashboards, alerts or SLOs are defined here (S13-14/15/17/18).

### Documentation

- `docs/observability/metrics.md` (the catalogue), `docs/devlog/sprint-13-observability.md` (this section), `CHANGELOG.md` (this entry).

## S13-12 — messaging metrics instrumentation

### What changed

The `messaging` family of Sprint 13 metrics is now instrumented — the metrics that drive the outbox/inbox/DLQ dashboards (S13-14…S13-16), alerts (S13-17) and SLOs (S13-18): the **pending-row gauges** of the outbox, a **publish-attempt counter** tagged with the bounded outcome vocabulary, a **retry counter**, and the ingester's **consumer-lag gauge** plus a **processing-failures counter** — all bounded by construction, none carrying an identifier, none failing a business operation.

**New [`OutboxPendingSnapshot`](apps/agentflow-app/src/main/java/com/example/agentflow/messaging/persistence/OutboxPendingSnapshot.java)** (`messaging.persistence`) — the read model the outbox gauges consume: `pendingEvents` + `oldestAgeSeconds`, one bounded query (`count(*) AS pending_events` + `EXTRACT(EPOCH FROM now() - min(created_at)) AS oldest_age_seconds`) over exactly the due set `selectForClaim` claims (`PENDING` due / `FAILED` due / `CLAIMED` stale, per the `claim-timeout-seconds` bound), so the gauge and the publisher's view agree. `OutboxEventRepository` gains `pendingSnapshot(long claimTimeoutSeconds)`. `OutboxPublisherService` (optional `MeterRegistry` threaded through the full constructor, 4-arg delegate) registers the two gauges at construction — `agentflow.messaging.outbox.pending.events` and `agentflow.messaging.outbox.oldest.age.seconds` — reading the repository at scrape time; metering is wrapped and debug-logged. After each claimed row it increments `agentflow.messaging.outbox.publish.attempts.total{outcome}` — `delivered` on success, `transient_failure` / `ambiguous` / `permanent_failure` from the `DeliverResult` branch — and, when a row is scheduled for a bounded-backoff retry, increments `agentflow.messaging.outbox.publish.retries.total`.

**`InboxConsumerService`** (`messaging.application`, the consumer boundary) takes an optional `MeterRegistry` (4-arg full constructor, 3-arg delegate) and increments the `agentflow.messaging.consumer.processing.failures.total{outcome}` counter on the `RETRY` / `DEAD_LETTER` branch of `apply()` — `retry` for the transient disposition, `dead_letter` for the terminal one — exactly once per failed business change, never for `PROCESSED` / `OTHER_CLAIMED`, wrapped and debug-logged.

**`DocumentIndexingWorker`** (`ingestion.worker`, extended in place) takes an optional `MeterRegistry` (threaded through the full constructor and the Spring-facing constructor, `@Autowired(required = false)`): a `agentflow.messaging.consumer.lag.count` gauge registering once at construction — it reports the last known value, re-read after every poll pass as `max(0, end - position)` summed over the assigned partitions (`endOffsets` from the broker), 0 when no partitions are assigned or the broker read fails (the catch is wrapped and debug-logged, never escaping the poll loop). The poll loop, the claim logic and the Kafka offset management are untouched.

A null registry everywhere is a silent no-op — the publisher and consumer behave exactly as before when no registry is wired; the previous constructors are unchanged.

### Why

Sprint 13's metric families must exist before the dashboards land — the outbox/inbox/DLQ view was the missing half after S13-11 shipped the workflow-execution family. The plan (S13-12) names exactly five surfaces: pending outbox count, oldest pending row age, per-attempt publish counter, consumer lag and consumer-processing failures. A DLQ count is deliberately **not** a metric: it would need a per-tenant reader and is a business-visible state better surfaced through the `ops:dlq` endpoints (S12-11…13), not as a meter.

### Verification

`./gradlew :apps:agentflow-app:test --tests '*OutboxPublisherMetricsTest' --tests '*InboxConsumerMetricsTest' --tests '*DocumentIndexingWorkerLagTest' --tests '*OutboxEventRepositoryTest' --tests '*InboxConsumerServiceTest' --tests '*OutboxPublisherServiceTest'` — green, JUnit XML verified fresh (`OutboxPublisherMetricsTest` 8/8, `InboxConsumerMetricsTest` 5/5, `DocumentIndexingWorkerLagTest` 5/5, `OutboxEventRepositoryTest` 9/9 incl. the 4 new pending-snapshot cases; `OutboxPublisherServiceTest` and `InboxConsumerServiceTest` unchanged and green). `./gradlew :apps:agentflow-app:test` (whole unit suite) — BUILD SUCCESSFUL; `ArchitectureBoundaryTest --rerun-tasks` green; `spotlessCheck` green.

### Scope / follow-up

- The `ai`/`usage`/`knowledgebase` (AI/RAG) metric family lands with S13-13 — not touched here.
- No dashboard, alert or SLO is defined in this section (S13-17/18).

### Documentation

- [`docs/observability/metrics.md`](../observability/metrics.md) — the messaging family added to the catalogue (names, types, units, labels, the conventions and the downstream hooks).
- `docs/devlog/sprint-13-observability.md` (this section); `CHANGELOG.md` (this entry).

## S13-13 — AI/RAG metrics instrumentation

### What changed

The `ai` / `usage` / `knowledgebase` / `toolexecution` / `ingestion` families of Sprint 13 metrics are now instrumented — the provider-call latency, errors, tokens and estimated cost, the tool-duration and chunks-returned, and the ingestion-failure metrics the AI/RAG dashboards (S13-14…S13-16), alerts (S13-17) and SLOs (S13-18) build on. Every metric is one fact with a bounded label, no identifier anywhere, and metering never fails a business operation.

**`ResilientChatModel`** (`ai.provider`, extended in place) takes an optional `MeterRegistry` + `Tracer` (threaded through the 11-arg full constructor, 9-arg delegate — the previous 10-arg 9-arg delegates keep the test seams unchanged). After every attempt it records `agentflow.ai.provider.chat.latency.seconds` (timer, the whole retry loop — one sample per call, on success and on fault) and `agentflow.ai.provider.chat.errors.total{reason}` (counter, on failure only — a permanent failure is recorded once, a retryable one per attempt); on a successful response it also records `agentflow.ai.provider.chat.tokens.input.total` / `.tokens.output.total` (counters, only when the provider reports the counts — never an invented value). When a `Tracer` is wired it opens a closed `ResilientChatModel#chat` span (`agentflow.ai.model` = the target model id, no prompt/content/tenant), closed in a `finally`.

**`ResilientEmbeddingModel`** (`ai.provider`, extended in place) takes the same optional `MeterRegistry` + `Tracer` (threaded through the 11-arg full constructor, 9-arg delegate). Same rules: `agentflow.ai.provider.embedding.latency.seconds` (timer), `agentflow.ai.provider.embedding.errors.total{reason}` (counter), `agentflow.ai.provider.embedding.tokens.total` (counter), and a closed `ResilientEmbeddingModel#embed` span on success/fault.

**`UsageMeteringService`** (`usage.application`, extended in place) takes an optional `MeterRegistry` (resolved in the `record(...)` path, never in the constructor — the constructor stays DB-free). After a successful record it increments `agentflow.usage.estimated.cost.usd` (counter, the `estimatedCost` the record carries — never an invented value; a zero/absent cost records nothing).

**`BoundedToolExecutor`** (`toolexecution.application`, extended in place) takes an optional `MeterRegistry` (4-arg full constructor → 1-arg delegate). A `agentflow.toolexecution.tool.duration.seconds{outcome}` timer is recorded in a `finally` after every concluded call (success, throttle, timeout or fault), tagged with the bounded `ToolInvocationOutcome` vocabulary.

**`RetrievalService`** (`knowledgebase.application`, extended in place) takes an optional `MeterRegistry` (threaded through the 3-arg full constructor, 2-arg delegate). `agentflow.knowledgebase.retrieval.chunks.returned.total` is incremented at conclusion of a hybrid retrieval (the fused chunk count, never an invented value). `usage` and `knowledgebase` keep the same contract shape: a null registry is a silent no-op.

**`IngestionService`** (`ingestion.application`, extended in place) takes an optional `MeterRegistry` (4-arg full constructor, 2-arg delegate). When indexing is never launched (transient provider fault, permanent extraction failure, or a dimension mismatch at the assembly step) it increments `agentflow.ingestion.index.failures.total` — one increment per failed indexing pass.

Metering everywhere is wrapped and debug-logged; a null registry is a silent no-op. The previous constructors are unchanged (the 9-arg/3-arg delegates keep the test seams).

### Why

S13-11/S13-12 shipped the workflow-execution and messaging families; the AI/RAG half was the missing dashboards/alerts surface. The plan (S13-13) names exactly the AI/RAG surfaces: provider-call latency, errors, tokens, estimated cost, tool duration, retrieval chunks returned and ingestion failures. Nothing here publishes a number the code does not have — every counter is incremented only on a successful fact or a real fault, never on a null/zero value, and no prompt/document text, tenant, run, user or document id is ever recorded.

### Verification

`./gradlew :apps:agentflow-app:test --tests 'ResilientProviderObservabilityTest' --tests 'ResilientChatModelTest' --tests 'ResilientEmbeddingModelTest' --tests 'BoundedToolExecutorTest' --tests 'RetrievalServiceTest' --tests 'UsageMeteringServiceTest' --tests 'IngestionServiceTest'` — BUILD SUCCESSFUL, 0 failures, 0 errors. Fresh JUnit XML verified: `ResilientProviderObservabilityTest` (6 — the four chat meters, the two embedding meters, the estimated-cost counter, the token counters, the tool-duration tag and the ingestion failure counter, all on a throwaway registry; plus two span cases with a real SDK tracer); `BoundedToolExecutorTest` (+2 — the timer tagged `succeeded`/`timeout`); `RetrievalServiceTest` (+1 — the chunks-returned counter = 1.0); `UsageMeteringServiceTest` (+2 — the cost counter on a successful record, zero/null-cost no-op); `IngestionServiceTest` (+2 — transient failure and permanent extraction failure increment the counter). `./gradlew :apps:agentflow-app:test` (whole unit suite) — BUILD SUCCESSFUL.

### Scope / follow-up

- No dashboard, alert or SLO is defined here (S13-17/18); `docs/observability/metrics.md` carries the new family.
- The production embedding-port wiring is unchanged — S13-13 instruments the existing `ResilientEmbeddingModel` (used in tests and the opt-in production path); the production embedding-port bean remains owned by the caller that builds the `EmbeddingPort`.

### Documentation

- [`docs/observability/metrics.md`](../observability/metrics.md) — the AI/RAG family added (names, types, units, labels, conventions and the downstream hooks).
- `docs/devlog/sprint-13-observability.md` (this section); `CHANGELOG.md` (this entry).

## S13-14 — API/JVM/DB dashboard

### What changed

The Grafana provisioning provider (S13-06) plugs into a real, operator-facing dashboard: the **API/JVM/DB** dashboard, rendered entirely from the dedicated Prometheus (the S13-05 store, `agentflow-prometheus` datasource), showing request rate and 5xx errors, p95 and average latency, heap/GC, CPU, threads live/peak, and the DB pool saturation (utilization, active/max, pending, timeouts).

**Wiring the DB pool meters** — [`HikariDataSourceMetricsConfiguration`](../../apps/agentflow-app/src/main/java/com/example/agentflow/config/HikariDataSourceMetricsConfiguration.java). Spring Boot 4.x registers the generic `jdbc.connections.*` gauges from the pool metadata provider, but it deliberately does NOT wire the HikariCP connection-level meters — the `hikaricp.connections.*` gauges (active/idle/max/min/pending) and the acquire/usage/creation timers live in HikariCP's own `MicrometerMetricsTracker`, and are opt-in. Without this class the DB-pool panels would have had no series. The class is `@ConditionalOnBean(HikariDataSource.class)` (so the DB-less skeleton boots unchanged) and hands the app's `MeterRegistry` to the one pool Boot builds — `dataSource.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry))` — so the meters appear from the first real scrape. No new dependency: `micrometer-registry` and `HikariCP` are already on the classpath.

**p95 latency needs buckets** — `management.metrics.distribution.percentiles-histogram: http.server.requests: true` in `application.yml`. Boot's default is a sum-and-count timer (no bucket series), which cannot render `histogram_quantile`. Percentile histograms (the default boundary set, ~10 buckets) are emitted for the HTTP server timer only, carrying only the existing bounded tags.

**Dashboard** — new [`infra/observability/grafana/dashboards/agentflow-api-jvm-db.json`](../../infra/observability/grafana/dashboards/agentflow-api-jvm-db.json), stable UID `agentflow-api-jvm-db`, `allowUiUpdates: false` provider: 12 panels, all pointing at `agentflow-prometheus`, units labelled in the titles and the field config. It is provisioned by the same S13-06 provider (`updateIntervalSeconds: 30`), so the acceptance — dashboard auto-loaded — holds on a clean instance.

**Scrape token hygiene** — the token file mounted by Prometheus (`infra/observability/prometheus/scrape/token`) was committed as a placeholder in S13-05; a real token was never committed, but the file itself was still tracked, so a `git add -A` would stage a live token. It is now untracked and gitignored (`.gitignore`), the mint script's warning comment now reflects the gitignored workflow, and `scripts/mint-scrape-token.sh` stays the only producer of the file.

### Why

S13-02/S13-05 made metrics scrapeable but nothing showed them; the plan (S13-14) names the dashboard and the metrics behind it. Boot does not wire the HikariCP tracker or percentile histograms by default — both had to be opted in for the dashboard to be a real surface rather than a placeholder page. The committed-token-in-Git trap was fixed while verifying the scrape: the file is now produced and consumed locally only.

### Verification

Live, this session, against the app running in the background:

1. Dashboard provisioned: `GET localhost:3000/api/dashboards/uid/agentflow-api-jvm-db` → `meta.provisioned: true`, `provisionedExternalId: agentflow-api-jvm-db.json`.
2. All 15 dashboard queries execute against the dedicated Prometheus (`/api/v1/query` / `/api/v1/query_range`) and return series — verified panel by panel: request rate 0.067 req/s, 5xx error rate 0 series, p95 latency 0.033 s, average latency 0.031 s, heap 100 MB, heap-vs-max 1.2 %, GC 0/min, CPU 0.07 %, threads 61 live / 70 peak, DB pool 0 active / 10 max / 10 idle / 0 pending / 0 timeouts.
3. App scrape: `up{job="agentflow-api"} = 1` after minting a fresh token (`bash scripts/mint-scrape-token.sh`); the `hikaricp_connections.*` series are present in the scrape (16 lines — active/idle/max/min/pending gauges plus the acquire/usage/creation timers).
4. `./gradlew :apps:agentflow-app:test --tests "com.example.agentflow.config.HikariDataSourceMetricsConfigurationTest" --rerun-tasks` — BUILD SUCCESSFUL, JUnit XML `tests="2" failures="0" errors="0"`.

### Scope / follow-up

- DB-pool panels show real data only once the app borrows a connection (any authenticated API request); a zero-traffic local boot keeps the idle/max series but never active/usage. That is the honest state of a dev boot and matches the plan — the panels are real, not aspirational.
- The histogram boundaries are the Boot defaults; S13-15 (Workflow/Kafka) and S13-19 (cardinality) own any tighter tuning.
- The `agentflow-overview.json` smoke dashboard is unchanged.

### Documentation

- [`infra/observability/README.md`](../../infra/observability/README.md) — scrape-token workflow (gitignored, not committed).
- [`docs/observability/metrics.md`](../observability/metrics.md) — the HikariCP pool family added.
- `CHANGELOG.md` (this entry).

## S13-15 — Workflow/Kafka dashboard

### What changed

The Grafana provisioning provider (S13-06) plugs into the second rich dashboard: the **Workflow/Kafka** operational dashboard, rendered entirely from the dedicated Prometheus (the S13-05 store, `agentflow-prometheus` datasource), showing runs by state, run/step durations (p95/p99), step retries, cancellations, lease recovery, outbox pending/age/attempts/retries, consumer lag/failures and approval expiries — plus links into Loki (logs) and Tempo (traces).

**Histogram opt-in for the workflow timers** — `application.yml` gains the same `percentiles-histogram` opt-in S13-14 used for the HTTP server timer, applied to the two workflow timers: `agentflow.workflowexecution.run.duration.seconds` and `agentflow.workflowexecution.step.duration.seconds`. Boot's default timer has no bucket series, so `histogram_quantile` had nothing to render. The buckets carry only the existing bounded tags (no state, no id).

**Dashboard** — new [`infra/observability/grafana/dashboards/agentflow-workflow-kafka.json`](../../infra/observability/grafana/dashboards/agentflow-workflow-kafka.json), stable UID `agentflow-workflow-kafka`, `allowUiUpdates: false` provider: 15 panels (a header + 14 operational panels), all pointing at `agentflow-prometheus`, units labelled in the titles and the field config. It is provisioned by the same S13-06 provider (`updateIntervalSeconds: 30`), so the acceptance — dashboard auto-loaded — holds on a clean instance. The links (Loki/Tempo Explore) are part of the dashboard contract, not a manual UI setup.

**What it deliberately does NOT do:** it does not invent metrics that do not exist. The run/step duration p95/p99 panels need buckets (the opt-in above); the queue/consumer-lag panels need the `workflow-worker` / `ingestion-worker` role, which the local API boot does not start. Empty panels are the honest state of a dev boot — the panels are real and provisioned, not aspirational.

### Why

The plan (S13-15) names the operational dashboard: runs by state, queue age, consumer lag, outbox age, retries, lease recovery and p95/p99, plus logs/traces links. The metrics were instrumented in S13-11 (workflow) and S13-12 (messaging), but nothing showed them; this is the surface that lets an operator see a stuck worker — pending/oldest-age/lag rising while runs by state stop moving — without peeking at the database.

### Verification

Live, this session, against the local stack (observability profile up; the app is the API role):

1. Dashboard provisioned: `GET localhost:3000/api/dashboards/uid/agentflow-workflow-kafka` → `meta.provisioned: true`, `provisionedExternalId: agentflow-workflow-kafka.json`.
2. All 16 panel queries execute against the dedicated Prometheus (`/api/v1/query_range`, 6h window) and return `status: success` — zero series, because the running role is the API and emits no workflow/messaging meters; the honest state, not a defect. The S13-14 dashboard (API metrics) shows real series by comparison.
3. The histogram opt-in is verified against the running scrape: `agentflow_workflowexecution_run_duration_seconds_bucket` and `agentflow_workflowexecution_step_duration_seconds_bucket` appear (`0` series — no terminal runs/steps yet); the HTTP server buckets are unchanged.
4. `./gradlew :apps:agentflow-app:check` — BUILD SUCCESSFUL (compile + unit + architecture + spotless + OpenAPI gate).

**Acceptance note (honest):** the "injected stuck worker" scenario in the plan is not exercised here — it requires the `workflow-worker`/`ingestion-worker` roles running against a live Kafka, which the local API boot does not start. The dashboard is provisioned and its queries execute; the metrics that would make the stuck-worker signal visible require the role-enabled profile, which is out of scope for this task.

### Scope / follow-up

- A stuck-worker drill (inject a worker that stops claiming runs, watch outbox age / lag / runs-by-state move on the dashboard) is the S15 deployment-sprint follow-up, together with the alert rules (S13-17) that consume the same metrics.
- The dashboard does not add new meters, alerts or runbooks — S13-17 owns alerts; the dashboard is the operator surface.
- The `agentflow-overview.json` smoke dashboard and `agentflow-api-jvm-db.json` are unchanged.

### Documentation

- [`infra/observability/README.md`](../../infra/observability/README.md) — dashboards list mention.
- [`docs/observability/metrics.md`](../observability/metrics.md) — the hooks section updated (the dashboard now exists).
- `docs/devlog/sprint-13-observability.md` (this file) + `CHANGELOG.md` (Unreleased / Added).

## S13-01 — telemetry conventions

### What changed

A new file, [`docs/observability/standards.md`](../observability/standards.md), fixes the telemetry conventions every later Sprint 13 task follows:

- **Three signal homes** — metrics (Micrometer → Prometheus/Actuator scrape), traces (OpenTelemetry → OTLP → Collector → Tempo), logs (slf4j → logback JSON encoder → Loki/Collector) — with an explicit "where to add what" rule.
- **Naming/units** — metrics are `agentflow.<domain>.<what>.<unit>` (`.seconds` / `.total` / `.count` / `.bytes` / `.rate`), span names follow the owning module's operation vocabulary, log lines carry the standard logstash fields.
- **Bounded labels** — closed, code-defined vocabularies per metric (`state`, `type`, `result`, `provider`, …); an explicit forbidden-label list (`tenantId`, `runId`, `userId`, `documentId`, prompts, payloads, raw errors, …); adding a label to an existing metric is a cardinality change, not a drive-by.
- **Trace attributes** — service identity (`service.name` / `service.version` / `deployment.environment`), causality (`correlation_id` / `causation_id` / `trace_id` / `span_id` — traces/logs only), operation identity (`agentflow.step.type`, `agentflow.run.state`, …), and bounded AI/RAG/tool attributes (`agentflow.ai.provider` / `prompt_version`, `agentflow.rag.retrieval.outcome`, `agentflow.tool.risk_class`, …).
- **Redaction** — three controls stand in front of every signal: the S01-19/S12-09 shared `SecretRedactor` masker (ADR-020) at the log boundary, bounded closed vocabularies at the trace/metric boundary, and redaction at storage boundaries (audit / DLQ, ADR-020).
- **Never-in-telemetry list** — secrets, prompts, document text, raw payloads/bodies/responses, high-cardinality IDs in labels or span names, raw error text.
- **Checklist** — the 6-step "adding a signal" checklist and the S13-19 cardinality/privacy enforcement note.

### Why

Sprint 13 wires Micrometer/Actuator (S13-02), the OTel exporter (S13-03), the Collector/Prometheus/Grafana/Loki/Tempo pipeline (S13-04…S13-08), the workflow/messaging/AI-RAG meters (S13-11…S13-13) and the Redis helper meters (S13-22). Every one of those tasks needs the same rules: where a signal lives, what it may carry, and what it may never carry. Fixing them up front — before any meter exists — means later tasks implement a written contract instead of inventing per-module conventions that drift apart. This is a documentation-only change: no code, no contracts, no ADR.

### Acceptance

A developer reads `docs/observability/standards.md` and knows, in one place: which home each signal type lives in, where to add a new signal, what naming/units are expected, which labels are allowed and which are forbidden, which trace attributes are permitted, and what must never be sent in any signal.

### Verification

Documentation-only change — no build/test gate applies. Verified:

- Every relative link in `standards.md` resolves against the repo (`../domain-glossary.md`, `../architecture/module-map.md`, `../security/secrets.md`, `../adr/ADR-020-shared-redaction-in-common.md`, `../adr/ADR-021-redis-role.md`).
- The conventions are anchored to existing repo evidence: glossary §12 (Correlation / trace ID, Metric label), the observability module's `AGENTS.md` rules, `logback-spring.xml` (encoder + masker), `LoggingContextProperties` (MDC service/environment), `GlobalExceptionHandler` (`traceId` in MDC), and `ADR-020`.
- `docs/README.md` (tree + table) marks `standards.md` as the S13-01 deliverable and keeps the catalogue/SLOs marker as planned; `observability/AGENTS.md` now points at the doc.

### Documentation

- `docs/observability/standards.md` (new) — the conventions themselves.
- `docs/README.md` — tree + table row updated.
- `observability/AGENTS.md` — new line pointing at the doc.
- `docs/devlog/sprint-13-observability.md` (this file) + `docs/devlog/README.md` (new Contents line).
- `CHANGELOG.md` (Unreleased / Added).

### Scope / follow-up

The metric catalogue, SLOs, dashboards, alerts and the cardinality/privacy check remain scheduled (S13-11…S13-22) and must all follow this file. The `observability` module gains no code in this task.

## S13-02 — Micrometer/Actuator metrics

### What changed

The application now exposes a Prometheus scrape endpoint and a bounded metrics surface, so the Sprint 13 observability stack (Collector → Prometheus) has something real to scrape.

- **Dependency** — `io.micrometer:micrometer-registry-prometheus` (BOM-managed, rides the Micrometer line) is added to `apps/agentflow-app/build.gradle.kts` behind the existing `spring-boot-starter-actuator`. Without it, `/actuator/prometheus` does not exist at all — the endpoint only exists when the registry is on the runtime classpath.
- **Endpoint exposure** — `/actuator/prometheus` is added to `management.endpoints.web.exposure.include` in `application.yml`. It is **not** in the permit-all diagnostics set: it stays behind the default-deny secured chain (S04-11), so a scrape must carry a bearer token (the internal Prometheus server's `Authorization` header) and an external anonymous client gets `401`. The `health` / `info` probes remain permitAll (orchestrator). The internal-network restriction is enforced at the deployment layer (Kubernetes NetworkPolicy, S15) — the metrics surface never leaves the cluster.
- **Common tag** — every meter carries `application: <app-name>` (the one bounded identity tag the S13-01 standards require); no high-cardinality tags.
- **Disabled instrument groups** — `spring.data.redis`, `logback` and `cache` are switched off via `management.metrics.enable:`. HTTP/server (tomcat `http.server.requests`), JVM and DB-pool (HikariCP) auto-configuration stay enabled, so the S13-02 criterion — HTTP/JVM/DB-pool metrics up — holds. Redis-helper meters are S13-22's surface; logback event counters add nothing over the JSON stdout log; no caches are configured.

### Why

The telemetry standards (S13-01) assign metrics to one home — Micrometer → Prometheus/Actuator scrape — but the endpoint did not exist and no metrics were exposed. This change wires the registry, the endpoint, the common tag and the disabled groups, so the next tasks (S13-04…S13-08, S13-11…S13-22) have a minimal, honest metrics surface to build on.

### Verification

`PrometheusMetricsEndpointTest` (new, `secured-test` profile, `@SpringBootTest` on a random port) — 3 tests, fresh this run:

1. **Anonymous scrape is rejected** — `GET /actuator/prometheus` without a token → `401`.
2. **Authenticated scrape carries the JVM and HTTP metrics** — with a valid bearer token → `200`, `text/plain`, body contains `jvm_memory_used_bytes` and `http_server_requests` and the common `application="agentflow-app"` tag.
3. **The disabled instrument groups are absent** — the body contains no `spring_data_redis`, no `logback_events`, and no metric name starting with `cache_` (a line-prefix match, not a substring — the spring-security `requestcache_` metric is legitimately present).

DB-pool (hikaricp) meters are not asserted: the app boots without a DataSource on the secured-test profile, so the HikariCP meters are not on the scrape here. They ride the same auto-configuration that the JVM and HTTP meters use; they are exercised by the profiles that wire a real DataSource.

`./gradlew :apps:agentflow-app:check` green — unit + architecture + no-cycles + formatting + OpenAPI. `generateLockFiles --write-locks` was run so `gradle.lockfile` pins the new registry for every classpath configuration (compile/runtime/productionRuntime/test).

### Documentation

- `docs/devlog/sprint-13-observability.md` (this file) + contents line.
- `CHANGELOG.md` (Unreleased / Added).

### Scope / follow-up

Metrics-only: no OTLP tracing (S13-03), no dashboards (S13-14…S13-16), no Redis/rate-limit meters (S13-22), no Collector/Prometheus/Grafana provisioning (S13-04…S13-08), no NetworkPolicy (S15). The scrape requires a bearer token; the token is the Prometheus server's own — it is infrastructure, not an app secret, and remains out of the repo (S15).

## S13-03 — OTLP tracing exporter

### What changed

Every HTTP request is now exported as an OpenTelemetry span to an OTLP endpoint, so the Sprint 13 pipeline (Collector → Tempo, S13-04/S13-08) has real traces to carry, and the exporter contract is configuration, not code.

- **Dependency** — `org.springframework.boot:spring-boot-starter-opentelemetry` (BOM-managed) is added to `apps/agentflow-app/build.gradle.kts`. The Boot 4 starter is the piece that matters: it pulls the Micrometer Tracing OTel bridge (`micrometer-tracing-bridge-otel`), the OTel SDK autoconfiguration modules (`spring-boot-opentelemetry`, `spring-boot-micrometer-tracing-opentelemetry`) and the OTLP span exporter (`io.opentelemetry:opentelemetry-exporter-otlp`). Without the starter's autoconfiguration modules on the classpath, nothing reads the `management.opentelemetry.tracing.export.otlp.*` properties and no span exporter is ever created.
- **Sampling** — `management.tracing.sampling.probability: 1.0` in the base profile (Boot's default is 10%, `parent-based-trace-id-ratio`): every request is traced in dev/test, and the kubernetes profile can lower the same property when the volume demands it.
- **Service attributes** — per the S13-01 standards: `service.name` is the application name (the auto-configured `Resource` maps `spring.application.name`), and the bounded `deployment.environment` comes from `management.opentelemetry.resource-attributes` = `${AGENTFLOW_DEPLOYMENT_ENVIRONMENT:local}` — configuration-fixed, never taken from a request.
- **Endpoint from configuration** — the exporter settings are plain Boot properties, also mapped from the `OTEL_EXPORTER_OTLP_*` environment variables. The base profile carries no endpoint (Boot's `localhost:4318` default applies, and a missing collector never fails a request — export is asynchronous); the local profile points the common `management.opentelemetry.otlp.endpoint` at `${AGENTFLOW_OTLP_ENDPOINT:http://localhost:4318}` (Boot appends `/v1/traces`) for the S13-04 Collector. The kubernetes profile gets its in-cluster Collector endpoint in the deployment sprint (S15).
- **No OTLP metrics push** — the starter transitively drags in `micrometer-registry-otlp`, whose push export defaults to ON (posting to `localhost:4318` on a schedule). It is explicitly disabled (`management.otlp.metrics.export.enabled: false`): metrics ship through the Prometheus scrape (S13-02), never through OTLP push.
- **No raw body in spans** — HTTP instrumentation never captures request/response bodies; the test below pins that behavior over the wire.
- **Knock-on** — with tracing wired, the MDC `traceId` is now the request's OTel trace id (32 hex), which is exactly the `GlobalExceptionHandler#correlationId()` documented contract ("the current trace id when tracing is wired"); the handler-test regex was widened to accept either shape.

### Why

S13-01 assigned traces to one home (OpenTelemetry → OTLP → Collector → Tempo) and S13-02 gave the stack metrics to scrape; without the exporter there were no traces at all. Wiring the Boot-managed exporter — rather than hand-building an SDK — keeps every version on the Spring Boot BOM (no pins, no drift) and keeps endpoint/sampling/environment in configuration, which is where the plan puts them.

### Verification

`OtlpTracingExportTest` (new, `test` profile, `@SpringBootTest` on a random port) — 2 tests, JUnit-XML-verified (`tests="2"` `failures="0"`, fresh this run). The test drives the **real** OTLP exporter: `@DynamicPropertySource` points `management.opentelemetry.tracing.export.otlp.endpoint` at an in-process JDK `HttpServer` collector and sets compression to `none`, so the captured payloads are the actual protobuf a Collector receives — no exporter seam is mocked; spans are pushed deterministically via `SdkTracerProvider.forceFlush()`.

1. **A request is exported with stable service attributes** — `GET /actuator/health` → `200`; after the flush, an exported span named after the route is present in the payloads (`get /actuator/health` on the wire — Micrometer names HTTP spans with a lowercase method, the observed format), and the auto-configured `Resource` carries `service.name=agentflow-app` and `deployment.environment=test`.
2. **Spans never carry the raw request body** — a POST to `/api/v1/runs` with a unique marker in the body is exported as a span (its route appears in the payload), and the marker appears in **no** captured OTLP payload — protobuf encodes strings as raw UTF-8, so any leak would survive verbatim.

`GlobalExceptionHandlerTest` — 5 tests, JUnit-XML-verified fresh this run (`failures="0"`); the two `correlation_id` shape assertions now accept the wired-tracing trace id (`[0-9a-f]{32}`) alongside the no-context UUID (`[0-9a-f-]{36}`).

`./gradlew :apps:agentflow-app:check --rerun-tasks` green — unit + architecture (no module edge: all wiring is Boot auto-configuration, the test lives in the `observability` package and imports only external OTel SDK / JDK types) + formatting (`spotlessCheck`) + OpenAPI contract. `generateLockFiles --write-locks` regenerated `gradle.lockfile` (the OTel line pinned for every classpath configuration).

### Documentation

- `docs/devlog/sprint-13-observability.md` (this file) + contents line.
- `CHANGELOG.md` (Unreleased / Added).
- `docs/observability/standards.md` — needs no change: its S13-03 references ("the traces exporter reads its endpoint/environment/sampling from configuration … and never carries a raw request body") describe exactly what is now wired.

### Scope / follow-up

Traces-only: the Collector, Prometheus, Grafana, Loki and Tempo services (S13-04…S13-08), the workflow/messaging/AI-RAG trace attributes (`agentflow.operation`, `agentflow.step.*`, `agentflow.ai.*` — S13-11…S13-13), the correlation/causation attributes on the outbox/worker hops and the cardinality/privacy check (S13-19) remain scheduled. The kubernetes profile's OTLP endpoint lands with the in-cluster Collector (S15).

## S13-07 — Loki as code

### What changed

The local observability stack gains a dedicated, repo-owned Loki — the log store of the split-stack target — ingesting logs over the native OTLP endpoint, with the label contract enforced Loki-side, so high-cardinality IDs can never become index labels.

- **Config** — new [`infra/observability/loki/loki.yaml`](../../infra/observability/loki/loki.yaml), mounted read-only and booting the single-binary Loki (distributor, ingester, querier, compactor): filesystem storage (`chunks_directory`/`rules_directory`/`compactor.working_directory` under `/loki`), `schema_config` TSDB v13 from `2026-01-01`, replication factor 1 / in-memory ring (single-instance local stack, matching the lgtm all-in-one's single-node use), `allow_structured_metadata: true`, `reject_old_samples: true`, 7-day retention via `limits_config.retention_period: 168h` (enforced by the compactor on the filesystem delete-request store) — matching the dedicated Prometheus.
- **Label contract** — `limits_config.otlp_config`: `ignore_defaults: true` drops Loki's 17-attribute default index-label list, the only `index_label` rules are `service.name` and `deployment.environment`, and every other resource attribute falls back to **structured metadata** (Loki's `actionForAttribute` default) — same for scope and log attributes (`structured_metadata`, regex `.*`). The index is therefore bounded by construction, never by convention: a tenant/run/user/document ID arriving as an attribute lands in per-entry structured metadata and cannot index-label a stream. A `run_id`-shaped value visible in a query response is a display quirk of Loki 3.x (structured metadata is returned alongside stream labels) — the authoritative index label set is `/loki/api/v1/labels`.
- **Compose** — new `loki` service in [`infra/compose/docker-compose.yml`](../../infra/compose/docker-compose.yml): `grafana/loki:3.6.1` pinned by multi-arch index digest (`sha256:92bd57…a88389`), `command: ["-config.file=/etc/loki/loki.yaml"]`, host :3100 (the lgtm bundled Loki no longer host-publishes — the dedicated service owns the port, the S13-04 port precedent), `agentflow-loki` named volume, healthcheck via the binary's own `-verify-config` (distroless, no shell — the same pattern as the otel-collector), `logging: *shared-logging`.
- **Collector** — the logs pipeline exports through a new `otlp_http/loki` exporter (`http://loki:3100/otlp`, `tls.insecure: true`) — the contrib collector ships no loki exporter in the 0.165 line (the old `loki` exporter was removed), so native OTLP ingestion is the supported path. The redaction/attributes pipeline is unchanged; the exporter sends attributes verbatim — the label decision is made by Loki's `otlp_config`, not by the exporter. Traces and metrics still fan out to the lgtm bundled collector until S13-08.
- **Grafana** — the `loki` datasource UID (stable contract) now points at `http://loki:3100` — the URL half of the S13-06/07 swap; only the URL moved.
- **Smoke** — `scripts/smoke-local.sh` expects `agentflow-loki` in the `observability`/`full` profiles.

### Why

S13-05 gave the stack a repo-owned metrics store and S13-06 made Grafana Git-provisioned, but the logs still lived only inside the lgtm all-in-one image — the collector's logs pipeline fanned out to it, so the log store was nobody's code. Loki 3.x added native OTLP ingest (`/otlp/v1/logs`), which is the supported path for a dedicated log store behind the collector (the contrib collector has no loki exporter in 0.165). Taking the dedicated Loki now — the label contract in Git, the index bounded by construction — turns the log half of the split-stack target into repo-owned configuration, exactly like Prometheus did.

### Verification

Live run, this session, from a **wiped state** (`docker compose --profile observability down -v` — drops the `agentflow-loki`, `agentflow-prometheus` and `agentflow-observability` volumes — then `up -d`). This is the acceptance clean-instance scenario.

1. `docker compose config --quiet` — exit 0.
2. Clean instance: `agentflow-loki` + `agentflow-otel-collector` + `agentflow-observability` + `agentflow-prometheus` all `healthy`.
3. `GET /loki/api/v1/labels` → `["deployment_environment","service_name"]` — only the two bounded labels, nothing else (the numbered `nonce` assertions below confirm it stays that way).
4. A probe OTLP/JSON log posted to `localhost:4318/v1/logs` (via the collector's logs pipeline) carrying `service.name=agentflow-app`, `deployment_environment=local`, `tenant.id=…` and `run.id=a1b2c3d4-…` with a `correlation_id` scope/log attribute is visible via the `{service_name="agentflow-app"}` query; `run_id`/`tenant_id`/`correlation_id` appear in the stream object as per-entry structured metadata (48 bytes processed) — **not** as index labels.
5. `{tenant_id="8b3d9a2f-…"}` — empty result; `tenant.id` can never index-label a stream however it is emitted.
6. Grafana's `GET /api/datasources` — `loki` → `http://loki:3100`.
7. `bash scripts/smoke-local.sh --profile observability` — SMOKE TEST: PASS (all four services healthy from a clean state).

No Java/Gradle change — the compose/config validation and the live runs above are the verification.

### Documentation

- [`infra/observability/loki/loki.yaml`](../../infra/observability/loki/loki.yaml) (new).
- [`infra/observability/README.md`](../../infra/observability/README.md) — layout row, the why-lgtm paragraph (the S13-07 swap), the S13-07 configuration bullet, the endpoints table.
- [`infra/compose/docker-compose.yml`](../../infra/compose/docker-compose.yml) — the `loki` service, the `agentflow-loki` volume, the collector exporter comment, the lgtm service/port comments.
- [`infra/observability/otel-collector/collector.yaml`](../../infra/observability/otel-collector/collector.yaml) — the logs pipeline (`otlp_http/loki`) and header.
- [`infra/observability/grafana/provisioning/datasources/agentflow.yaml`](../../infra/observability/grafana/provisioning/datasources/agentflow.yaml) — the `loki` URL (`http://loki:3100`).
- [`scripts/smoke-local.sh`](../../scripts/smoke-local.sh) — expected service + display entry.
- `docs/devlog/sprint-13-observability.md` (this file) + contents line; `CHANGELOG.md` (Unreleased / Added).

### Scope / follow-up

Trace and metric fan-out to dedicated services remains S13-08 (Tempo) and S13-07/08 (collector exporter swap for traces/metrics). The Grafana alert rules and dashboards stay on the Git-provisioned provider; the Loki datasource is the URL half of the swap the S13-06 promise made. The kubernetes log pipeline (in-cluster Loki, S15) reuses the same file layout.

## S13-08 — Tempo as code

### What changed

The local observability stack gains a dedicated, repo-owned Tempo — the trace store of the split-stack target — ingesting traces over the OTLP endpoint, so the trace half of the split-stack move becomes repo-owned configuration, exactly like Prometheus did.

- **Config** — new [`infra/observability/tempo/tempo.yaml`](../../infra/observability/tempo/tempo.yaml), mounted read-only and booting the single-binary Tempo (distributor, ingester, querier, compactor): the OTLP/gRPC + HTTP receivers (`0.0.0.0:4317` / `0.0.0.0:4318`), local blocks + WAL on the `agentflow-tempo` named volume (`/var/tempo/blocks`, `/var/tempo/wal`), `compactor.compaction.block_retention: 168h` (7-day), `compaction` on both block stores; replication factor 1 / in-memory ring (single-instance local stack).
- **Sampling policy** — head sampling at the app (S13-03), unchanged: `management.tracing.sampling.probability: 1.0` boots the local stack at 100% of requests (Boot's default is 10%), the collector adds **no** sampling processor, and Tempo stores everything it receives. The kubernetes profile (S15) is the only place that lowers the rate when real volume demands it.
- **Compose** — new `tempo` service in [`infra/compose/docker-compose.yml`](../../infra/compose/docker-compose.yml): `grafana/tempo:2.9.2` pinned by multi-arch index digest (`sha256:3f0825e1…9fb74b40`), `command: ["-config.file=/etc/tempo/tempo.yaml"]`, host :3200 (the lgtm bundled Tempo no longer host-publishes — the dedicated service owns the port), `agentflow-tempo` named volume, healthcheck via the binary's own `-config.verify=true` (distroless, no shell), `logging: *shared-logging`.
- **Collector** — the traces pipeline exports through a new `otlp_grpc/tempo` exporter (`tempo:4317`, `tls.insecure: true`); redaction/attributes/batch unchanged. **Metrics still fan out to the lgtm bundled collector** (the app-pushed OTLP metrics pipeline is the S13-04 contract, unchanged).
- **Grafana** — the `tempo` datasource UID (stable contract) now points at `http://tempo:3200` — the URL half of the S13-06/08 swap; only the URL moved. Grafana's `agentflow-prometheus` datasource exemplar destinations (`tracesToLogsV2`/`nodeGraph`/`search`/`serviceMap`) are unchanged; the `loki` URL from S13-07 stays.
- **Smoke** — `scripts/smoke-local.sh` expects `agentflow-tempo` in the `observability`/`full` profiles.

### Why

S13-05–S13-07 gave the stack repo-owned metrics/logs stores, but the traces still lived inside the lgtm all-in-one image — the collector's traces pipeline fanned out to it, so the trace store was nobody's code. S13-08 closes the split-stack target: the dedicated Tempo, the OTLP/gRPC exporter, the 7-day retention and the Git-provisioned Grafana datasource together make the whole observability pipeline (Prometheus → metrics, Loki → logs, Tempo → traces) configuration this repository owns.

### Verification

Live run, this session, from a **wiped state** (`docker compose --profile observability down -v` — drops the `agentflow-tempo`, `agentflow-loki`, `agentflow-prometheus` and `agentflow-observability` volumes — then `up -d`). This is the acceptance clean-instance scenario.

1. `docker compose config --quiet` — exit 0.
2. Clean instance: `agentflow-tempo` + `agentflow-otel-collector` + `agentflow-observability` + `agentflow-prometheus` + `agentflow-loki` all `healthy`.
3. `docker inspect agentflow-tempo --format '{{json .State.Health}}'` — healthy; the healthcheck re-validates the mounted config with `-config.verify=true`.
4. A probe OTLP/JSON trace posted to `localhost:4318/v1/traces` (via the collector's traces pipeline) carrying `service.name=agentflow-app`, `deployment.environment=local`, `correlation.id`, a current timestamp and `service.name=agentflow-app` is visible via the dedicated Tempo: `GET localhost:3200/api/search?tags=service.name=agentflow-app` returns the trace with its trace ID, root span (`post /api/v1/workflows`) and 5 s duration.
5. Grafana end-to-end: the `tempo` datasource proxies the same search — `GET localhost:3000/api/datasources/proxy/uid/tempo/api/search?tags=service.name=agentflow-app` returns the trace, no manual datasource setup; `GET /api/datasources/uid/tempo` is provisioned with `http://tempo:3200` (editable: false).
6. `bash scripts/smoke-local.sh --profile observability` — SMOKE TEST: PASS (all five observability-profile containers healthy from a clean state).

No Java/Gradle change — the compose/config validation and the live runs above are the verification.

### Documentation

- [`infra/observability/tempo/tempo.yaml`](../../infra/observability/tempo/tempo.yaml) (new).
- [`infra/observability/README.md`](../../infra/observability/README.md) — layout row, the why-lgtm paragraph, the S13-08 configuration bullet, the endpoints table, the S13-08 acceptance section.
- [`infra/compose/docker-compose.yml`](../../infra/compose/docker-compose.yml) — the `tempo` service, the `agentflow-tempo` volume, the collector exporter comment, the lgtm service/port comments.
- [`infra/observability/otel-collector/collector.yaml`](../../infra/observability/otel-collector/collector.yaml) — the traces pipeline (`otlp_grpc/tempo`) and header.
- [`infra/observability/grafana/provisioning/datasources/agentflow.yaml`](../../infra/observability/grafana/provisioning/datasources/agentflow.yaml) — the `tempo` URL (`http://tempo:3200`).
- [`scripts/smoke-local.sh`](../../scripts/smoke-local.sh) — expected service + display entry.
- `docs/devlog/sprint-13-observability.md` (this file) + contents line; `CHANGELOG.md` (Unreleased / Added).

### Scope / follow-up

Metrics fan-out to a dedicated Prometheus-over-OTLP remains future work (the app-pushed OTLP metrics pipeline is the S13-04 contract — the dedicated Prometheus already scrapes the app; the bundled lgtm collector receives the app-pushed stream). The kubernetes pipeline (in-cluster Tempo, S15) reuses the same file layout.

## S13-06 — Grafana provisioning as code

### What changed

Grafana (inside the pinned lgtm all-in-one) now boots entirely from Git — datasources, the dashboard provider, dashboards and alert rules are provisioned files, and a manual UI edit is refused by the API, so a change is a Git change or it does not exist.

- **Provisioning configs** — new [`infra/observability/grafana/provisioning/`](../../infra/observability/grafana/provisioning/), mounted read-only at `/etc/grafana/provisioning`: [`datasources/agentflow.yaml`](../../infra/observability/grafana/provisioning/datasources/agentflow.yaml) declares **every** datasource of the stack with stable UIDs — the dedicated `agentflow-prometheus` (`http://prometheus:9090`, the S13-05 store, `timeInterval: 15s` matching the scrape interval) plus exact declarations of the lgtm-internal Prometheus/Loki/Tempo/Pyroscope entries (original UIDs, URLs and the trace-link `jsonData`) that Grafana 13 would otherwise auto-create. Two image facts forced the all-datasources-in-Git shape, both verified on the pinned image: Grafana 13 enforces `[paths] permitted_provisioning_paths` (provisioning from an arbitrary mount is silently rejected until the directory is selected via `GF_PATHS_PROVISIONING` AND permitted via `GF_PATHS_PERMITTED_PROVISIONING_PATHS`), and auto-creation of the lgtm defaults stops the moment any datasource provisioning exists. [`dashboards/agentflow.yaml`](../../infra/observability/grafana/provisioning/dashboards/agentflow.yaml) wires the dashboards directory as the AgentFlow provider (`allowUiUpdates: false`, `disableDeletion: true`); [`alerting/agentflow-alerts.yaml`](../../infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml) declares the alert rules in the stable Grafana 13 shape (instant PromQL → reduce → threshold, no classic_conditions).
- **Dashboard** — new [`dashboards/agentflow-overview.json`](../../infra/observability/grafana/dashboards/agentflow-overview.json), stable UID `agentflow-overview`: four panels over the dedicated Prometheus (API/collector `up`, collector uptime, collector RSS vs the 256 MiB memory-limiter ceiling). It is the provisioning smoke surface — the rich API/JVM/DB and Workflow/Kafka dashboards land in S13-14/15 and plug into the same provider.
- **Alert rule + runbook** — `agentflow-scrape-target-down` (uid-stable): `up{job=~"agentflow-api|otel-collector"}` reduce-last, threshold `< 1`, `for: 1m`, `noDataState: Alerting` (a target vanishing from discovery is as alertable as `up == 0`), `severity: warning`, `runbook_url` into the new [`docs/runbooks/observability-scrape-target-down.md`](../../docs/runbooks/observability-scrape-target-down.md) (symptoms, the expected local-dev state, diagnostics, safe actions, recovery criterion — the infra/AGENTS.md contract for alerts).
- **Compose** — the lgtm service gains the two `GF_PATHS_*` env vars and the read-only mounts of `grafana/provisioning/` and `grafana/dashboards/` ([`infra/compose/docker-compose.yml`](../../infra/compose/docker-compose.yml)); no new service, no port changes.

### Why

S13-05 gave the stack a repo-owned metrics store, but everything Grafana showed still lived inside the digest-pinned lgtm image — its datasources were created by Grafana's auto-connection feature at runtime, invisible to Git and unreviewable. The plan's S13-06 makes the whole Grafana surface configuration as code; declaring all datasources explicitly (rather than mixing provisioning with auto-creation) keeps the file the single source of truth and turns the S13-07/08 backend swaps into URL edits.

### Verification

Live run, this session, from a **wiped Grafana state** (`docker compose --profile observability down -v`, volume dropped, then `up -d`) — the acceptance is exactly this clean-instance scenario:

1. `docker compose config --quiet` — exit 0.
2. Grafana healthy on :3000; `GET /api/datasources` lists all five datasources from Git, including `agentflow-prometheus → http://prometheus:9090`.
3. `GET /api/dashboards/uid/agentflow-overview` — the dashboard with 4 panels, `meta.provisioned: true`.
4. `GET /api/v1/provisioning/alert-rules` — the rule with `for: 1m`, `severity: warning` and the runbook annotation; the evaluation endpoint shows it **firing** for `agentflow-api` (up=0, app not running — expected per the runbook) and Normal for `otel-collector` after the `for` guard elapsed.
5. UI-only change prohibition proven at the API: `POST /api/dashboards/db` for the provisioned UID → `Cannot save provisioned dashboard`.
6. `bash scripts/smoke-local.sh --profile observability` — SMOKE TEST: PASS.

No Java/Gradle change — the compose/config validation and the live runs above are the verification.

### Documentation

- `infra/observability/grafana/provisioning/{datasources,dashboards,alerting}/` + `infra/observability/grafana/dashboards/agentflow-overview.json` (new).
- [`infra/observability/README.md`](../../infra/observability/README.md) — layout rows, the provisioning configuration bullet, the S13-06 acceptance recipe.
- [`infra/compose/docker-compose.yml`](../../infra/compose/docker-compose.yml) — env vars, mounts, comments.
- [`docs/runbooks/observability-scrape-target-down.md`](../../docs/runbooks/observability-scrape-target-down.md) (new).
- `docs/devlog/sprint-13-observability.md` (this file) + contents line; `CHANGELOG.md` (Unreleased / Added).

### Scope / follow-up

Rich dashboards (API/JVM/DB — S13-14, Workflow/Kafka — S13-15) and notification policies/contact points (alert routing, S13-14/15) plug into this provider; S13-07/08 swap the lgtm-internal Loki/Tempo datasource URLs and the collector exporter endpoints to dedicated services. The kubernetes Grafana provisioning (external Grafana, S15) reuses the same file layout.

## S13-05 — Prometheus as code

### What changed

The local observability stack gains a dedicated, repo-owned Prometheus — the metrics store of the split-stack target (S13-04 → S13-05…S13-08) is now real for metrics.

- **Config** — new [`infra/observability/prometheus/prometheus.yml`](../../infra/observability/prometheus/prometheus.yml), mounted read-only: 15 s scrape/evaluation intervals, `external_labels.environment: local` (bounded, configuration-fixed) and two jobs — `agentflow-api` (the application's `/actuator/prometheus`, S13-02 contract; the endpoint sits behind the default-deny secured chain, so the job carries a bearer token from `/etc/prometheus/scrape/token`) and `otel-collector` (the Collector's own metrics, exposed by a new `prometheus` telemetry reader on :8888 in [`collector.yaml`](../../infra/observability/otel-collector/collector.yaml) — `detailed` level, bounded component-id labels, internal network only).
- **Compose** — [`infra/compose/docker-compose.yml`](../../infra/compose/docker-compose.yml) gains the `prometheus` service: image pinned by multi-arch index digest (`prom/prometheus:v3.6.0` → `sha256:76947e…`), versioned retention as command flags (`--storage.tsdb.retention.time=7d`, `--storage.tsdb.retention.size=2GB` — Prometheus keeps retention a startup flag, not a config key), TSDB on the `agentflow-prometheus` named volume, healthcheck via the image's busybox `wget` against `/-/healthy`, a `host-gateway` alias so the host-running app resolves on every engine. Per the S13-04 port-precedent (the collector took over the host OTLP ports), the dedicated service now owns host :9090 and lgtm's bundled Prometheus stops being host-published — Grafana keeps using it internally until S13-06/07/08 swap datasources and collector exporters.
- **Scrape token** — [`scripts/mint-scrape-token.sh`](../../scripts/mint-scrape-token.sh) mints a short-lived JWT (Client Credentials, `agentflow-service` m2m client, same local-only secret default as the Keycloak service) and rewrites the mounted token file; Prometheus re-reads `bearer_token_file` before every scrape (verified on the pinned image with a header-echo probe), so the 300 s Keycloak token TTL only needs a file refresh — `--watch` loops at 240 s. The [`scrape/token`](../../infra/observability/prometheus/scrape/token) file is produced and consumed locally only (S13-14 — gitignored, never committed; the committed placeholder was removed).
- **Smoke** — `scripts/smoke-local.sh` now expects `agentflow-otel-collector` and `agentflow-prometheus` in the `observability`/`full` profiles (the collector expectation was missing since S13-04).

### Why

S13-02 made metrics ship through the Prometheus scrape (never OTLP push) and S13-04 made the Collector the repo-owned OTLP ingress, but the metrics store itself still lived inside the digest-pinned lgtm all-in-one — its scrape config was not this repo's. The plan's S13-05 makes Prometheus configuration as code (`prometheus.yml`, scrape intervals, retention, targets for the API and the Collector, pinned image); standing the dedicated service up now and letting it scrape the app directly turns the S13-02 contract into an observable pipeline while Grafana keeps working off lgtm until S13-06/07/08.

### Verification

Live run, this session, from a clean state:

1. `docker compose config --quiet` — exit 0; collector `validate` on the pinned image — exit 0 with the new telemetry reader.
2. Observability profile up: `agentflow-prometheus` healthy (wget healthcheck), `agentflow-otel-collector` healthy.
3. **Acceptance — config restores from Git after container removal.** `docker rm -f agentflow-prometheus` → `docker compose --profile observability up -d` → recreated healthy from the mounted Git config; `/api/v1/targets` lists both jobs; `up{job="otel-collector"} = 1`.
4. Collector self-metrics scraped over the observability network: `otelcol_process_uptime` / `otelcol_process_memory_rss` / `otelcol_exporter_queue_size` present (bounded labels).
5. **End-to-end authenticated app scrape** — `core`+`identity` profiles up (PostgreSQL, Keycloak), app on `bootRun` (local profile, real DataSource): `bash scripts/mint-scrape-token.sh` → within one scrape interval `up{job="agentflow-api"} = 1` with `jvm_memory_used_bytes` (6 series), `http_server_requests_seconds_count` (3 series) and `hikaricp_connections` (the DB-pool half the S13-02 test could not pin without a DataSource). Placeholder token restored after the run.
6. `bash scripts/smoke-local.sh --profile observability` — SMOKE TEST: PASS (all three containers healthy; stack torn down after).

No Java/Gradle change — the compose/config validation, the live runs above and the smoke script are the verification.

### Documentation

- `infra/observability/prometheus/prometheus.yml` (new) — the Prometheus config with the boundary contract in the header comments.
- `infra/observability/otel-collector/collector.yaml` — the `prometheus` telemetry reader (:8888).
- [`infra/observability/README.md`](../../infra/observability/README.md) — layout, endpoints (:9090 now the dedicated Prometheus), configuration, the scrape-token section, the S13-05 acceptance recipe (the S13-04 backend smoke is kept).
- [`infra/compose/docker-compose.yml`](../../infra/compose/docker-compose.yml) — the new service, the lgtm :9090 change and updated comments.
- Root [`README.md`](../../README.md) — port table gains Prometheus :9090.
- `scripts/smoke-local.sh` — observability/full expectations.
- `docs/devlog/sprint-13-observability.md` (this file) + contents line; `CHANGELOG.md` (Unreleased / Added).

### Scope / follow-up

Grafana provisioning (datasources pointing at the dedicated Prometheus, dashboards) is S13-06; dedicated Loki/Tempo and the collector exporter swap are S13-07/08. Until then, lgtm's bundled Prometheus serves Grafana internally and receives the OTLP-carried app metrics; the dedicated Prometheus already owns the canonical scrape of the app and the collector. The kubernetes scrape token / NetworkPolicy surface is S15.

## S13-04 — OTel Collector config as code

### What changed

The standalone `otel-collector` service is now the OTLP ingress of the local observability stack. Previously the app exported directly into the lgtm all-in-one's embedded collector; now the ingress contract — memory limit, batching, attribute redaction, health check — is this repo's configuration, independent of the all-in-one image.

- **Config** — new [`infra/observability/otel-collector/collector.yaml`](../../infra/observability/otel-collector/collector.yaml), mounted read-only over the image default: `otlp` receivers (:4317 gRPC / :4318 HTTP), `memory_limiter` (256 MiB ceiling, 64 MiB spike, 1 s check interval), `attributes/redaction` (deletes `http.request.body` / `http.response.body` / `prompt*` / `document.*` attributes — defense in depth on top of the S13-03 app-side guarantee; the collector only removes attributes, it never invents them), `batch` (5 s timeout, 8 192 send size, 16 384 max), `health_check` extension on :13133, and one `otlp_grpc` exporter per pipeline to the lgtm all-in-one over the observability network. The deprecated `otlp` exporter alias was replaced with `otlp_grpc` after the 0.165.0 collector logged the deprecation warning.
- **Compose** — [`infra/compose/docker-compose.yml`](../../infra/compose/docker-compose.yml) gains the `otel-collector` service (image pinned by multi-arch digest, config volume read-only, ports 4317/4318/13133, healthcheck, `observability`/`full` profiles); the lgtm service no longer host-publishes :4317/:4318 — host OTLP ports are unchanged for the app (`localhost:4318`), they are just owned by the collector now. S13-05/07/08 will stand Prometheus/Loki/Tempo up as dedicated services and swap the exporter endpoints; until then the three pipelines fan out through lgtm's bundled collector.
- **Validation / healthcheck** — the pinned image is distroless (no shell, no curl), so the compose healthcheck re-validates the mounted config with the collector's own `validate` subcommand; the runtime probe is the `health_check` extension, published on :13133.

### Why

S13-03 wired the app's OTLP exporter but the ingress itself lived inside a digest-pinned all-in-one image — its collector config was not this repo's. The plan makes the Collector the first piece of the split stack (S13-04 → S13-05…S13-08); shipping the standalone collector first, forwarding to the still-lgtm backends, keeps every later backend task a config swap instead of a topology change.

### Verification

Live run, this session, from a clean state (`docker compose --profile observability up -d`, nothing pre-running):

1. `docker compose config --quiet` — exit 0.
2. `docker run --rm -v <repo>/…/collector.yaml:/etc/otelcol-contrib/config.yaml:ro <pinned image> validate --config=/etc/otelcol-contrib/config.yaml` — exit 0 (this is also the compose healthcheck).
3. Both services up; `agentflow-otel-collector` healthy (healthcheck passing, health_check extension `http=200`); `agentflow-observability` healthy.
4. **Acceptance — OTLP accepted and routed to the local backends.** OTLP/JSON payloads with `service.name=agentflow-smoke` posted to `http://localhost:4318`:
   - `/v1/traces` → 200; Tempo search returns the trace: `traceID 5b8aa5a2d2c872e8321cf3730e32c853, rootServiceName agentflow-smoke, rootTraceName s13-04-smoke-span` (`/api/search?tags=service.name=agentflow-smoke`).
   - `/v1/metrics` → 200; Prometheus returns the gauge: `agentflow_smoke_metric{service_name="agentflow-smoke", deployment_environment="local", job="agentflow-smoke"} = 42`.
   - `/v1/logs` → 200; Loki returns the stream: `{service_name="agentflow-smoke", agentflow_operation="smoke.log", deployment_environment="local"}` with the smoke record.
   All labels are bounded (`service_name` / `deployment_environment` / the closed attribute set) — no IDs.
5. After the `otlp_grpc` rename: collector log contains zero deprecation warnings; re-sent trace + metric still land in Tempo/Prometheus (fresh payloads, current timestamps — a stale hardcoded smoke timestamp initially produced empty backend queries because it fell outside the search windows; the probes use `date +%s` now).

No Java/Gradle change — `./gradlew check` is not the gate here; the compose/config validation and the live smoke above are the verification.

### Documentation

- `infra/observability/otel-collector/collector.yaml` (new) — the collector config itself, with the boundary contract in the header comments.
- [`infra/observability/README.md`](../../infra/observability/README.md) — layout, the standalone-collector rationale, endpoints table (incl. :13133), the validation command, the acceptance recipe.
- [`infra/compose/docker-compose.yml`](../../infra/compose/docker-compose.yml) — the new service, the lgtm port change and updated comments.
- Root [`README.md`](../../README.md) — port table names the otel-collector ingress and the health check.
- `docs/devlog/sprint-13-observability.md` (this file) + contents line; `CHANGELOG.md` (Unreleased / Added).

### Scope / follow-up

Collector-only: Prometheus/Grafana/Loki/Tempo as dedicated services (S13-05…S13-08) swap the exporter endpoints; the collector's own metrics scrape is S13-05's surface; the lgtm service keeps no healthcheck (pre-existing, outside this task's scope).
