# Observability handbook

- **Owner:** `observability` (`com.example.agentflow.observability`) — meters, trace attributes, correlation, structured JSON logging.
- **Task:** `S13-21` (P0) — the unified operational guide to the local observability stack.
- **Status:** the local (`observability` compose profile) stack is in place and verified — traces, logs, metrics and Grafana are repo-owned configuration under `infra/observability/`; the kubernetes profile (S15) is the only place volume/sampling changes.
- **Related:** [`standards.md`](standards.md) (telemetry rules), [`metrics.md`](metrics.md) (the machine-checked metric catalogue), [`../architecture/kafka-topics.md`](../architecture/kafka-topics.md) (the trace-context propagation contract), [`../runbooks/`](../runbooks/README.md) (operator runbooks), [`../../infra/observability/README.md`](../../infra/observability/README.md) (the stack's layout, endpoints and acceptance recipes).
- **Machine-checked:** the metric catalogue and the forbidden-signal rules are enforced by `TelemetryPrivacyTest` (S13-19, run by `./gradlew :apps:agentflow-app:check`); the dashboard/datasource/alert surfaces are proven to re-provision from Git by `scripts/observability-clean-start-test.sh` (S13-20).

This is the **single entry point for operating the observability stack**. It is a navigation and operating guide: the source of truth for the *rules* is [`standards.md`](standards.md), the source of truth for the *metric catalogue* is [`metrics.md`](metrics.md), and the source of truth for the *stack's wiring* is [`infra/observability/README.md`](../../infra/observability/README.md). Do not copy those documents here — link to them.

---

## 1. The stack at a glance

One Spring Boot artifact boots on the host (`bootRun`); the observability stack is five containers in the `observability` compose profile, all configuration as code, all mounted read-only:

```text
                ┌────────────────────┐
   traces ──────▶  standalone OTLP  ──▶  dedicated Tempo  ──▶  Grafana
   (OTLP/JSON)   collector (S13-04)   (repo-owned, S13-08)
                  (memory-limit,
                   redaction, batch)
                ┌────────────────────┐
   logs ───────▶  collector logs     ──▶  dedicated Loki    ──▶  Grafana
   (OTLP posts)  pipeline (S13-07)      (repo-owned, S13-07)

   metrics ─────▶  dedicated Prometheus (S13-05) ──▶ Grafana
   (scrape                                          (OTLP push: off)
   /actuator/prometheus, bearer token)

   lgtm all-in-one (Grafana + its bundled backends) — retired as a log/trace
   store; still serves the Git-provisioned Grafana and the OTLP-pushed
   metrics pipeline (S13-04…S13-08; rationale in infra/observability/README.md).
```

The three signals each have **one home** (the `observability` module) and **one purpose** — see [`standards.md` §Signal types and their homes](standards.md#signal-types-and-their-homes).

- **Traces** — OpenTelemetry SDK → OTLP/gRPC/HTTP → the standalone Collector → the dedicated Tempo.
- **Logs** — app JSON on stdout (the `StructuredJsonLogstashEncoder` of S13-09, whitelisted MDC, masked) → the environment's log drain; the dedicated Loki receives logs the collector's logs pipeline fans out (OTLP).
- **Metrics** — Micrometer → the `/actuator/prometheus` scrape endpoint → the dedicated Prometheus → Grafana. Metrics never ship through OTLP push (`management.otlp.metrics.export.enabled: false`).

## 2. Telemetry path

### Traces (`S13-03`, `S13-04`, `S13-08`)

- The app exports OTLP traces to the standalone Collector (`AGENTFLOW_OTLP_ENDPOINT`, local default `http://localhost:4318`; Boot maps the `OTEL_EXPORTER_OTLP_*` environment variables to the same properties).
- Every HTTP request becomes a span. Head sampling is **100% of requests** in the local stack (`management.tracing.sampling.probability: 1.0`, no collector-side sampling processor — Tempo stores everything it receives); the kubernetes profile is the only place the rate is lowered.
- The Collector (`infra/observability/otel-collector/collector.yaml`) is the OTLP ingress: `memory_limiter` (256 MiB ceiling / 64 MiB spike), `attributes/redaction` (deletes `http.request.body` / `http.response.body` / `prompt*` / `document.*` — defense in depth; the app instrumentation never emits them), `batch` (5 s / 8 192 / 16 384), `health_check` on `:13133`, and a `prometheus` telemetry reader on `:8888` (scraped by the dedicated Prometheus).
- The traces pipeline exports to the dedicated Tempo over OTLP/gRPC (`tempo:4317`, insecure inside the compose network). Tempo keeps local blocks + WAL on the `agentflow-tempo` volume and 7-day block retention.

### Logs (`S13-09`, `S13-07`)

- The app writes JSON on stdout via `logback-spring.xml` (`StructuredJsonLogstashEncoder`): `@timestamp`, `severity`, `service`, `environment`, `correlationId`, `message`, `logger_name`, `thread_name` and — when tracing is wired — `traceId` / `spanId`. The encoder whitelists exactly those MDC keys, so a stray high-cardinality id never reaches a line; `@version` and `exception` stay as the default encoder emits them.
- Every field value passes through the shared `SecretRedactor` (ADR-020) via the logback `ValueMasker` decorator — JWTs / Bearer tokens, API keys, authorization headers, AWS keys and full prompts are masked before serialization, canary-tested (`CanaryLeakScanTest`, `SecretRedactingValueMaskerTest`).
- The `correlationId` is seeded into the MDC by `CorrelationIdFilter` for the duration of an HTTP request (the active `traceId` when tracing is wired, a fresh UUID otherwise); the response's `correlation_id` is the same id (`GlobalExceptionHandler#correlationId()`).
- The Collector's logs pipeline (`otlp_http/loki` → `http://loki:3100/otlp`) delivers OTLP-posted logs to the dedicated Loki. Loki's `limits_config.otlp_config` keeps only `service.name` / `deployment.environment` as index labels; everything else — tenant/run/user/document ids included — lands in per-entry structured metadata and can never index-label a stream. The authoritative index-label set is `GET /loki/api/v1/labels`.
- Note on the local app's log path: the app's own stdout (JSON) is the application's log path; the collector's logs pipeline is the Loki ingest surface proven by the backend smoke (a probe OTLP/JSON log posted to `:4318/v1/logs`). The app does not push its own logs over OTLP today.

### Metrics (`S13-02`, `S13-05`)

- The app exposes `/actuator/prometheus` behind the default-deny secured chain (S13-02): a scrape must carry a bearer token (issued by the local Keycloak `agentflow-service` m2m client — see the scrape-token section).
- The dedicated Prometheus (`infra/observability/prometheus/prometheus.yml`, 15 s scrape/evaluation, `environment: local` external label) scrapes the app (`host.docker.internal:8080`, since the app runs on the host) and the collector's own `:8888` metrics. The TSDB persists on the `agentflow-prometheus` volume.
- The collector's own metrics are also scraped (job `otel-collector`, internal network only, no auth): `otelcol_process_uptime`, `otelcol_process_memory_rss`, `otelcol_exporter_queue_size` and friends — bounded component-id labels.
- Canonical metrics path is the scrape, never OTLP push: `management.otlp.metrics.export.enabled: false` keeps Boot's OTLP-push exporter off (the starter drags it in); the lgtm-bundled metrics pipeline exists but carries no application metrics by default.

## 3. Service / resource attributes

Every span and metric carries the same bounded, configuration-fixed identity:

| Attribute | Value | Where fixed |
|---|---|---|
| `service.name` | `agentflow-app` (the `spring.application.name`) | `application.yml` (the auto-configured Resource maps it) |
| `service.version` | the build version | Boot auto-configuration |
| `deployment.environment` | `local` / `test` / `kubernetes` (`AGENTFLOW_DEPLOYMENT_ENVIRONMENT`, default `local`) | `application.yml` |
| `application` metric tag | `agentflow-app` (one value) | `application.yml` (`management.metrics.tags`) |
| Prometheus `external_labels.environment` | `local` | `prometheus.yml` |
| Collector telemetry reader labels | bounded component ids only | `collector.yaml` (`prometheus` telemetry reader, `detailed` level) |

These are **configuration-fixed, never taken from a request**. `service.name`/`deployment.environment` are also the only two labels Loki indexes — everything else is structured metadata. See [`standards.md` §Trace attributes](standards.md#trace-attributes) for the full attribute vocabulary (service identity, causality, operation identity, AI/RAG and tool execution) and its closed vocabularies.

## 4. Trace propagation through Kafka

The outbox/inbox boundary carries W3C trace context end to end (`S13-10`; the contract in [`docs/architecture/kafka-topics.md`](../architecture/kafka-topics.md) §Trace context propagation):

- **Producer (`KafkaMessageDeliverer` / `OutboxService#enqueue`).** An event persisted inside a traced request carries the active span's `traceparent` in its `headers` JSON — `enqueue` threads it automatically unless the caller set an explicit one (the HTTP create-run path threads the request traceparent explicitly, e.g. via `RunService`). The publish runs inside a `KafkaMessageDeliverer#deliver` span parented on that `traceparent`, attributed `correlation_id` / `causation_id` when present.
- **Consumer (`DocumentIndexingWorker` and the other workers).** Each `handle()` runs inside a span (e.g. `DocumentIndexingWorker#handle`) parented on the record's `traceparent` header — the context the producer wrote — never on the consumer's own poll, which groups unrelated work. The span is closed in a `finally` and records `correlation_id` / `causation_id` as attributes.
- **Boundary rules.** An explicit `traceparent` in the event's `headers` always wins; a blank payload yields the threaded traceparent; a corrupt payload is left untouched (the deliverer warns and drops it — never silently replaced). The wire contract is unchanged: the same three fields (`correlationId` / `causationId` / `traceparent`) in the same stable JSON order, never a secret, never `tenantId`.
- **What is never carried.** Tenant / run / document ids — never span attributes, never metric labels. The trace is keyed by `trace_id` in Tempo; the `correlationId` in logs is the join key.

A trace view therefore shows one journey: the API request → the outbox publish → the worker processing, across the Kafka boundary, with correlation/causation ids preserved as attributes. `KafkaTraceContextIT` (real KRaft broker) is the end-to-end proof.

## 5. Metric catalogue and labels

The authoritative metric catalogue is **[`metrics.md`](metrics.md)**, in machine-checked form by `TelemetryCatalogue` (`observability`, pure data, no Spring, no I/O) and `TelemetryPrivacyTest` (DB-free, run by `./gradlew :apps:agentflow-app:check`):

- Every meter in `src/main/java` must have a catalogue entry; every catalogue entry must be produced in the tree (neither direction drifts).
- Every label key on a builder must be in that metric's allowed set — an unlisted label is rejected, and a no-label metric must stay label-free.
- The catalogue fixes the **closed label value sets** (e.g. `state` on `run.total`, `outcome` on the outbox counters / the tool timer, `reason` on both provider error counters), the forbidden label set, the forbidden span-attribute set, the allowed trace-attribute vocabulary, the closed span-name vocabulary and the five pinned log MDC keys.
- Boot-native meters (`http.server.requests`, `jvm.*`, `hikaricp.*`) are deliberately outside the catalogue — Boot-managed, bounded tags, documented in `metrics.md` without an explicit entry.

The catalogue covers (details in [`metrics.md`](metrics.md)):

| Family | Owner | Examples |
|---|---|---|
| Workflow execution | `workflowexecution` | `agentflow.workflowexecution.run.total{state}`, `run.duration.seconds`, `step.duration.seconds`, `step.retry.total`, `step.cancelled.total`, `lease.expired.total` |
| Messaging | `messaging` | `agentflow.messaging.outbox.pending.events`, `outbox.oldest.age.seconds`, `outbox.publish.attempts.total{outcome}`, `outbox.publish.retries.total`, `consumer.lag.count`, `consumer.processing.failures.total{outcome}` |
| AI/RAG | `ai` / `usage` / `knowledgebase` / `toolexecution` / `ingestion` | `agentflow.ai.provider.chat.latency.seconds` / `.errors.total{reason}` / `.tokens.*.total`, `agentflow.usage.estimated.cost.usd`, `agentflow.toolexecution.tool.duration.seconds{outcome}`, `agentflow.knowledgebase.retrieval.*`, `agentflow.ingestion.index.failures.total` |
| Redis | `common` (`rate_limit`) | `agentflow.redis.rate_limit.fallback_open` |
| Boot-native HTTP/JVM/DB-pool | `config` | `http_server_requests_seconds` (histogram opt-in), `jvm_*`, `process_cpu_usage`, `hikaricp_connections*` |

The scrape-token section below is the one operational command for this family (a placeholder token cannot scrape).

## 6. Forbidden labels and sensitive fields

**In every signal and every label position, forbidden** (the S13-19 check enforces the structural side):

- Identifiers: `tenantId`, `runId`, `userId`, `stepRunId`, `documentId`, `documentName`, `resourceId` — anything whose cardinality is not bounded by a small constant or a code-defined set.
- Causality ids as **labels**: `correlationId`, `traceId`, `spanId` — trace/log values only, never metric labels.
- Content: `prompt`, `query`, `token`, `url` with a path, `payload`, `message` body — anything content-carrying.
- Raw values: `actor`, `email`, `ip`, `hostname` — bounded configuration only.

**Full text of this list and its enforcement**: [`standards.md` §Bounded labels (metrics)](standards.md#bounded-labels-metrics) and §What never goes in any signal](standards.md#what-never-goes-in-any-signal) — secrets/credentials, prompts/document text, raw payloads/bodies, tenant/run/user/document identifiers in metric labels or span names, raw error text in labels/attributes. [`metrics.md` §Machine-checked catalogue](metrics.md#machine-checked-catalogue-s13-19) adds the machine-checked detail.

The **log** side has its own contract: only the five pinned MDC keys are written inline (`service`, `environment`, `traceId`, `spanId`, `correlationId`) and every value is masked by the shared `SecretRedactor` before serialization — a stray high-cardinality id in the MDC never reaches a line, and the canary secret fixture can never appear. Dashboards and alert rules must not add a label beyond the closed value sets; adding a label to an existing metric is a breaking cardinality change and needs ADR-level review, not a drive-by.

## 7. Dashboards (provisioned from Git)

Every dashboard lives in [`infra/observability/grafana/dashboards/`](../../infra/observability/grafana/dashboards/) as JSON with a stable UID, mounted read-only at `/etc/grafana/dashboards`, wired by the S13-06 provider (`infra/observability/grafana/provisioning/dashboards/agentflow.yaml` — `allowUiUpdates: false`, `disableDeletion: true`, 30 s update interval). A manual UI edit of a provisioned dashboard is **refused by the API** (`Cannot save provisioned dashboard`) — a dashboard change is a Git change.

| UID | Title | Panels | Data source |
|---|---|---|---|
| `agentflow-overview` | AgentFlow / Overview (S13-06 smoke) | 4 | dedicated Prometheus |
| `agentflow-api-jvm-db` | AgentFlow / API/JVM/DB (S13-14) | 12 | dedicated Prometheus |
| `agentflow-workflow-kafka` | AgentFlow / Workflow & Kafka (S13-15) | 15 | dedicated Prometheus (+ Loki/Tempo links) |
| `agentflow-ai-rag-cost` | AgentFlow / AI/RAG/Cost (S13-16) | 11 | dedicated Prometheus |

The dashboards are **provisioned, not built in the UI**: the clean-start test (S13-20) proves all four re-load from a wiped state with `meta.provisioned: true`. Panels render honestly — a local API boot emits no workflow/messaging/AI series, so those panels are empty until the role is running; an empty series is the truth, not a defect. Histogram-opt-in timers (`http.server.requests`, the two workflow timers, the AI/RAG timers) back the `histogram_quantile` panels — Boot's default timer has no bucket series, hence the opt-in.

The datasource set in [`infra/observability/grafana/provisioning/datasources/agentflow.yaml`](../../infra/observability/grafana/provisioning/datasources/agentflow.yaml) is the authoritative Grafana wiring: every datasource of the stack, with stable UIDs and `editable: false` — `agentflow-prometheus` (the dedicated store), the lgtm-internal `prometheus` / `loki` / `tempo` / `pyroscope` (the URLs point at the dedicated services since S13-07/08).

## 8. Alerts and runbooks

The alert rules live in [`infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml`](../../infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml) — configuration as code, rendered by the S13-06 provider, with a `for` guard, a severity label and a `runbook_url` annotation on every rule. The query model is the stable Grafana 13 chain (instant PromQL → `reduce` last → `threshold`, no `classic_conditions`).

| UID | Title | Severity | `for` | Expression (A) | Runbook |
|---|---|---|---|---|---|
| `agentflow-scrape-target-down` | AgentFlow scrape target down | warning | 1m | `up{job=~"agentflow-api\|otel-collector"} < 1` | [`observability-scrape-target-down.md`](../runbooks/observability-scrape-target-down.md) |
| `agentflow-api-error-rate` | AgentFlow API 5xx error rate | critical | 5m | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) > 0.5` | [`api-error-rate.md`](../runbooks/api-error-rate.md) |
| `agentflow-db-saturation` | AgentFlow DB pool saturating | critical | 5m | `hikaricp_connections_active / hikaricp_connections_max > 0.8` | [`db-saturation.md`](../runbooks/db-saturation.md) |
| `agentflow-outbox-age` | AgentFlow outbox oldest event age | warning | 5m | `agentflow_messaging_outbox_oldest_age_seconds > 300` | [`outbox-age.md`](../runbooks/outbox-age.md) |
| `agentflow-kafka-lag` | AgentFlow Kafka consumer lag | warning | 10m | `agentflow_messaging_consumer_lag_count > 1000` | [`kafka-lag.md`](../runbooks/kafka-lag.md) |
| `agentflow-expired-leases` | AgentFlow expired leases (stuck runs) | warning | 5m | `rate(agentflow_workflowexecution_lease_expired_total[5m]) > 0` | [`expired-leases.md`](../runbooks/expired-leases.md) |
| `agentflow-provider-failures` | AgentFlow provider failures | warning | 5m | `rate(agentflow_ai_provider_chat_errors_total[5m]) + rate(agentflow_ai_provider_embedding_errors_total[5m]) > 0` | [`provider-failures.md`](../runbooks/provider-failures.md) |
| `agentflow-ingestion-failures` | AgentFlow ingestion failures | warning | 5m | `rate(agentflow_ingestion_index_failures_total[5m]) > 0` | [`ingestion-failures.md`](../runbooks/ingestion-failures.md) |

Every rule carries:

- **Owner:** engineering (on-call) — every runbook header declares it.
- **Action:** the runbook — each one walks the symptoms, the honest local-dev state, the `curl`/dashboard diagnostics, the causes and the safe mitigations, the recovery criterion and the escalation note; see [`../runbooks/README.md`](../runbooks/README.md) for the index.
- **noDataState:** `Alerting` for the scrape-target-down rule (a target vanishing from discovery is as alertable as `up == 0`); `OK` for the seven operational rules (silent on missing series — pages only on a real breach). `execErrState: Error` everywhere.

**SLO status — honestly.** There are no SLO definitions yet: the alert thresholds in the table above are baseline values (S13-17), not SLOs. SLO definitions land with the SLO work (`S14-16` / `S16-14`); the runbooks reference the `for` guard as the recovery-criterion anchor until then. The local API boot honestly produces no workflow/messaging/AI series — the operational rules stay `inactive`/`Normal (NoData)` and the scrape rule honestly fires (the local API boot is not scraped). This is documented, not a defect.

The `agentflow-scrape-target-down` rule is the one P0-style operational rule wired to the stack's own health: it fires when `up{job=~"agentflow-api|otel-collector"}` is below 1 for more than 1 m. The two `critical` operational rules (API 5xx, DB saturation) are the user-facing health signals.

## 9. Data retention and volumes

All retention is versioned in the repo and enforced by the backends — nothing is chosen inside a UI:

| Surface | Retention | Where |
|---|---|---|
| Prometheus TSDB | 7 days / 2 GB | `infra/compose/docker-compose.yml` command flags (`--storage.tsdb.retention.time=7d`, `--storage.tsdb.retention.size=2GB`) + the `agentflow-prometheus` named volume |
| Loki (chunks/index) | 7 days (`limits_config.retention_period: 168h`) | `infra/observability/loki/loki.yaml` + the `agentflow-loki` named volume |
| Tempo (blocks + WAL) | 7 days (`compaction.block_retention: 168h`) | `infra/observability/tempo/tempo.yaml` + the `agentflow-tempo` named volume |
| Grafana (sqlite state) | none — the whole volume is the state | `agentflow-observability` named volume (`/data`); no retention flags |
| lgtm bundled stores | none (retired as stores) | bundled inside the digest-pinned `grafana/otel-lgtm` image; no host-published ports for Loki/Tempo since S13-07/08 |

The local stack keeps **100% head sampling** (`management.tracing.sampling.probability: 1.0`); Tempo stores everything it receives. The kubernetes profile is the only place the sampling rate is lowered.

**Ports** (host-published, see [`infra/observability/README.md` §Endpoints](../../infra/observability/README.md)): `3000` Grafana, `9090` Prometheus, `3100` Loki, `3200` Tempo, `4317`/`4318` OTLP ingress (gRPC/HTTP), `13133` collector health check. The collector's `:8888` self-metrics and the backends' internal ports are never host-published.

## 10. Stack recovery and operations

All commands run from the repo root; the stack is torn down/restarted with the same profile.

```bash
# Bring the stack up (observability profile)
docker compose --file infra/compose/docker-compose.yml --profile observability up -d

# Bring it down (add -v to drop the named volumes — the clean-slate state)
docker compose --file infra/compose/docker-compose.yml --profile observability down

# Smoke test: services up and healthy, then torn down
bash scripts/smoke-local.sh --profile observability

# Clean-start test: wipe the state volumes, re-provision from Git, verify the
# surfaces over the Grafana API (datasources, dashboards, alerts, UI-edit refusal)
bash scripts/observability-clean-start-test.sh

# Grafana UI: http://localhost:3000 (admin / local default GF_ADMIN_PASSWORD, default "admin")
```

The scrape path needs a bearer token when the app runs on the host:

```bash
bash scripts/mint-scrape-token.sh            # one token (~5 min of scraping)
bash scripts/mint-scrape-token.sh --watch    # refresh every 240 s for a session
```

The mint script writes the git-ignored scrape token file (`infra/observability/prometheus/scrape/token`) the Prometheus `agentflow-api` job re-reads before every scrape — mint, then wait one scrape interval (15 s) for `up{job="agentflow-api"} = 1`. **Never commit the file with a real token** (it is git-ignored since S13-14; a fresh checkout never contains one).

When the stack is broken, the order of checks is:

1. **Health** — `docker compose --file infra/compose/docker-compose.yml --profile observability ps` (the five services report healthy; a distroless service fails the check via its config-verify binary, so a broken config edit fails loudly).
2. **Collector** — `GET localhost:13133/ready` (`health_check` extension) and the collector's own metrics (`http://localhost:9090/api/v1/query?query=otelcol_process_uptime`).
3. **Scrape** — `http://localhost:9090/api/v1/targets` (both jobs `UP`; `agentflow-api` DOWN while the app is not running is the honest local state), and `http://localhost:9090/api/v1/query?query=up{job="otel-collector"}`.
4. **Grafana** — `GET /api/datasources` (all five, `readOnly: true`), `GET /api/v1/provisioning/alert-rules` (all eight rules present), `GET /api/dashboards/uid/<uid>` (provisioned).
5. **Backends** — Tempo search `GET localhost:3200/api/search?tags=service.name=agentflow-smoke`; Loki `GET localhost:3100/loki/api/v1/labels` (only `service_name` / `deployment_environment`); Prometheus UI `http://localhost:9090/targets`.
6. **Clean re-provision** — `bash scripts/observability-clean-start-test.sh` drops the state volumes, brings the stack up and verifies the surfaces from Git (28 checks).

**Never do in the UI:** edit a dashboard/datasource/alert by hand, add a rule/panel/target without a Git change, or edit a provisioned dashboard — the API refuses it and the stack is configuration as code (infra/AGENTS.md). Fix in Git, restart the stack, and re-verify.

**What is not durable:** Grafana sqlite (the `agentflow-observability` volume), the Prometheus TSDB (`agentflow-prometheus`), Loki chunks/index (`agentflow-loki`), Tempo blocks/WAL (`agentflow-tempo`). All four are dev/demo state — the clean-start test drops them by design. There is no durable business state in the stack: PostgreSQL (not this stack) is the source of truth.

## 11. Verification and evidence

- `./gradlew :apps:agentflow-app:check` — unit + architecture + formatting + the S13-19 `TelemetryPrivacyTest` (catalogue well-formedness, full metric scan, span scan, log whitelist).
- `bash scripts/observability-clean-start-test.sh` — the S13-20 clean-start proof: wipe the four state volumes, bring the stack up, verify over the Grafana API that all surfaces re-provision from Git (datasources `readOnly: true`, dashboards `meta.provisioned: true`, alert rules in the provisioning API **and** evaluating live, UI-edit refusal).
- `bash scripts/smoke-local.sh --profile observability` — all five services healthy, then torn down.
- `docker compose --file infra/compose/docker-compose.yml --profile observability config --quiet` — compose validation.
- The live acceptance recipes for each task are recorded in [`infra/observability/README.md`](../../infra/observability/README.md); the per-task detail is in [`../devlog/sprint-13-observability.md`](../devlog/sprint-13-observability.md).

## 12. Checklist — adding or changing a signal

1. Decide the signal's home (metric / trace span / log line) — [`standards.md` §Adding a signal](standards.md#adding-a-signal-checklist).
2. Metric: add it to `TelemetryCatalogue` in the same change (name, type, unit, allowed labels with closed values); never add a label or a value outside the catalogue.
3. Trace: follow the closed span-name/attribute vocabularies in `standards.md` — no content, no identifiers.
4. Log: only the five pinned MDC keys (the encoder whitelists them); every value masked.
5. Update [`metrics.md`](metrics.md) (catalogue), the dashboard that consumes it, the alert that reads it and the runbook that covers it — in the same change. A new dashboard panel or alert rule goes through Git (the provider is the contract).
6. Re-verify: `./gradlew :apps:agentflow-app:check`, the clean-start test (if dashboards/alerts changed) and the scrape path if the new surface is scraped.

---

*The handbook is a guide: the source of truth lives in the linked documents and in the Git-provisioned files. If this document and the Git files disagree, the Git files win — fix this document in the same change.*
