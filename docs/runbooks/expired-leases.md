# Runbook — AgentFlow expired leases (stuck runs)

> **Status:** Accepted
> **Owner:** engineering (on-call)
> **Alert:** `AgentFlow expired leases (stuck runs)` (`agentflow-expired-leases`, severity `warning`)
> **Task:** `S13-18`
> **Upstream reference:** the rule in [`infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml`](../../infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml) (`agentflow-expired-leases`), the workflow family in [`docs/observability/metrics.md`](../observability/metrics.md), the durable-execution model in [`docs/architecture/durable-execution.md`](../architecture/durable-execution.md), and [`docs/runbooks/workflow-recovery.md`](workflow-recovery.md).

This runbook is for the operator who receives this page. It says what the alert
means, what is affected, how to confirm the signal, what is safe to do, and when
the incident is over. It never suggests a manual run-state edit — the state
machine is the authority, and recovery is automatic.

Fires when `rate(agentflow_workflowexecution_lease_expired_total[5m]) > 0` — the
recovery scheduler keeps scanning expired-lease candidates — sustained for **5
minutes** (`for: 5m`).

## Symptoms and impact

- Runs are stuck/crashed — the recovery scheduler keeps finding expired leases
  (a stale worker, a wedged step, a crashed recovery).
- The **AgentFlow / Workflow & Kafka** dashboard retries/lease-recovery panels
  rise.
- Running workflows make no progress until a worker re-claims them; no run is
  lost.

## Expected local-dev state

The counter is emitted only by the workflow-worker (`WorkflowRecoveryScheduler`). A local API boot emits no metric series for this family — this rule is deliberately silent there (`noDataState: OK`).

## Diagnostics

All commands run from the repo root against the local stack (default ports).

1. The scan load (the counter measures the **scan load** — every expired-lease candidate the scheduler scanned — not only re-claims):

   ```bash
   curl -s 'http://localhost:9090/api/v1/query?query=rate%28agentflow_workflowexecution_lease_expired_total%5B5m%5D%29'
   ```

2. Re-claims: `curl -s 'http://localhost:9090/api/v1/query?query=rate%28agentflow_workflow_recoveries%5B5m%5D%29'` — a high scan rate and a flat recoveries rate is the signature of crashed workers (candidates not re-claimed).

   Note: the raw metric name `agentflow_workflow_recoveries` is kept verbatim from the instrumentation — check the catalogue if it does not render on the dashboard.
3. The worker: is the workflow-worker healthy — check the worker logs (`service_name="agentflow-app"`), the recoveries rate and the runs-by-state panels.

## Likely causes and safe mitigations

| Cause | Action |
|---|---|
| Crashed/wedged worker | Restart the workflow-worker role; the recovery scheduler re-claims expired leases automatically once the worker is healthy. |
| Lease recovery looping (stuck run) | Inspect the run's history (API/DB) — the state machine is the authority; escalate per `docs/runbooks/workflow-recovery.md`, which owns cancel/retry/force-lease procedures. |
| Recovery disabled on a deploy | Check the `agentflow.worker.recovery-enabled` config; if deliberately off, silence is expected and this alert is the signal — do not enable a stopped worker by hand. |

## Rollback — and idempotency

Lease recovery is automatic and idempotent by design: every worker write checks
the `lease_epoch` fence, and the state machine transitions run state in one
transaction with the `workflow_events` record. Re-claiming a run is therefore
safe and must **not** be done by hand — the scheduler does it within ~35 s of a
worker leaving the lease. Do not force a run to a state by hand, do not edit
`lease_until`, and do not re-run an already-recovered step; cancel/retry/force
lease go through the workflow-recovery runbook and the state machine.

## Recovery criterion

`rate(agentflow_workflowexecution_lease_expired_total[5m])` returns to 0 for the
`for: 5m` guard; the `recoveries` rate rises back to match healthy re-claims (the
live state is visible via `GET /api/prometheus/grafana/api/v1/rules`).

## Escalation

Escalate to the platform/on-call owner when the scan load does not clear after a
worker restart, when a run loops through recovery repeatedly, or when the worker
is healthy but candidates are not being re-claimed — do not force-claim by hand.
