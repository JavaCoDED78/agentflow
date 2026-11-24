# Runbook — AgentFlow API 5xx error rate

> **Status:** Accepted
> **Owner:** engineering (on-call)
> **Alert:** `AgentFlow API 5xx error rate` (`agentflow-api-error-rate`, severity `critical`)
> **Task:** `S13-18`
> **Upstream reference:** the rule in [`infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml`](../../infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml) (`agentflow-api-error-rate`), the HTTP family in [`docs/observability/metrics.md`](../observability/metrics.md), and the telemetry rules in [`docs/observability/standards.md`](../observability/standards.md).

This runbook is for the operator who receives this page. It says what the alert
means, what is affected, how to confirm the signal, what is safe to do, and when
the incident is over. It never suggests a manual database edit — a 5xx burst is an
application/deployment event, not a data-integrity event.

Fires when the dedicated Prometheus (`agentflow-prometheus`) sees
`sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) > 0.5` — more than
half a 5xx request per second — sustained for **5 minutes** (`for: 5m`). It is the
user-facing "the API is degraded" signal: the only rule that pages on a real
error-rate breach.

## Symptoms and impact

- API clients get `5xx` responses; the **AgentFlow / API/JVM/DB** dashboard shows
  the 5xx error-rate panel rising.
- Requests queue, time out, or fail; orchestrators and callers see error responses.
- Workflows already created are **not** lost — they live in PostgreSQL — but
  their clients may see errors and re-send on retry.

## Expected local-dev state

The metric exists only while the app is scraped. In a local dev stack (app on the host via `bootRun`, repo-configured scrape target `host.docker.internal:8080`) the scrape target is DOWN while the app is not running — the scrape-target-down rule (`agentflow-scrape-target-down`) carries that signal, and this rule is deliberately silent on missing data (`noDataState: OK`). A real series breaching the threshold is what pages.

## Diagnostics

All commands run from the repo root against the local stack (default ports).

1. Confirm the breach (expect the value to be rising above 0.5 req/s):

   ```bash
   curl -s 'http://localhost:9090/api/v1/query?query=sum%28rate%28http_server_requests_seconds_count%7Bstatus%3D~%225..%22%7D%5B5m%5D%29%29'
   ```

2. Which 5xx are flowing: `curl -s 'http://localhost:9090/api/v1/query?query=sum+by+%28status%29+(rate%28http_server_requests_seconds_count%5B5m%5D%29)'` — expect a `5..` status series to be rising.
3. What the 5xx look like (status/method/path split):

   ```bash
   curl -s 'http://localhost:9090/api/v1/query?query=sum+by+%28status%2Cmethod%2Cpath%29+(rate%28http_server_requests_seconds_count%5B5m%5D%29)' \
     | python3 -c 'import json,sys; [print(i["metric"], i["value"][1]) for i in json.load(sys.stdin)["data"]["result"]]'
   ```

4. Which exception class dominates — the application logs (Loki: `service_name="agentflow-app"`, severity `ERROR`, last 5–15 minutes, over Grafana at `localhost:3000`).
5. Grafana: the **AgentFlow / API/JVM/DB** dashboard, 5xx error-rate panel, `agentflow-prometheus` datasource.

## Likely causes and safe mitigations

| Cause | Action |
|---|---|
| Application bug / regression in a controller or service | Check the latest deploy; read the exception class/message in logs and the 5xx panel; if recent, roll the deploy back or forward-fix (see §6). |
| Dependency outage (PostgreSQL, Kafka, Redis, Keycloak) | Check `docker ps` / the dashboards' DB-pool and Kafka panels; act per the dependency's runbook (`db-saturation.md`, `kafka-lag.md`, `redis-outage.md`, `observability-scrape-target-down.md`). |
| Legitimate load spike with thin capacity | A real spike is not a code bug — check the latency and DB-pool panels; the mitigation is capacity/deployment headroom, not a manual restart. |
| Rate limit / throttle | Confirm the 5xx are not throttling — throttling is a 429, not a 5xx; nothing to fix here. |
| Scrape misconfigured (series absent, `up=0`) | Not this alert — the scrape-target-down rule carries it; fix the scrape per that runbook. |

## Rollback — and idempotency

A deploy rollback (previous image/values) restores the pre-incident behaviour and
is safe by nature: it changes **no** workflow state, no outbox row, no tenant data
and no record — it never has to re-create a run or a record.

Do **not** "fix" this alert by clearing it, restarting the scrape, or touching
PostgreSQL — those are not recovery, and a business failure must not be patched
with a data edit. Client re-sends and idempotency keys are the caller's contract
(`Idempotency-Key` on create-run; the run and the outbox are durable) — never
re-send or re-create the business change by hand.

## Recovery criterion

`sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))` drops below
0.5 req/s and stays there for the `for: 5m` guard — the alert clears within one
evaluation interval after the last breach (the live state is visible via
`GET /api/prometheus/grafana/api/v1/rules`).

## Escalation

Escalate to the platform/on-call owner when the 5xx burst does not clear after
the deploy rollback or forward-fix, or when the 5xx are correlated with a
dependency that is itself unhealthy — act per that dependency's runbook first; do
not mask the dependency signal by restarting the app.
