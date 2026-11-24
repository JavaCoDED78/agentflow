# Runbook: AgentFlow DB pool saturation

Alert: `AgentFlow DB pool saturating` (`agentflow-db-saturation`, severity `critical`).

Fires when `hikaricp_connections_active / hikaricp_connections_max` exceeds 0.8 for more than 5 minutes.

## Symptoms and impact

- API requests queue, time out, or fail while the pool is saturated — the pool is the shared connection budget for the API/worker roles.
- The **AgentFlow / API/JVM/DB** dashboard DB-pool panels (utilization, active/max, pending, timeouts) rise.

## Expected local-dev state

The pool meters exist only while the app is scraped (`agentflow-api` target). A local `bootRun` with the repo-configured scrape target DOWN is silenced by this rule (`noDataState: OK`) and carried by the scrape-target-down rule; this rule pages on a real saturated pool.

## Diagnostics

1. Current saturation: `curl -s 'http://localhost:9090/api/v1/query?query=sum+(hikaricp_connections_active)+%2F+sum+(hikaricp_connections_max)'` — a ratio > 0.8.
2. Idle vs active: `curl -s 'http://localhost:9090/api/v1/query?query=sum+(hikaricp_connections_active)'` and the dashboard's active/max, pending and timeout panels.
3. Where connections go: the **AgentFlow / API/JVM/DB** dashboard DB-pool panels, plus the application logs (`service_name="agentflow-app"` in Loki) for long-running queries — **no manual DB edits**; a query storm is a code/deployment problem, not a manual fix.

## Likely causes and safe actions

| Cause | Action |
|---|---|
| Hung query / connection leak | Reproduce, then fix the leak (deploy fix); never kill connections by hand — the pool will recover once the leaking code is fixed and the app restarted. |
| Legitimate load spike | Check the API error-rate panel and the error burst; if the spike is real, raise the pool per the deployment guide and let the fix land with the change, not by UI edit. |
| PostgreSQL unhealthy | Check `docker ps` / the `agentflow-postgres` container health; act per the deployment runbook; never fail over by hand. |

## Recovery criterion

The active/max ratio falls below 0.8 and stays there for the `for: 5m` guard — the rule clears within one evaluation interval after the last breach.
