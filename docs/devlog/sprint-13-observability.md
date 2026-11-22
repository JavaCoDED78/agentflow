# Sprint 13 — Observability: metrics pipeline as code

Detailed per-task changelog records for the observability thread. Slim one-line summaries with links are in the root [`CHANGELOG.md`](../../CHANGELOG.md).

## Contents

- [S13-01 — telemetry conventions](#s13-01-telemetry-conventions)
- [S13-02 — Micrometer/Actuator metrics](#s13-02--micrometeractuator-metrics)

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
