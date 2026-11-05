# Runbook — Workflow run recovery

> **Status:** Accepted
> **Owner:** engineering (on-call)
> **Task:** `S07-17`
> **Upstream reference:** `run-state-machine.md` (statuses + transitions),
> `step-state-machine.md` (`HUMAN_APPROVAL`/`WAITING_FOR_APPROVAL` lease release),
> `error-taxonomy.md` (`error_class` + retry decision),
> `durable-execution.md` (lease + fencing + recovery model),
> `ADR-011-workflow-run-lease.md` (lease ownership + fencing).

This runbook is for on-call engineers diagnosing a run that is stuck, crashed, or
failed. It is **operational**, not architectural: it says what to look for, how to
confirm what happened, and what to do about it. The engineering model behind it is
in [`durable-execution.md`](../architecture/durable-execution.md).

## 1. Before you touch anything: recovery is automatic

A run is designed to recover itself. Three safety nets work together without
operator action:

1. **Worker drain on shutdown** — when a worker stops (deploy, restart, kill), it
   drains its current step and then **stops heartbeating**. The run lease expires.
2. **Lease expiry** — once `lease_until < now()`, no worker will touch that run
   (fencing by `lease_epoch`), so the crash window is closed.
3. **Recovery scheduler** — `WorkflowRecoveryScheduler` re-claims runs whose lease
   expired; the run stays `RUNNING` (a new worker inherits the lease and resumes it).

Because of this, **most runs fix themselves within ~35 s** (lease lifetime
`duration-seconds` = 30 s + recovery scan `recovery-fixed-rate-ms` = 5 s, default
config). Do not intervene on a run that is still `RUNNING`. Check metrics and logs
first (§2–3); only act if it does not move.

The one case that does **not** recover on its own is a run parked in
`WAITING_FOR_APPROVAL` (an approval) or `RETRY_WAIT` (retry backoff) — it is waiting
for something outside the engine (§6).

## 2. What an operator sees

### Metrics

| Metric | Type | Meaning | Where to look |
|---|---|---|---|
| `agentflow.workflow.recoveries` | Counter | Increments **once per successful re-claim** by the recovery scheduler. A rising value = the scheduler is healthy and reclaiming stale runs (`S07-09`). | Metrics dashboard (`agentflow.*`, no run/tenant label — invariant 9) |
| Run lease health | Gauge / per-tenant view | `RUNNING` runs whose `lease_until` is approaching `now()` — heartbeats stopped, the worker may be dead | `workflow_runs` table, §4 |
| Failed heartbeats | per-process counter | Rejected run heartbeats (lost lease) surfaced by the worker loop | `RunHeartbeatService#failedHeartbeats()` |

> Metric labels never carry `tenantId`, `runId`, or other high-cardinality values
> (invariant 9). Identify a run by its `runId` in **traces/logs/tables**, not in
> metric labels.

### Logs

| Component | Line you will see | Meaning |
|---|---|---|
| Recovery scheduler | `recovery pass reclaimed N stale run(s)` | Scheduler re-claimed `N` runs. Look here after a deploy/kill — a positive count is the run being recovered. |
| Recovery scheduler | `recovery pass failed; it will be retried on the next cadence` | The recovery pass threw; it retries automatically. If this repeats, the scheduler is unhealthy. |
| Worker lifecycle (start) | `workflow worker poll loop started for N tenant(s)` | Worker is live and polling. |
| Worker lifecycle (shutdown) | `worker poll loop did not drain within X ms; the in-flight run lease is left to expire (heartbeat stopped) so recovery re-claims it` | **Normal** on shutdown. The lease is deliberately left to expire so recovery re-claims. |
| Worker lifecycle (drain) | `workflow worker poll loop drained and stopped; in-flight run lease is not extended (heartbeat stopped) for {tenants}` | **Normal** clean shutdown — no more heartbeats, lease will expire. |
| Worker (heartbeat) | `heartbeat for run X rejected: <reason>` | A run heartbeat was rejected (lost lease). Transient during restart; if repeated for an active run, investigate. |

If you see the shutdown/drain lines after a deploy, **do not panic** — that is the
run lease being handed off. Look for the `recovery pass reclaimed ...` line on the
next worker that comes up.

---

## 3. Fast triage (do this first)

1. **Look at the run in the API** — `GET /api/v1/runs/{runId}`.
   Note `status`, `attempt`, and `createdAt`.
2. **Is it `RUNNING` with a fresh lease?** Let it run. Watch `agentflow.workflow.recoveries`
   rise — the run is either progressing or about to be recovered.
3. **Has it been `RUNNING` past the lease lifetime (~30 s) with a stale lease?** → §5.
4. **Is it `WAITING_FOR_APPROVAL` / `RETRY_WAIT`?** → §6 (it is parked, not crashed).
5. **Is it `FAILED`?** → §7 (retry policy exhausted).

---

## 4. Diagnostic queries (PostgreSQL)

All queries are **tenant-scoped** (`tenant_id = ?`). A run is read by the pair
`(tenantId, runId)`; another tenant's run is indistinguishable from a missing one.

**Run-level lease snapshot** — confirm ownership and how stale it is:

```sql
SELECT id, status, attempt, version,
       lease_owner, lease_epoch, lease_until,
       next_action_at,
       lease_until < now() AS lease_expired
FROM   workflow_runs
WHERE  tenant_id = ? AND id = ?;
```

**Has recovery re-claimed this run?** Compare `lease_owner`/`lease_epoch` with the
attempt history (§ below). A bumped `lease_owner`/`lease_epoch` means a worker took
over after a crash (`lease_epoch` fencing, invariant 5).

**Step history** — which step, how many attempts, status, where the attempt began.
Tenant isolation flows through the parent run (`workflow_step_runs` has no
`tenant_id`, so join to `workflow_runs`):

```sql
SELECT s.step_key, s.status, s.attempt, s.attempt_started_at
FROM   workflow_step_runs s
JOIN   workflow_runs r ON r.id = s.run_id
WHERE  r.tenant_id = ? AND s.run_id = ?;
```

**Per-attempt execution history** — start/finish, worker instance, safe error
category (redacted, no secret payload). Duration is `finished_at - started_at`,
computed by the reader, so it is not a column:

```sql
SELECT attempt,
       worker_instance,
       outcome,
       started_at,
       finished_at,
       error_class,       -- taxonomy token (e.g. provider_timeout, rate_limited)
       error_summary      -- redacted, secret-free
FROM   workflow_step_attempt_history h
JOIN   workflow_runs r ON r.id = h.run_id
WHERE  r.tenant_id = ? AND h.run_id = ?
ORDER BY attempt;
```

**Event log** — the ordered history of run/step events (created by `S05-09`):

```sql
SELECT event_type, occurred_at
FROM   workflow_events
WHERE  tenant_id = ? AND run_id = ?
ORDER BY occurred_at;
```

---

## 5. Diagnose: run stuck in `RUNNING`

A run in `RUNNING` past the lease lifetime (~30 s) with no recovery usually means one
of:

- **Worker was mid-step when it died.** The step attempt did not finish, the lease
  expired, and recovery has not yet re-claimed it. Confirm with §4: if
  `lease_expired = true` and no fresh `lease_owner`/`lease_epoch`, recovery is
  pending — it arrives on the next scheduler pass (~35 s default; ≤60 s in the test config). Watch
  `agentflow.workflow.recoveries` rise.
- **Recovery scheduler is down.** If `agentflow.workflow.recoveries` is flat and you
  repeatedly see `recovery pass failed; it will be retried on the next cadence`, the
  scheduler is unhealthy. Restart the worker/scheduler process; it re-claims on the
  next pass.
- **A step is genuinely hanging** (slow external tool, provider stall). If
  `attempt_started_at` is far older than `lease_until` and the worker is alive, the
  step call has no timeout configured or exceeded it. Confirm the step is real
  (`workflow_step_runs`) and the worker process is up (see §2 logs).

**Action:**

1. Watch for ~35 s — most recover automatically: the run stays `RUNNING` (re-claimed
   by a new worker) and resumes to `SUCCEEDED`.
2. If still stuck after recovery should have fired and the worker is up → check the
   step's external dependency (provider/tool latency), not the run itself.
3. If the run has no worker alive and the lease is stale and it will not recover →
   force re-claim (§8). **Do not** edit the run row directly.

---

## 6. Diagnose: run parked in `WAITING_FOR_APPROVAL` or `RETRY_WAIT`

This is **not a crash** — the run intentionally released its lease and is waiting.

- **`WAITING_FOR_APPROVAL`** — a `HUMAN_APPROVAL` step is waiting for a decision from
  an external approval system (identity provider / approval UI). The run lease is
  released (`lease_owner` cleared, heartbeat stopped). It resumes **only** when an
  approval event arrives; the run re-enters `RUNNING` and resumes. Until then it will
  look "stuck" — that is the expected state. **Fix the approval, not the run.**
- **`RETRY_WAIT`** — a step failed with a retryable error and the run is in backoff
  before a re-claim (the error taxonomy decides retryable vs permanent). A free worker
  re-claims the run (retry policy) automatically; once backoff elapses it resumes to
  `RUNNING`. Transient; no action.

**Check:** `lease_expired = true` and `status = WAITING_FOR_APPROVAL`/`RETRY_WAIT` is
fine. If the approval was already given but the run never resumed, the approval
event may have been dropped — check the inbox/outbox (the events catalog,
`../architecture/events/README.md`), then §9.

---

## 7. Diagnose: run in `FAILED` (retry policy exhausted)

A run reaches `FAILED` when every step attempt failed and the retry policy ran out
of attempts. **Retry decision comes from the `error_class`** (`error-taxonomy.md`):
only transient faults (`rate_limited`, `provider_timeout`, connection errors) retry;
others terminate immediately.

1. Look at `workflow_step_attempt_history` (§4). Read `outcome`, `error_class`,
   `finished_at`, `started_at` for the last attempt. `error_summary` is redacted — the secret
   payload is never in the history. `finished_at` − `started_at` is the attempt duration.
2. Classify:
   - **Transient** (`rate_limited`, `provider_timeout`, connection): the run already
     exhausted its retries. A new run or a manual retry (below) will likely succeed
     if the underlying fault cleared.
   - **Terminal** (bad input, validation, non-5xx provider error): retrying will not
     help. Fix the input/step, then resubmit.
3. **Ambiguous external outcome** (`provider_timeout` where the side effect *may*
   have completed): per `ADR-003` / invariant 6, this is **not** exactly-once. The
   attempt is recorded with its safe category; its retry follows the tool's
   idempotency (the tool registry's risk class + retry metadata). Idempotent tools may be
   re-run; non-idempotent ones are resolved, never blindly retried.

**Action:** retry manually only for transient faults and only if idempotency allows
(§8). For terminal failures, correct the input and resubmit.

---

## 8. Acting: cancel, retry, or force a lease

### Cancel a run (operator request)

```
POST /api/v1/runs/{runId}/cancel
```

Returns **`202 CANCEL_REQUESTED`**. Cancellation is cooperative: the run moves to
`CANCEL_REQUESTED` (a visible "cancellation in flight") and then drains to `CANCELLED`
as a running worker checks the signal and exits at the next safe point
(`run-state-machine.md`). No direct row edit.

### Retry a failed run (transient faults only)

- **Preferred:** submit a **new run** (idempotent create under `Idempotency-Key`,
  `S06-06`) with the corrected input.
- **In place:** only if the failed step was idempotent (see the tool's idempotency
  contract — the registry risk class + retry metadata) and the fault looks cleared. Do not
  blind-retry a non-idempotent step.

### Force a lease re-claim (last resort — scheduler down)

> Do this **only** when the run is not terminal (`SUCCEEDED`/`FAILED`/`CANCELLED`)
> and the recovery scheduler is confirmed down. Prefer restarting the worker; a
> live scheduler will re-claim on its next pass. This also makes the scheduler pick
> the run up again automatically.

```sql
UPDATE workflow_runs
SET    lease_owner  = 'operator-<initials>-manual-reclaim',
       lease_epoch  = lease_epoch + 1,   -- fence any dead worker (invariant 5)
       lease_until  = now() + interval '60 seconds'
WHERE  tenant_id = ?
  AND  id = ?
  AND  status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED');
```

Rows affected `= 1` means the lease was taken over; a worker re-claims the run
(`RUNNING`) and resumes it. Rows affected `= 0` means the run is terminal, already
owned by a live worker, or a concurrent claim won — stop.

### Force a terminal state (absolute last resort)

If a run is permanently wedged (no worker, scheduler down, lease already stale) and
you have confirmed no worker will recover it, an operator may mark it terminal so the
system stops tracking it. Prefer the cancel API (§8) first. Only as a last resort:

```sql
UPDATE workflow_runs
SET    status = 'FAILED',
       version = version + 1
WHERE  tenant_id = ?
  AND  id = ?
  AND  status IN ('RUNNING', 'RETRY_WAIT', 'WAITING_FOR_APPROVAL', 'CANCEL_REQUESTED');
```

Manual row edits are a **runbook action, not a code path** — record why in the
incident, and confirm the change with the `workflow_events` log. Never edit the row
to bypass the state machine in normal operation.

---

## 9. Acting: run did not resume after an approval

If an approval was granted but the run stays `WAITING_FOR_APPROVAL`:

1. Confirm the approval actually reached the system — check the inbox/outbox and the
   event log (`workflow_events`, §4; the events catalog).
2. If the approval event exists but the run did not transition, the resume did not
   fire. Restart the worker so it re-polls; the run re-enters `RUNNING` and resumes
   retry policy (`step-state-machine.md`).
3. If the approval event is missing, coordinate with the approval system — the event
   was never delivered (at-least-once, not exactly-once; a duplicate is safe, a gap
   is not — the events catalog).

---

## 10. When to escalate

| Signal | Likely cause | Action |
|---|---|---|
| `agentflow.workflow.recoveries` flat, no recovery after ~35 s on a `RUNNING` run with a stale lease | Recovery scheduler down | Restart the worker/scheduler process; re-claim (§8) |
| `recovery pass failed; ... retried on the next cadence` repeats | Scheduler loop error | Restart; open an incident if it persists |
| Heartbeats rejected for a long-lived active run (not during a deploy) | Lease lost unexpectedly / worker stuck | Check worker process + the step's external dependency |
| Run stuck `WAITING_FOR_APPROVAL` with no approval decision | Waiting on external approver | Fix the approval; not an engine fault |
| Repeated `FAILED` across many runs on the same step | Systemic transient fault (provider/dependency) | Triage the dependency, not individual runs |

---

## 11. Reference: identifiers used in this runbook

| Concept | Identifier / where it lives |
|---|---|
| Run statuses | `QUEUED, RUNNING, WAITING_FOR_APPROVAL, RETRY_WAIT, SUCCEEDED, FAILED, CANCEL_REQUESTED, CANCELLED` — `run-state-machine.md` |
| Step statuses | `PENDING, READY, RUNNING, WAITING_FOR_APPROVAL, RETRY_WAIT, SUCCEEDED, FAILED, SKIPPED, CANCELLED` — `step-state-machine.md` |
| Lease fields | `workflow_runs.lease_owner, lease_epoch, lease_until, version, next_action_at` — `ADR-011`, `durable-execution.md` |
| Run lease lifetime | `agentflow.worker.lease.duration-seconds` (default 30) |
| Retry policy | `agentflow.worker.retry.*` (max-attempts 3, base `base-delay-seconds`, `multiplier`, `max-delay-seconds`) |
| Recovery cadence | `agentflow.worker.recovery-fixed-rate-ms` (5 s) |
| Recoveries metric | `agentflow.workflow.recoveries` (Counter, no run/tenant label) |
| Run snapshot API | `GET /api/v1/runs/{runId}` → `200 RunDto {id, workflowVersionId, status, attempt, createdAt}` |
| Step attempts API | `GET /api/v1/runs/{runId}/attempts` → `200 AttemptRecord[] {attempt, stepKey, workerInstance, outcome, startedAt, finishedAt, durationSeconds, errorClass, errorSummary}` |
| Cancel API | `POST /api/v1/runs/{runId}/cancel` → `202 CANCEL_REQUESTED` |
| Retry decision | `error_class` token (transient → retry, else terminate) — `error-taxonomy.md` |
| Exactly-once policy | `ADR-003`, invariant 6 — tool idempotency (registry risk class + retry metadata) |
