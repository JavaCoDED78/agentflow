# Sprint 13 — Observability: metrics pipeline as code

Detailed per-task changelog records for the observability thread. Slim one-line summaries with links are in the root [`CHANGELOG.md`](../../CHANGELOG.md).

## Contents

- [S13-01 — telemetry conventions](#s13-01-telemetry-conventions)
- [S13-02 — Micrometer/Actuator metrics](#s13-02--micrometeractuator-metrics)
- [S13-03 — OTLP tracing exporter](#s13-03-otlp-tracing-exporter)
- [S13-04 — OTel Collector config as code](#s13-04-otel-collector-config-as-code)
- [S13-05 — Prometheus as code](#s13-05-prometheus-as-code)

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

## S13-05 — Prometheus as code

### What changed

The local observability stack gains a dedicated, repo-owned Prometheus — the metrics store of the split-stack target (S13-04 → S13-05…S13-08) is now real for metrics.

- **Config** — new [`infra/observability/prometheus/prometheus.yml`](../../infra/observability/prometheus/prometheus.yml), mounted read-only: 15 s scrape/evaluation intervals, `external_labels.environment: local` (bounded, configuration-fixed) and two jobs — `agentflow-api` (the application's `/actuator/prometheus`, S13-02 contract; the endpoint sits behind the default-deny secured chain, so the job carries a bearer token from `/etc/prometheus/scrape/token`) and `otel-collector` (the Collector's own metrics, exposed by a new `prometheus` telemetry reader on :8888 in [`collector.yaml`](../../infra/observability/otel-collector/collector.yaml) — `detailed` level, bounded component-id labels, internal network only).
- **Compose** — [`infra/compose/docker-compose.yml`](../../infra/compose/docker-compose.yml) gains the `prometheus` service: image pinned by multi-arch index digest (`prom/prometheus:v3.6.0` → `sha256:76947e…`), versioned retention as command flags (`--storage.tsdb.retention.time=7d`, `--storage.tsdb.retention.size=2GB` — Prometheus keeps retention a startup flag, not a config key), TSDB on the `agentflow-prometheus` named volume, healthcheck via the image's busybox `wget` against `/-/healthy`, a `host-gateway` alias so the host-running app resolves on every engine. Per the S13-04 port-precedent (the collector took over the host OTLP ports), the dedicated service now owns host :9090 and lgtm's bundled Prometheus stops being host-published — Grafana keeps using it internally until S13-06/07/08 swap datasources and collector exporters.
- **Scrape token** — [`scripts/mint-scrape-token.sh`](../../scripts/mint-scrape-token.sh) mints a short-lived JWT (Client Credentials, `agentflow-service` m2m client, same local-only secret default as the Keycloak service) and rewrites the mounted token file; Prometheus re-reads `bearer_token_file` before every scrape (verified on the pinned image with a header-echo probe), so the 300 s Keycloak token TTL only needs a file refresh — `--watch` loops at 240 s. The committed [`scrape/token`](../../infra/observability/prometheus/scrape/token) is a clearly-marked placeholder, not a valid token; a real token is never committed.
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
