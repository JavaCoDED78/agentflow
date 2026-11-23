# Runbook: AgentFlow scrape target down

Alert: `AgentFlow scrape target down` (`agentflow-scrape-target-down`, severity `warning`).

Fires when the dedicated Prometheus cannot scrape a configured AgentFlow target —
`up{job=~"agentflow-api|otel-collector"} < 1` or the series is missing entirely
(`noDataState: Alerting` — a target dropping out of discovery is as alertable as
`up == 0`) — for more than 1 minute.

## Symptoms and impact

- A panel on the **AgentFlow / Overview** dashboard goes to 0 or "No data".
- Metrics from the affected target stop flowing into the dedicated Prometheus
  (7-day TSDB on the `agentflow-prometheus` volume).

## Expected local-dev state

`agentflow-api` is **expected to be DOWN whenever the application is not
running** — the app runs on the host via `bootRun`, not as a container. Before
acting, check whether the app is simply stopped: an alert on `agentflow-api`
alone, with the app stopped, is normal local noise. `otel-collector` down is
never expected — the collector is a compose service.

## Diagnostics

1. Which target is down: `curl -s 'http://localhost:9090/api/v1/query?query=up'`
   (or the **AgentFlow / Overview** dashboard, `http://localhost:3000`).
2. Prometheus view of the target: `curl -s 'http://localhost:9090/api/v1/targets' |
   python3 -c 'import json,sys; [print(t["labels"]["job"], t["health"], t["lastError"])
   for t in json.load(sys.stdin)["data"]["activeTargets"]]'` — `lastError` shows
   the scrape failure (401 = token problem, connection refused = app/collecting
   not running, DNS = network).
3. Containers: `docker ps --format '{{.Names}}\t{{.Status}}' | grep agentflow` —
   `agentflow-prometheus` and `agentflow-otel-collector` must be `healthy`.

## Likely causes and safe actions

| Cause | Action |
|---|---|
| App not running locally (target `agentflow-api`) | Expected — start it (`./gradlew :apps:agentflow-app:bootRun --args='--spring.profiles.active=local'`) or ignore. |
| Scrape token expired (target `agentflow-api`, `lastError` 401) | `bash scripts/mint-scrape-token.sh` — Prometheus re-reads the token file before every scrape; no restart needed. |
| `agentflow-prometheus` container down/unhealthy | `docker compose --file infra/compose/docker-compose.yml --profile observability up -d` — the config restores from Git automatically (S13-05 acceptance). |
| `otel-collector` container down/unhealthy | Recreate it the same way; check `docker logs agentflow-otel-collector` for a config validation failure. |
| Broken config committed | `git diff` the last change to `infra/observability/prometheus/prometheus.yml` or `infra/observability/otel-collector/collector.yaml`; restore with `git checkout`. |

## Recovery criterion

`up{job="<target>"} == 1` in `http://localhost:9090/api/v1/query?query=up` within
one scrape interval (15 s) of the fix, and the alert clears after its `for: 1m`
guard.
