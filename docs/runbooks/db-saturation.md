# Runbook — AgentFlow DB pool saturation

> **Status:** Accepted
> **Owner:** engineering (on-call)
> **Alert:** `AgentFlow DB pool saturating` (`agentflow-db-saturation`, severity `critical`)
> **Task:** `S13-18`
> **Upstream reference:** the rule in [`infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml`](../../infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml) (`agentflow-db-saturation`), the DB-pool family in [`docs/observability/metrics.md`](../observability/metrics.md), and the telemetry rules in [`docs/observability/standards.md`](../observability/standards.md).

This runbook is for the operator who receives this page. It says what the alert
means, what is affected, how to confirm the signal, what is safe to do, and when
the incident is over. It never suggests killing connections by hand or a manual
PostgreSQL edit — the pool self-heals when the leaking code is fixed.

Fires when `hikaricp_connections_active / hikaricp_connections_max` exceeds 0.8
sustained for **5 minutes** (`for: 5m`). The pool is the shared connection budget
for the API/worker roles; a saturated pool blocks every request.

## Symptoms and impact

- API requests queue, time out, or fail while the pool is saturated.
- The **AgentFlow / API/JVM/DB** dashboard DB-pool panels (utilization, active/max,
  pending, timeouts) rise.
- Downstream callers see latency and 5xx; no data is lost or changed.

## Expected local-dev state

The pool meters exist only while the app is scraped (`agentflow-api` target). A local `bootRun` with the repo-configured scrape target DOWN is silenced by this rule (`noDataState: OK`) and carried by the scrape-target-down rule; this rule pages on a real saturated pool.

## Diagnostics

All commands run from the repo root against the local stack (default ports).

1. Current saturation:

   ```bash
   curl -s 'http://localhost:9090/api/v1/query?query=(sum+%28hikaricp_connections_active%29)+%2F+(sum+%28hikaricp_connections_max%29%29'
   ```

2. Idle vs active: `curl -s 'http://localhost:9090/api/v1/query?query=sum+%28hikaricp_connections_active%29'` and the dashboard's active/max, pending and timeout panels.
3. Where connections go: the **AgentFlow / API/JVM/DB** dashboard DB-pool panels, plus the application logs (`service_name="agentflow-app"` in Loki) for long-running queries — **no manual DB edits**; a query storm is a code/deployment problem, not a manual fix.

## Likely causes and safe mitigations

| Cause | Action |
|---|---|
| Hung query / connection leak | Reproduce, then fix the leak (deploy fix); never kill connections by hand — the pool recovers once the leaking code is fixed and the app restarts. |
| Legitimate load spike | Check the API error-rate and latency panels; if the spike is real, raise the pool per the deployment guide and let the change land with the change, not by UI edit. |
| PostgreSQL unhealthy | Check `docker ps` / the `agentflow-postgres` container health; act per the deployment runbook; never fail over by hand. |

## Redrive / rollback — and idempotency

No data redrive exists for a saturated pool — the pool is a runtime resource, not a
store. A deploy rollback or forward-fix of the offender restores the pre-incident
behaviour and changes **no** workflow state, no outbox row, no tenant data; it
never has to re-create a run or a record. Do **not** restart the pool or the app
to "clear" the alert — a pending request inside the pool is the signal to fix the
leak, and a restart alone does not fix the leak.

## Recovery criterion

The active/max ratio falls below 0.8 and stays there for the `for: 5m` guard —
the rule clears within one evaluation interval after the last breach (the live
state is visible via `GET /api/prometheus/grafana/api/v1/rules`).

## Escalation

Escalate to the platform/on-call owner when the pool saturates again after the
fix, when the active/max ratio stays above 0.8 despite a healthy Postgres or when
the leak is not yet identified — do not mask the symptom by raising the pool
without fixing the cause.
