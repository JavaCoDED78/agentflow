# Runbook: AgentFlow expired leases (stuck runs)

Alert: `AgentFlow expired leases (stuck runs)` (`agentflow-expired-leases`, severity `warning`).

Fires when `rate(agentflow_workflowexecution_lease_expired_total[5m]) > 0` for more than 5 minutes — the recovery scheduler keeps scanning expired-lease candidates.

## Symptoms and impact

- Runs are stuck/crashed — the recovery scheduler keeps finding expired leases (a stale worker, a wedged step, a crashed recovery).
- The **AgentFlow / Workflow & Kafka** dashboard retries/lease recovery panels rise.

## Expected local-dev state

The counter is emitted only by the workflow-worker (`WorkflowRecoveryScheduler`). A local API boot emits no metric series for this family — this rule is deliberately silent there (`noDataState: OK`).

## Diagnostics

1. The scan load: `curl -s 'http://localhost:9090/api/v1/query?query=rate%28agentflow_workflowexecution_lease_expired_total%5B5m%5D%29'` — the counter measures the **scan load** (every expired-lease candidate the scheduler scanned), not only re-claims (see the metrics catalogue).
2. Re-claims: `curl -s 'http://localhost:9090/api/v1/query?query=rate%28agentflow_workflow_recoveries%5B5m%5D%29'` — a high scan rate and a flat recoveries rate is the signature of crashed workers (candidates not re-claimed).
3. The worker: is the workflow-worker healthy — check the worker logs (`service_name="agentflow-app"`), the `agentflow_workflow.recoveries` rate and the runs-by-state panels.

## Likely causes and safe actions

| Cause | Action |
|---|---|
| Crashed/wedged worker | Restart the workflow-worker role; the recovery scheduler re-claims expired leases automatically once the worker is healthy. |
| Lease recovery looping (stuck run) | Inspect the run's history (API/DB) — the state machine is the authority; escalate per `docs/runbooks/workflow-recovery.md`, which owns cancel/retry/force-lease procedures. |
| Recovery disabled on a deploy | Check the `agentflow.worker.recovery-enabled` config; if deliberately off, silence is expected and this alert is the signal — do not enable a stopped worker by hand. |

## Recovery criterion

`rate(agentflow_workflowexecution_lease_expired_total[5m])` returns to 0 for the `for: 5m` guard; the `recoveries` rate rises back to match healthy re-claims.
