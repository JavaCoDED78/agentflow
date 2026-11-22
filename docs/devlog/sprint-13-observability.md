# Sprint 13 — Observability: metrics pipeline as code

Detailed per-task changelog records for the observability thread. Slim one-line summaries with links are in the root [`CHANGELOG.md`](../../CHANGELOG.md).

## Contents

- [S13-01 — telemetry conventions](#s13-01-telemetry-conventions)

---

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
