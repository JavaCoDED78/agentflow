# Telemetry standards

- **Owner:** `observability` (`com.example.agentflow.observability`) — meters, trace attributes, correlation, structured JSON logging.
- **Task:** `S13-01` (P0) — fixes the conventions every later Sprint 13 task (S13-02 … S13-22) follows.
- **Related:** [`../domain-glossary.md`](../domain-glossary.md) §12 (Correlation / trace ID, Metric label), [`../architecture/module-map.md`](../architecture/module-map.md) (cross-cutting packages), [`../security/secrets.md`](../security/secrets.md), [`../adr/ADR-020-shared-redaction-in-common.md`](../adr/ADR-020-shared-redaction-in-common.md) (shared redaction), [`../adr/ADR-021-redis-role.md`](../adr/ADR-021-redis-role.md) (metrics for the Redis helper).
- **What this is not:** the metric **catalogue** (metric names, units and meanings — `S13-11…S13-13`, `S13-22`, the handbook `S13-21`), the dashboards (`S13-14…S13-16`), the alerts (`S13-17`) or the SLOs. This file fixes the **rules**; the catalogues are written per signal family and must follow it — the workflow-execution family is `metrics.md` (S13-11); the `messaging` / `ai` / `usage` / `knowledgebase` and Redis families are still planned.

---

## Signal types and their homes

Three signals carry telemetry. Each has one home and one purpose; do not put a signal in the
wrong home.

| Signal | Carrier | Home document | Allowed to carry | Never allowed |
|---|---|---|---|---|
| **Metrics** (counts, rates, durations, sizes) | Micrometer (Prometheus registry) → `/actuator/prometheus` scrape | `observability` (domain meters) | Bounded numeric values; bounded category labels | Tenant/run/user/document identifiers, prompts, document text, raw errors, secrets, unbounded labels |
| **Traces** (one request's journey across API/outbox/worker) | OpenTelemetry SDK → OTLP → Collector → Tempo (dedicated repo-owned store since S13-08) | `observability` (trace attributes, correlation) | W3C `traceparent`/`tracestate`, `correlationId`, `causationId`, service/environment/version, bounded attributes | Raw request/response bodies, full prompts, document text, secrets, tokens, high-cardinality IDs as **attribute names** that explode cardinality (e.g. one attribute per run) |
| **Logs** (one event with context) | `org.slf4j` → logback logstash encoder (JSON stdout, the `StructuredJsonLogstashEncoder` of S13-09) → Loki/Collector | `observability` (encoder + masker) | The full structural context: `traceId`/`spanId`/`correlationId`, service, environment, severity, message | Secrets/tokens, full prompts, document text, raw payloads, stray high-cardinality MDC keys — anything unredacted |

The assignment follows the plan (§13 of `AGENTFLOW_ARCHITECTURE_AND_PLAN.md`): **Micrometer for
application metrics and OpenTelemetry/OTLP for traces**; JSON logs via the logback encoder
(`logback-spring.xml`, S01-19). The metric scrape endpoint is internal-network-only (S13-02);
the traces exporter reads its endpoint/environment/sampling from configuration (S13-03) and
never carries a raw request body. In the local stack the traces exporter points at the
standalone Collector (`localhost:4318`), which fans spans out to the dedicated Tempo (S13-08);
the local stack runs 100% head sampling (`management.tracing.sampling.probability: 1.0`) with no
collector-side sampling processor, so every span the app emits is stored and searchable —
Tempo's `compactor` retention (7 days, matching the dedicated Prometheus and Loki) is a
storage-side bound, not a sampling decision. The kubernetes profile (S15) is the only place the
rate is lowered.

**Where to add what**

- A **new metric** → a meter owned by the module that owns the *behaviour* (e.g. `workflowexecution`
  for runs, `messaging` for the outbox, `usage` for cost), built over `io.micrometer.core`
  through the `observability` helpers, never by hand-rolled `System.out` / `Counter` globals.
- A **new trace span** → Spring MVC / `@Observed` / manual `Span` around the operation, with
  attributes from this document, in the module that owns the operation. Never import a provider
  SDK; keep the OTel surface behind the `observability` helpers.
- A **new log line** → an `org.slf4j` call in the owning module; the encoder handles
  serialization and the shared masker (ADR-020) handles redaction. Never log a full document,
  a prompt, a token, or a raw payload — not even at DEBUG.

## Naming and units

**Metrics** use lower-case, dotted, domain-first names with a unit suffix:

```text
agentflow.<domain>.<what>.<unit>
```

- `what` describes the thing measured (`run`, `step`, `outbox`, `tool`, `retrieval`, `budget`, `redis`).
- The unit suffix is explicit and one of: `.seconds` (timer/latency), `.total` (counter — terminal
  counts), `.count` (events that are not strictly monotonic — e.g. recoveries), `.bytes` (size),
  `.rate` (rate or ratio) or `.bytes_per_second`/`.requests_per_second` only on the Actuator
  HTTP/JVM family. Never use a vague suffix (`.value`, `.amount`) or an ambiguous unit.
- Counters never carry an id: `agentflow.workflowexecution.run.total{state="succeeded"}` is a
  counter over a *bounded* state label, not a per-run counter.

**Traces** name spans by operation (`POST /api/v1/workflows`, `OutboxPublisherService#pollOnce`,
`ToolExecutor.execute`, `KafkaMessageDeliverer#deliver`, `DocumentIndexingWorker#handle`) with the standard
OTel semantic-convention attributes plus the `agentflow.*` extensions below. Span names follow the service's own operation vocabulary, not a literal
HTTP path with ids embedded.

**Logs** carry the pinned line contract of `StructuredJsonLogstashEncoder` (S13-09): `@timestamp`,
`severity`, `service`, `environment`, `correlationId`, `message`, `logger_name`, `thread_name` and — when
tracing is wired — `traceId` / `spanId`, all inline with no nesting. The encoder whitelists exactly these MDC
keys, so a stray high-cardinality id (tenant/run/user/document) that some caller puts into the MDC never reaches
the line. `@version` and `exception` stay as the default encoder emits them. The `message` is a short, redacted,
human-readable description — never a payload dump.

## Bounded labels (metrics)

Every metric carries a **closed, code-defined vocabulary** of labels. An author picks the
smallest set that answers a question; an unbounded set is a defect.

- Allowed examples: `state` / `status` (run or step state, from the closed state machines),
  `type` / `node_type` (the closed workflow/tool/event vocabulary), `op` / `operation`
  (closed operation names), `result` (success/failure/timeout), `provider` (the closed
  provider id), `env`-family attributes that are configuration-fixed, `kind` / `source`
  when drawn from a closed set.
- **Forbidden labels, in every signal and in every label position:** `tenantId`, `runId`,
  `userId`, `stepRunId`, `documentId`, `documentName`, `prompt`, `query`, `token`, `payload`,
  `resourceId`, `correlationId`, `traceId`, `spanId`, `actor`, `email`, `ip`, `hostname`,
  `url` with a path — anything whose cardinality is not bounded by a small constant or by a
  code-defined set. `correlationId` / `traceId` are **trace/log** values only (glossary §12).
- Adding a label to an existing metric is a breaking cardinality change: it needs an ADR-level
  review of the dashboards/alerts that read it (S13-17), not a drive-by.

**Enforcement.** The S13-19 cardinality/privacy check (`TelemetryPrivacyTest`, DB-free, run by `./gradlew :apps:agentflow-app:check`) walks the full metric catalogue and the live source tree — every `Counter`/`Timer`/`Gauge` in `src/main/java` must have a catalogue entry, every label key on a builder must be in that metric's allowed set, every span attribute and span name must be in the closed vocabulary, and the log whitelist must remain exactly the five pinned keys. A new meter, a new label or a new attribute without a catalogue entry is rejected before it ships; see [`#cardinality--privacy-verification`](#cardinality--privacy-verification).

## Trace attributes

Trace attributes follow the same bounded discipline as labels, plus the trace-specific content:

- **Service identity:** `service.name` = the application name (`agentflow-app`), `service.version`
  = build version, `deployment.environment` = the active profile (`local` / `test` / `kubernetes`).
- **Causality:** `correlation_id` (inherited from the request or the outbox event),
  `causation_id` (the event that caused this one), plus `trace_id`/`span_id` when emitted by
  the OTel SDK. These are correct in traces and logs; they never cross into metric labels.
- **Operation identity:** `agentflow.operation` (e.g. `workflow_run.start`, `tool_call`),
  `agentflow.step.type`, `agentflow.step.status`, `agentflow.run.state` — bounded, closed
  vocabularies. A run's id is not an attribute; the *trace* is keyed by `trace_id` in the backend.
- **AI/RAG:** `agentflow.ai.provider`, `agentflow.ai.model` (the closed model id from config),
  `agentflow.ai.prompt_version` (name/version, from the `ai.prompt` catalogue — never the template
  text), `agentflow.ai.budget_outcome` (`allowed` / `insufficient_budget` / `settled`),
  `agentflow.rag.retrieval.outcome` (`ok` / `insufficient_evidence` / `no_provider`),
  `agentflow.ingestion.outcome`. The **content** of a prompt, a document or a retrieval result is
  never an attribute.
- **Tool execution:** `agentflow.tool.name` (the closed descriptor name), `agentflow.tool.risk_class`,
  `agentflow.tool.outcome` (`success` / `denied` / `timeout` / `error`). Never the argument
  payload, the result body, or the raw error text in an attribute.

## Redaction

Telemetry is a disclosure surface: three controls stand in front of every signal.

1. **Log masking (S01-19 / S12-09).** Every field value passes through the shared
   `common.SecretRedactor` (ADR-020) via the logback `ValueMasker` decorator in
   `logback-spring.xml`. JWTs / Bearer tokens, API keys, authorization headers, AWS access
   keys, passwords and full prompts are masked before serialization — logs cannot leak
   secrets even when an error string embeds one.
2. **Trace and metric discipline.** Nothing secret reaches a span or an attribute: the trace
   attributes above are closed vocabularies, and the forbidden label list (above) is
   enforced by the S13-19 cardinality test. A prompt, a document, a payload or a raw error
   message never appears in a span, an attribute, a metric or a label.
3. **Storage boundaries.** Audit metadata and DLQ descriptions redact through the same
   `SecretRedactor` before being written (ADR-020) — telemetry, audit and DLQ redact identically.

`SecretRedactor` knows no metric- or span-specific rules; it is a string masker. A caller
that must keep a redacted *value* available (e.g. an error message in a log line) passes the
already-masked value; a caller that must carry the *structure* of a value uses a bounded,
closed attribute instead.

## What never goes in any signal

Explicit, for every carrier:

- **Secrets and credentials** — tokens, API keys, passwords, authorization headers, AWS keys
  (invariant 8). Canary-tested: `CanaryLeakScanTest` / `SecretRedactingValueMaskerTest`.
- **Prompts, user content, document text** — full or partial. A prompt version/hash is fine;
  the template text is not.
- **Raw payloads / bodies / responses** — never in logs, spans or metric values.
- **Tenant/run/user/document identifiers** in metric labels (high cardinality) or span names —
  they live in traces/logs only.
- **Raw error text** with embedded content in labels/attributes — classification and closed
  outcomes only.

## Adding a signal: checklist

1. Is it a metric, a trace attribute or a log line? Pick the home; never mix.
2. Is it bounded (metrics) and does it have a **code-defined vocabulary**? If not, it is a
   log line or a trace attribute, never a metric label.
3. Is it redacted (or structure-only)? If it carries content, it must not be telemetry — it is
  data (logs/audit) or a payload (object storage).
4. Does the metric have a unit suffix and a catalogue entry (or a planned one, marked `planned`
   until the catalogue lands, S13-11…S13-13/S13-22)?
5. Does the span name / attribute set follow the closed vocabularies above?
6. Is it in the `observability`-owned catalogue, not ad-hoc in a module?

## Cardinality / privacy verification

The S13-19 cardinality and privacy check is a DB-free unit test (`com.example.agentflow.observability.TelemetryPrivacyTest`, 7 tests, run by `./gradlew :apps:agentflow-app:check`) that walks two things:

1. **The catalogue** (`com.example.agentflow.observability.TelemetryCatalogue` — pure data, no Spring, no I/O) — the machine-checked form of this document. It fixes the full metric catalogue (names, types, units, and the bounded label keys with their closed lowercase value sets), the forbidden label set, the forbidden span-attribute set, the allowed trace-attribute vocabulary, the closed span-name vocabulary and the five pinned log MDC keys.
2. **The live source tree** — every `Counter`/`Timer`/`Gauge` builder call, every `AttributeKey.stringKey(...)` and every span name (the `spanBuilder(...)` literal and the messaging boundary's `startSpan(...)` literal) in `src/main/java` is checked against the catalogue. A new meter without a catalogue entry, an unlisted label key, a forbidden attribute, a free-text span name or a drifted log whitelist makes CI red — before the signal ships.

Add your metric (or label, attribute, span name or log key) to `TelemetryCatalogue` in the same change you add the signal, or the S13-19 check fails. The value *side* of a label is enforced by the closed catalogue sets plus review — the check is structural, not a runtime sampler; secret shapes reaching stdout are enforced by the canary and masker tests (`CanaryLeakScanTest`, `SecretRedactingValueMaskerTest`).
