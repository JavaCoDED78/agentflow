# Runbook: AgentFlow API 5xx error rate

Alert: `AgentFlow API 5xx error rate` (`agentflow-api-error-rate`, severity `critical`).

Fires when the dedicated Prometheus (`agentflow-prometheus`) sees `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) > 0.5` requests/s for more than 5 minutes (`for: 5m`).

## Symptoms and impact

- API clients get `5xx` responses; the **AgentFlow / API/JVM/DB** dashboard (`agentflow-api-jvm-db`) shows the 5xx error-rate panel rising.
- Requests queue or time out; the alert is the user-facing "the API is degraded" signal.

## Expected local-dev state

The metric exists only while the app is scraped. In a local dev stack (app on the host via `bootRun`, repo-configured scrape target `host.docker.internal:8080`) the scrape target is DOWN while the app is not running — the scrape-target-down rule (`agentflow-scrape-target-down`) carries that signal, and this rule is deliberately silent on missing data (`noDataState: OK`). A real series breaching the threshold is what pages.

## Diagnostics

1. Which 5xx are flowing: `curl -s 'http://localhost:9090/api/v1/query?query=sum+by+%28status%29+(rate%28http_server_requests_seconds_count%5B5m%5D%29)'` — expect a `5..` status series to be rising.
2. What the 5xx look like: `curl -s 'http://localhost:9090/api/v1/query?query=sum+by+%28status,method,path%29+(rate%28http_server_requests_seconds_count%5B5m%5D%29)' | python3 -c 'import json,sys; [print(i["metric"], i["value"][1]) for i in json.load(sys.stdin)["data"]["result"]]'` and the matching Loki queries over Grafana (`localhost:3000`, explore with `service_name="agentflow-app"`).
3. Grafana: the **AgentFlow / API/JVM/DB** dashboard, 5xx error-rate panel, `agentflow-prometheus` datasource.

## Likely causes and safe actions

| Cause | Action |
|---|---|
| Application bug / regression in a controller or service | Check the latest deploy; read the exception class/message in logs and the 5xx panel; if recent, roll the deploy back or forward-fix (see `docs/runbooks/` for the incident flow — no manual DB edits). |
| Dependency outage (PostgreSQL, Kafka, Redis, Keycloak) | Check `docker ps` / the dashboards' DB-pool and Kafka panels; act per the dependency's runbook (`db-saturation.md`, `kafka-lag.md`, `redis-outage.md`, `observability-scrape-target-down.md`). |
| Rate limit / throttle | Confirm the 5xx are not throttling — throttling is a 429, not a 5xx; nothing to fix here. |
| Scrape misconfigured (series absent, `up=0`) | Not this alert — the scrape-target-down rule carries it; fix the scrape per that runbook. |

## Recovery criterion

`sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))` drops below 0.5 req/s and the rule's `for: 5m` guard clears — the alert instance returns to Normal within one evaluation interval after the last breach.
