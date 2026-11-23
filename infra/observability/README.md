# `infra/observability/` — telemetry stack

The observability stack is `grafana/otel-lgtm` (the **L**oki-**G**rafana-**T**empo-**M**etrics all-in-one image) behind a standalone OpenTelemetry Collector ingress, brought up by the `observability` compose profile. Everything is configuration as code: the collector boots from `otel-collector/collector.yaml`, mounted read-only, and there is nothing to set up by hand.

## Layout

| Path | Contents |
|---|---|
| `otel-collector/collector.yaml` | The standalone Collector (S13-04): OTLP ingress, memory limiter, redaction, batch, health check; fans signals out to the lgtm backends. |

## Why lgtm backends behind a standalone collector

The standalone design (OpenTelemetry Collector → Prometheus + Loki + Tempo, each its own container) is not viable for the local stack *as backends*: the Loki exporter is absent from `otel/opentelemetry-collector-contrib` and `grafana/loki` exposes no OTLP ingest. `grafana/otel-lgtm` bundles the Collector with the Loki exporter and keeps all three stores working in one image, which is the officially recommended backend for local dev/demo. A production deployment would run these as separate services (deployment sprint).

Since S13-04 the **standalone contrib collector is the OTLP ingress**: the application exports to it (not directly into lgtm), so the ingress contract — memory limit, batching, attribute redaction, health check — is this repo's config, independent of the all-in-one image. S13-05/07/08 will replace the lgtm backends with dedicated Prometheus/Loki/Tempo services by swapping the exporter endpoints in `otel-collector/collector.yaml`.

## Endpoints (host-published)

| Port | Service | Purpose |
|---|---|---|
| `4317` / `4318` | otel-collector | OTLP (gRPC/HTTP) ingress — the only OTLP surface the app talks to |
| `13133` | otel-collector | `health_check` extension (smoke scripts / operator probes) |
| `3000` | Grafana | dashboards; exposes Prometheus, Loki and Tempo as datasources |
| `9090` | Prometheus | direct access (debug) |
| `3100` | Loki | direct access (debug) |
| `3200` | Tempo | direct access (debug) |

## Configuration

- `otel-collector/collector.yaml` (this directory) is the standalone collector config: `otlp` receivers, `memory_limiter` (256 MiB ceiling, 64 MiB spike), `attributes/redaction` (drops body/prompt/document attributes — defense in depth; the app instrumentation never emits them, S13-03), `batch` (5 s / 8 192 / 16 384), `health_check` extension and one `otlp_grpc` exporter per pipeline to the lgtm backends. The image is pinned by digest in `infra/compose/docker-compose.yml`.
- Config validation: `docker run --rm -v <repo>/infra/observability/otel-collector/collector.yaml:/etc/otelcol-contrib/config.yaml:ro ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:<pinned> validate --config=/etc/otelcol-contrib/config.yaml` (also the compose healthcheck — the image is distroless, so the collector binary re-validates the mounted config; the runtime probe is the `health_check` extension on `:13133`).
- lgtm bundles its own component configs (`/otel-lgtm/`); this repo pins the image by digest so the bundled stack (Collector, Prometheus, Loki, Tempo, Grafana) is version-locked in one place. Data (Prometheus, Loki, Grafana, Tempo) persists on the `agentflow-observability` named volume. Grafana's admin password defaults to a local-only value via `GF_SECURITY_ADMIN_PASSWORD` (override through a local `.env`).

## Verification (S13-04 acceptance)

With the `observability` profile up, OTLP/JSON payloads posted to `localhost:4318` (`/v1/traces`, `/v1/metrics`, `/v1/logs`, `service.name=agentflow-smoke`) are visible in the backends: Tempo search returns the smoke trace (`/api/search?tags=service.name=agentflow-smoke`), Prometheus returns the smoke gauge (`/api/v1/query?query=agentflow_smoke_metric`) and Loki returns the smoke log stream (`{service_name="agentflow-smoke"}`) — all carrying only bounded labels.

Scaffolded in **S01-16** (the multi-container observability design was replaced by lgtm once it became clear the Loki exporter is unavailable from the standard contrib collector); the standalone Collector ingress landed in **S13-04**.
