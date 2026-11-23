# `infra/observability/` — telemetry stack

The observability stack is `grafana/otel-lgtm` (the **L**oki-**G**rafana-**T**empo-**M**etrics all-in-one image) behind a standalone OpenTelemetry Collector ingress and a dedicated, repo-owned Prometheus, brought up by the `observability` compose profile. Everything is configuration as code: the collector boots from `otel-collector/collector.yaml`, Prometheus boots from `prometheus/prometheus.yml`, both mounted read-only, and there is nothing to set up by hand.

## Layout

| Path | Contents |
|---|---|
| `otel-collector/collector.yaml` | The standalone Collector (S13-04): OTLP ingress, memory limiter, redaction, batch, health check; fans signals out to the lgtm backends. |
| `prometheus/prometheus.yml` | The dedicated Prometheus (S13-05): scrape configs for the application (`/actuator/prometheus`, bearer token) and the Collector's own metrics (:8888); versioned scrape intervals. |

## Why lgtm backends behind a standalone collector

The standalone design (OpenTelemetry Collector → Prometheus + Loki + Tempo, each its own container) is not viable for the local stack *as backends*: the Loki exporter is absent from `otel/opentelemetry-collector-contrib` and `grafana/loki` exposes no OTLP ingest. `grafana/otel-lgtm` bundles the Collector with the Loki exporter and keeps all three stores working in one image, which is the officially recommended backend for local dev/demo. A production deployment would run these as separate services (deployment sprint).

Since S13-04 the **standalone contrib collector is the OTLP ingress**: the application exports to it (not directly into lgtm), so the ingress contract — memory limit, batching, attribute redaction, health check — is this repo's config, independent of the all-in-one image. Since S13-05 the dedicated **Prometheus is the repo-owned metrics store** and owns the host-published :9090; it scrapes the application's `/actuator/prometheus` endpoint (the S13-02 contract: metrics ship through the Prometheus scrape, never OTLP push) and the collector's self-metrics. lgtm's bundled Prometheus keeps serving Grafana internally until S13-06/07/08 swap the datasources and the collector exporter endpoints to the dedicated backends; S13-07/08 replace the lgtm Loki/Tempo with dedicated services the same way.

## Endpoints (host-published)

| Port | Service | Purpose |
|---|---|---|
| `4317` / `4318` | otel-collector | OTLP (gRPC/HTTP) ingress — the only OTLP surface the app talks to |
| `13133` | otel-collector | `health_check` extension (smoke scripts / operator probes) |
| `9090` | prometheus | Prometheus UI / HTTP API — the repo-owned metrics store (debug; the Grafana datasource in S13-06) |
| `3000` | Grafana | dashboards; exposes Prometheus, Loki and Tempo as datasources |
| `3100` | Loki | direct access (debug) |
| `3200` | Tempo | direct access (debug) |

## Configuration

- `otel-collector/collector.yaml` (this directory) is the standalone collector config: `otlp` receivers, `memory_limiter` (256 MiB ceiling, 64 MiB spike), `attributes/redaction` (drops body/prompt/document attributes — defense in depth; the app instrumentation never emits them, S13-03), `batch` (5 s / 8 192 / 16 384), `health_check` extension, a `prometheus` telemetry reader exposing the collector's own metrics on :8888 (scraped by the dedicated Prometheus over the observability network), and one `otlp_grpc` exporter per pipeline to the lgtm backends. The image is pinned by digest in `infra/compose/docker-compose.yml`.
- Config validation: `docker run --rm -v <repo>/infra/observability/otel-collector/collector.yaml:/etc/otelcol-contrib/config.yaml:ro ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:<pinned> validate --config=/etc/otelcol-contrib/config.yaml` (also the compose healthcheck — the image is distroless, so the collector binary re-validates the mounted config; the runtime probe is the `health_check` extension on `:13133`).
- `prometheus/prometheus.yml` is the dedicated Prometheus config: 15 s scrape/evaluation intervals and two jobs — `agentflow-api` (the application's `/actuator/prometheus`, reached as `host.docker.internal:8080` from the container since the app runs on the host via `bootRun`; DOWN while the app is not running) and `otel-collector` (`otel-collector:8888`, internal network). Retention is versioned as compose command flags (`--storage.tsdb.retention.time=7d`, `--storage.tsdb.retention.size=2GB`) — Prometheus keeps retention a startup flag, not a config-file key. The image is pinned by multi-arch index digest; the TSDB persists on the `agentflow-prometheus` named volume, so a removed container comes back with its history.
- lgtm bundles its own component configs (`/otel-lgtm/`); this repo pins the image by digest so the bundled stack (Collector, Prometheus, Loki, Tempo, Grafana) is version-locked in one place. Data (Prometheus, Loki, Grafana, Tempo) persists on the `agentflow-observability` named volume. Grafana's admin password defaults to a local-only value via `GF_SECURITY_ADMIN_PASSWORD` (override through a local `.env`).

## Scraping the application

`/actuator/prometheus` sits behind the default-deny secured chain (S13-02): a scrape must carry a bearer token. The token file is mounted at `/etc/prometheus/scrape/token` — a committed, clearly-marked **placeholder** by default (not a valid token; the `agentflow-api` target is DOWN/401 until a real one is provisioned), never a real token in Git. Mint a real one with the machine-to-machine client (S04-02):

```bash
bash scripts/mint-scrape-token.sh            # one token (~5 min of scraping)
bash scripts/mint-scrape-token.sh --watch    # refresh every 240 s for a session
```

Keycloak signs local access tokens for 300 s; Prometheus re-reads the token file before every scrape (verified on the pinned image), so the script only rewrites the file — no Prometheus reload or restart. After minting, `agentflow-api` comes UP on the next 15 s scrape. **Never commit the file with a real token**; restore the placeholder with `git checkout -- infra/observability/prometheus/scrape/token` when done.

## Verification (S13-04 backend smoke + S13-05 acceptance)

The S13-04 backend smoke still applies: with the `observability` profile up, OTLP/JSON payloads posted to `localhost:4318` (`/v1/traces`, `/v1/metrics`, `/v1/logs`, `service.name=agentflow-smoke`, current `date +%s` nanosecond timestamps — the search windows ignore stale payloads) are visible in the backends: Tempo search returns the smoke trace (`/api/search?tags=service.name=agentflow-smoke`), lgtm's bundled Prometheus returns the smoke gauge and Loki returns the smoke log stream (`{service_name="agentflow-smoke"}`) — all carrying only bounded labels.

S13-05 acceptance, live run this session from a clean state (`docker compose --profile observability up -d`, nothing pre-running):

1. `docker compose config --quiet` — exit 0.
2. Both services up and healthy: `agentflow-prometheus` (healthcheck: busybox `wget` against `/-/healthy`) and `agentflow-otel-collector` (healthcheck: collector `validate`; `health_check` extension 200).
3. **Acceptance — config restores from Git after container removal.** `docker rm -f agentflow-prometheus` → `docker compose --profile observability up -d` → the container is recreated healthy with the Git configuration; `GET localhost:9090/api/v1/targets` lists both jobs and `up{job="otel-collector"} = 1`.
4. Collector self-metrics: `otelcol_process_uptime`, `otelcol_process_memory_rss`, `otelcol_exporter_queue_size` and friends are scraped from `otel-collector:8888` (bounded component-id labels only).
5. End-to-end application scrape: with the `identity` profile (Keycloak) and the app running (`bootRun`, local profile), `bash scripts/mint-scrape-token.sh` → within one scrape interval `up{job="agentflow-api"} = 1` and `jvm_memory_used_bytes`, `http_server_requests_seconds_count` and `hikaricp_connections` series are present in the dedicated Prometheus.
6. `bash scripts/smoke-local.sh --profile observability` — SMOKE TEST: PASS (all three observability-profile containers healthy, then torn down).

Scaffolded in **S01-16** (the multi-container observability design was replaced by lgtm once it became clear the Loki exporter is unavailable from the standard contrib collector); the standalone Collector ingress landed in **S13-04**; the dedicated Prometheus in **S13-05**.
