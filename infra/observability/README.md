# `infra/observability/` — telemetry stack

The observability stack is `grafana/otel-lgtm` (the **L**oki-**G**rafana-**T**empo-**M**etrics all-in-one image). It runs the OpenTelemetry Collector, Prometheus, Loki, Tempo and Grafana as a single, pinned, sh-enabled container via the `observability` compose profile.

## Why lgtm

The standalone design (OpenTelemetry Collector → Prometheus + Loki + Tempo, each its own container) is not viable for the local stack: the Loki exporter is absent from `otel/opentelemetry-collector-contrib` and `grafana/loki` exposes no OTLP ingest. `grafana/otel-lgtm` bundles the Collector with the Loki exporter and keeps all three stores working in one image, which is the officially recommended backend for local dev/demo. A production deployment would run these as separate services (deployment sprint).

## Endpoints (host-published)

| Port | Service | Purpose |
|---|---|---|
| `3000` | Grafana | dashboards; exposes Prometheus, Loki and Tempo as datasources |
| `4317` / `4318` | OTLP (gRPC/HTTP) | application instrumentation ingress |
| `9090` | Prometheus | direct access (debug) |
| `3100` | Loki | direct access (debug) |
| `3200` | Tempo | direct access (debug) |

## Configuration

lgtm bundles its own component configs (`/otel-lgtm/`); this repo pins the image by digest so the entire stack (Collector, Prometheus, Loki, Tempo, Grafana) is version-locked in one place (see `infra/compose/docker-compose.yml`). Data (Prometheus, Loki, Grafana, Tempo) persists on the `agentflow-observability` named volume. Grafana's admin password defaults to a local-only value via `GF_SECURITY_ADMIN_PASSWORD` (override through a local `.env`).

Scaffolded in **S01-16** (the multi-container observability design was replaced by lgtm once it became clear the Loki exporter is unavailable from the standard contrib collector).
