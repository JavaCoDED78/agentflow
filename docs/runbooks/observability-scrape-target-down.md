# Runbook — AgentFlow scrape target down

> **Status:** Accepted
> **Owner:** engineering (on-call)
> **Alert:** `AgentFlow scrape target down` (`agentflow-scrape-target-down`, severity `warning`)
> **Task:** `S13-18`
> **Upstream reference:** the rule in [`infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml`](../../infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml) (`agentflow-scrape-target-down`), the Prometheus config in [`infra/observability/prometheus/prometheus.yml`](../../infra/observability/prometheus/prometheus.yml), the collector config in [`infra/observability/otel-collector/collector.yaml`](../../infra/observability/otel-collector/collector.yaml), and the dashboards in [`infra/observability/grafana/dashboards/`](../../infra/observability/grafana/dashboards/).

This runbook is for the operator who receives this page. It says what the alert
means, what is affected, how to confirm the signal, what is safe to do, and when
the incident is over. It never suggests editing provisioning by hand.

Fires when the dedicated Prometheus cannot scrape a configured AgentFlow target —
`up{job=~"agentflow-api|otel-collector"} < 1` or the series is missing entirely
(`noDataState: Alerting` — a target dropping out of discovery is as alertable as
`up == 0`) — for more than 1 minute (`for: 1m`).

## Symptoms and impact

- A panel on the **AgentFlow / Overview** dashboard goes to 0 or "No data".
- Metrics from the affected target stop flowing into the dedicated Prometheus
  (7-day TSDB on the `agentflow-prometheus` volume).
- Every downstream signal depending on the affected target (dashboards, alerts
  reading its series, Grafana panels) goes dark.

## Expected local-dev state

`agentflow-api` is **expected to be DOWN whenever the application is not
running** — the app runs on the host via `bootRun`, not as a container. Before
acting, check whether the app is simply stopped: an alert on `agentflow-api`
alone, with the app stopped, is normal local noise. `otel-collector` down is
never expected — the collector is a compose service.

## Diagnostics

All commands run from the repo root against the local stack (default ports).

1. Which target is down:

   ```bash
   curl -s 'http://localhost:9090/api/v1/query?query=up'
   ```

2. Prometheus view of the target:

   ```bash
   curl -s 'http://localhost:9090/api/v1/targets' \
     | python3 -c 'import json,sys; [print(t["labels"]["job"], t["health"], t["lastError"]) for t in json.load(sys.stdin)["data"]["activeTargets"]]'
   ```

   `lastError` shows the scrape failure (401 = token problem, connection refused = app/collector not running, DNS = network).
3. Containers: `docker ps --format '{{.Names}}\t{{.Status}}' | grep agentflow` — `agentflow-prometheus` and `agentflow-otel-collector` must be `healthy`.

## Likely causes and safe mitigations

| Cause | Action |
|---|---|
| App not running locally (target `agentflow-api`) | Expected — start it (`./gradlew :apps:agentflow-app:bootRun --args='--spring.profiles.active=local'`) or ignore. |
| Scrape token expired (target `agentflow-api`, `lastError` 401) | `bash scripts/mint-scrape-token.sh` — Prometheus re-reads the token file before every scrape; no restart needed. |
| `agentflow-prometheus` container down/unhealthy | `docker compose --file infra/compose/docker-compose.yml --profile observability up -d` — the config restores from Git automatically (S13-05 acceptance). |
| `otel-collector` container down/unhealthy | Recreate it the same way; check `docker logs agentflow-otel-collector` for a config validation failure. |
| Broken config committed | `git diff` the last change to `infra/observability/prometheus/prometheus.yml` or `infra/observability/otel-collector/collector.yaml`; restore with `git checkout`. |

## Redrive — and idempotency

There is no data redrive for a scrape failure — the TSDB is history, and a scrape
incident does not touch workflow, outbox, or document state. Mitigations restore
the **scrape path**: starting the app, re-minting the token file, or rebuilding
the collector restores the series — nothing is re-published, re-indexed, or
re-run. Do not delete the TSDB volume to "clear" the alert; its history is the
only evidence of the incident. A committed config fix is a Git change, not a UI
edit (`allowUiUpdates: false`).

## Recovery criterion

`up{job="<target>"} == 1` in the dedicated Prometheus (`http://localhost:9090/api/v1/query?query=up`) within one scrape interval (15 s) of the fix, and the alert clears after its `for: 1m` guard (the live state is visible via `GET /api/prometheus/grafana/api/v1/rules`).

## Escalation

Escalate to the platform/on-call owner when `otel-collector` stays down after a
rebuild, when the scrape failure is not in the bounded vocabulary above (DNS,
401, connection refused, config error), or when `agentflow-api` and `otel-collector`
fail together (a collector/shared-dependency event, not an app event).
