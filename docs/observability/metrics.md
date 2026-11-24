# Metrics catalogue — workflow execution

- **Owner:** `workflowexecution` (`com.example.agentflow.workflowexecution`) — the runs, steps,
  retries, expired leases and cancellations a worker drives.
- **Task:** `S13-11` (P0) — instruments the workflow-execution metrics the dashboards (S13-14…S13-16),
  alerts (S13-17) and SLOs (S13-18) build on.
- **Standards:** [`standards.md`](standards.md) — metric naming (`agentflow.<module>.<object>.<measure>[.unit]`),
  unit-suffixed names, bounded state labels, no identifiers in labels (invariant 9), no tenant/run/user ids
  anywhere (invariant 9). Every metric here registers on `io.micrometer.core.instrument.MeterRegistry`
  (the shared Prometheus registry in the app), never a hand-rolled global.
- **Scope:** the `workflowexecution` family only. The `messaging` (outbox/inbox/DLQ) and `ai`/`usage`/`knowledgebase`
  (AI/RAG families) catalogues land with `S13-12`/`S13-13` and are intentionally **not** listed here.

---

## Workflow-execution metrics

| Metric | Type | Unit / dimension | Labels | Meaning |
|---|---|---|---|---|
| `agentflow.workflowexecution.run.total` | Counter | count | `state` (target state: lowercased `WorkflowRunState`, bounded set) | Successful run transitions, one increment per transition, labelled with the **target** state. A terminal transition increments exactly once; the state label is the only label and never carries an id. |
| `agentflow.workflowexecution.run.duration.seconds` | Timer | seconds | none | A terminal run transition (`SUCCEEDED`/`FAILED`/`CANCELLED`/`TIMEOUT`) records one sample per run: `run.created_at` → the terminal transition (`Clock.instant()`, clamped at 0). A readable `RunView` is required; when absent the sample is skipped (no exception, no partial metric). |
| `agentflow.workflowexecution.step.duration.seconds` | Timer | seconds | none | One sample per handler invocation, measured from just before the handler (after the HUMAN_APPROVAL short-circuit and the pre-step limit check) through the step's conclusion — success, fault, or failure. No label (high-cardinality ids never appear). |
| `agentflow.workflowexecution.step.retry.total` | Counter | count | none | One increment per step actually moved to `RETRY_WAIT`. A step that exhausts the per-run retry budget and ends `FAILED` does **not** increment (no retry happened). |
| `agentflow.workflowexecution.step.cancelled.total` | Counter | count | none | One increment per successful step transition to `CANCELLED` (invariant 4 — no other path sets a step to CANCELLED). |
| `agentflow.workflowexecution.lease.expired.total` | Counter | count | none | One increment per expired-lease candidate scanned by the recovery scheduler (`findExpiredLeaseRuns`). Runs reclaimed AND runs that lost the claim increment this — it measures the **scan load**, not only re-claims. |
| `agentflow.workflow.recoveries` | Counter (existing, S07-09) | count | none | Successful recovery re-claims. Kept verbatim (predates the catalogue) — same semantics, do not rename; `agentflow.workflowexecution.lease.expired.total` is the S13-11 addition. |
| `agentflow.workflow.approvals.expired` | Counter (existing) | count | none | Approval expirations (existing). |

### Conventions

- **Never** add `tenantId`, `runId`, `userId`, `documentId`, `agentId` or any other identifier as
  a label — the `<module>` in the name carries the signal's home (`workflowexecution`), not the run.
- Unit suffixes: durations `*.seconds` (Timer), counts `*.total` (Counter), rates `*.rate` (counter
  + rate calculation at the dashboard, never a raw label).
- A counter increments exactly when a successful transition/claim occurs; a failed transition
  (`400` gate rejection, `409` lost race, step rejected by `enforcePreStepLimits`) increments
  nothing.
- Metering never fails a business operation: the run-duration and counter recorders are wrapped
  so a metering error is a debug-level log, never a transition failure.
- Two views of the same fact exist on purpose: `run.total{state}` (from the transition service,
  the authoritative state-machine path) and the worker's `step.duration.seconds` / `step.retry.total`
  (measured around the handler). A dashboard must not sum them as "steps" — they are distinct.

## Dashboard / alert hooks (downstream, not yet defined)

Expected first consumers (S13-14 dashboards, S13-17 alerts):

- `run.total{state}` — rate of run transitions; the `failed`/`timeout`/`cancelled` series are the
  headline health signal.
- `run.duration.seconds` — p50/p99 by control-group (not by id); a run that is still in flight
  contributes nothing (its terminal transition is what samples it).
- `step.duration.seconds` — the step-level tail latency.
- `step.retry.total` / `lease.expired.total` / `recoveries` — retry storms and recovery-scan load;
  an expiry that is not reclaimed is a real incident signal (the stale run is not re-claimed).
- `step.cancelled.total` — cancellations from all paths (user cancel, run terminal, lease fence).

No alert, SLO or dashboard is defined in this document — those land with `S13-17`/`S13-18`.
