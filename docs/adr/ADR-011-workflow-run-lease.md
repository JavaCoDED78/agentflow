# ADR-011. Workflow run lease, heartbeat and recovery (fencing)

- **Status:** Accepted
- **Date:** 2026-11-02
- **Task:** S07-01 (Sprint 7 — durable execution: lease, fencing, checkpoints, retry, cancel, recovery)
- **Supersedes:** none. Records the lease model that `S07-02`…`S07-09` implement and that the domain
  glossary §5 already canonicalizes (`lease_owner`, `lease_until`, `lease_epoch`, heartbeat, recovery).
- **Superseded by:** none

## Context

AgentFlow runs workflows on **ephemeral, restartable workers** (`workflow-worker` role, one process
per run/step, no shared in-memory state). A worker can be killed by an OOM kill, a `SIGTERM` on
graceful shutdown, a node crash or a network partition — **at any point mid-step**. The durable
execution state (which step is `RUNNING`, what it produced, what has been retried) must survive
those crashes: losing it would violate *PostgreSQL is the source of truth* (`ADR-002`).

The state machine moves a run/step only through the single transition service
(`run-state-machine.md` `S05-01`, `step-state-machine.md` `S05-02`), and every write is a
conditional update guarded by the tenant predicate (invariant 1) and, on a step, the current
`lease_epoch` (invariant 5 / fencing). But a conditional update alone does not answer the question
that crash recovery forces: **when two (or more) workers can act on the same run, who is allowed to
advance it right now, and how does recovery recognise that the current owner has stopped alive?**

Three hard requirements follow:

1. **Exactly one live owner at a time.** If two workers held the run concurrently, each could apply
   a "successful" transition and the append-only history (`workflow_events`) would record two
   divergent outcomes for one run — a silent lost update. Only one may persist progress, and a
   second must be fenced (invariant 5).
2. **A dead owner is reclaimed, not waited on forever.** If a worker crashes, its run would stay
   `RUNNING` indefinitely without a mechanism to notice it stopped heartbeating; recovery must
   discover and re-claim it (invariant 2 — the run is never lost, only paused until re-claim).
3. **Reclamation is unforgeable.** A stale worker must not be able to keep writing after its lease
   is taken, because a partial/ambiguous step result (an external call whose outcome is unknown)
   could otherwise overwrite the new owner's result. This is *at-least-once*, never *exactly-once*
   (`ADR-003`, invariant 6): a repeated step after a crash is possible and must be made visible, not
   prevented by pretending a single owner was guaranteed.

The domain glossary §5 already names this concept — "Lease": the worker claims a run and receives
`lease_until` and an incremented `lease_epoch`; only the current owner may extend it; every worker
write re-checks `lease_epoch`. `V3_3_6__create_workflow_runs_and_steps.sql` (`S05-08`) already
declares `lease_owner` (nullable), `lease_until` (`timestamptz`) and `lease_epoch`
(`INTEGER NOT NULL DEFAULT 0`) on `workflow_runs`, and `workflowexecution.persistence` already
re-checks `lease_epoch` on every step write (`WorkflowStepRunRepository`, mapping a moved fence to a
`409 conflict` per `ADR-009`). What is **not** yet implemented is the lease *model*: the claim
semantics, the heartbeat that extends the lease, the recovery scan that detects a stale owner, and
the index that makes that scan cheap. Those are `S07-03`, `S07-04`, `S07-09` and `S07-02`. This
ADR fixes the model; the columns are already present, the services are the follow-ups.

## Decision

**A single run has one live lease, held by the worker instance that atomically claims it, expressed
in three columns on `workflow_runs` and fenced by a monotonic epoch.** The model is:

| Field | Type | Meaning | Owner |
|---|---|---|---|
| `lease_owner` | `UUID` (nullable) | The worker **instance** that holds the lease. Set on claim, cleared when the run reaches a terminal state or the lease is released (e.g. at a `HUMAN_APPROVAL` pause, where the run-level lease is released per `step-state-machine.md` §10). | `RunLeaseService` (S07-03) |
| `lease_until` | `TIMESTAMPTZ` (nullable) | The instant the lease **expires** without a heartbeat. Computed as `clock.instant().plusSeconds(duration)` on claim and extended by each heartbeat. Never read from the system clock in domain/application code — always through the injected `Clock` (engineering-conventions.md). | `RunLeaseService` / heartbeat (S07-03, S07-04) |
| `lease_epoch` | `INTEGER` | A **monotonic fencing counter**, already present in `V3_3_6`. Bumped on every re-claim (new owner); every worker write re-checks the expected epoch and a mismatch is a `409 conflict`. This is what makes reclamation unforgeable. | every write; `RunLeaseService` on claim |

**Who may persist progress.** A worker writes a step or run transition only when the row still holds
`lease_owner = <this worker>` **and** `lease_epoch = <expected>`. Both are part of the conditional
`UPDATE ... WHERE` (invariant 5, already on steps; extended to the run write in `S07-05`), so a
stale worker's write matches zero rows and becomes a `409 conflict` instead of overwriting the new
owner. This is the acceptance criterion of `S07-01`: *only the current lease owner persists
progress; a worker that lost the lease cannot save its result.*

**How a stale owner is detected.** `lease_until` is a wall-clock bound. A live owner heartbeats at
an interval shorter than the lease duration, so `lease_until` is always pushed into the future while
it is alive. When a worker stops (crash / `SIGTERM`), no heartbeat extends `lease_until`; once
`lease_until < now()` **and** `lease_owner` still names the dead worker, the lease is expired and
the run is recoverable. A heartbeat that arrives with a different `lease_owner` or a different
`lease_epoch` (the owner was re-claimed) is **rejected** — an old owner cannot extend its lease
after it is taken (`S07-04`). Staleness is therefore a pure comparison of `lease_until` against the
injected clock; the recovery scheduler owns the scan.

**Duration / heartbeat / recovery intervals.** These are **configuration, not magic numbers**, bound
through `AgentFlowProperties.Worker.Lease` (`@ConfigurationProperties` with validation), with the
defaults below chosen so the relationships hold and so the recovery-time acceptance of `S07-15`
(resume no later than 60 s after expiry in test config) is met:

| Parameter | Default | Rule |
|---|---|---|
| `duration-seconds` | `30` | Lease lifetime without a heartbeat. Well above a normal step, below the crash-detection horizon. |
| `heartbeat-interval-seconds` | `10` | ≈ `duration / 3`. A live worker extends the lease three times within one TTL, so a single lost heartbeat (or a GC pause) never expires a healthy lease. `interval ≤ duration / 3`. |
| `recovery-interval-seconds` | `5` | How often the recovery scheduler scans for expired leases. Bounded so `duration + recovery-interval ≤ 60` in the test config (the `S07-15` criterion); the worst-case resume time is bounded by the same sum. |

The recovery scan selects `workflow_runs` rows whose `lease_until < now()` and `lease_owner` is
non-null (plus terminal-state rows are skipped, and concurrency limits are respected, `S07-09`) and
re-claims them atomically. The index on `lease_until` (`S07-02`) backs that scan; without it the
recovery loop would table-scan every run, which the operational consequences below forbid.

**Fencing, not JVM state.** The fence lives in PostgreSQL (`lease_epoch`), not in in-process state
(invariant 5). Two workers on one run are fenced at the row, so a `synchronized` block or an
in-memory `AtomicInteger` on a worker provides **no** protection — a restart loses it. This is the
same primitive the step transition already uses (`WorkflowStepRunRepository`), now made the
explicit run-level lease.

**No exactly-once across a crash.** Because an external call (LLM, tool, HTTP) is not exactly-once
(`ADR-003`, invariant 6), a worker may crash after persisting `RUNNING` + attempt number but before
recording the outcome (`S07-06`). On re-claim the step may run again; the attempt history records the
re-run and marks an ambiguous external outcome, but the lease model never *promises* a single
execution. The lease makes ownership explicit and recovery possible — it does not make a repeated
side effect impossible.

## Alternatives considered

1. **DB-backed lease on `workflow_runs` (chosen).** Lease state lives in the PostgreSQL source of
   truth alongside the run. Pro: losing any other store cannot lose the lease or a run (invariant 2),
   the fence is durable and survives restarts, and it is the same primitive the step write already
   uses. Con: a claim is a row write, not a memory operation — acceptable, since claims are
   infrequent (per run, not per request).
2. **Redis as the lease authority.** Pro: sub-millisecond lease check. Con: Redis is, by the stack
   contract, a cache / short-lived coordination layer (AGENTS.md), and "losing Redis must not lose a
   run" (invariant 2) is incompatible with Redis owning who may advance a run — a Redis flush would
   make every run leaseless and two workers would race. Rejected as the authority. Redis *may* back
   ephemeral coordination later (e.g. a fast in-process hint) but never the authoritative lease.
3. **Lease without a monotonic epoch.** Pro: simpler (only `lease_until`). Con: a re-claim only
   moves `lease_until` into the future, so a crashed owner whose write is still in flight can
   overwrite the new owner — the fence is not unforgeable. The epoch is what turns "my lease is
   still valid" into "my lease is valid *and* nothing newer happened". Chosen with epoch.
4. **TTL-only lease vs heartbeat + TTL.** Pro (TTL-only): simplest. Con: a short TTL that tolerates
   nothing expires a healthy worker on a transient pause; a long TTL makes stale detection slow.
   Heartbeat + TTL keeps a live lease far from expiry while still bounding how long a dead owner
   lingers. Chosen both.
5. **Central lease coordinator vs periodic recovery scheduler.** Pro (coordinator): a single authority
   always knows the current owner. Con: it is a single point of failure and a serialization bottleneck,
   and it must itself be made durable and fault-tolerant. Chosen scheduler: stateless, scales per
   tenant/lease-range, and re-derives the current owner from PostgreSQL (the source of truth) on every
   scan rather than trusting a remembered one.

## Consequences

- **Positive:**
  - Exactly one live owner advances a run; a second worker's write is fenced to a `409 conflict`
    (invariant 5) — the two-worker race can never produce two divergent outcomes for one run.
  - A crashed or drained worker's run is discovered and re-claimed automatically (`S07-09`); a run is
    **paused**, never lost, across a worker outage (invariant 2).
  - Reclamation is unforgeable: the `lease_epoch` bump on every re-claim means the old owner's
    in-flight writes fail the conditional update, so a partial/ambiguous step result cannot overwrite
    the new owner's result.
  - The model is cheap to reason about: three columns, one monotonic counter, one scan. It reuses the
    exact fencing primitive already on the step write, so the run and step paths stay identical.
  - Consistent with `ADR-002` (source of truth), `ADR-003` (at-least-once, never exactly-once),
    `ADR-007` (tenant scope flows through the run) and `ADR-009` (the repository maps a moved lease to
    a `409`).
- **Negative / operational:**
  - The lease columns are on `workflow_runs`, so the lease is a **run-level** concept. Steps are
    fenced only by the shared `lease_epoch` on `workflow_step_runs` (already the case, `S05-08`);
    the run-level lease governs *which worker may run the next step*, and the step `lease_epoch`
    fences *the write itself*. These are complementary, not duplicated: the run lease selects the
    owner, the epoch fences the write.
  - The recovery scan reads and re-writes `lease_until`/`lease_owner`/`lease_epoch` for every expired
    run, so it must be **bounded** (batched, concurrency-limited, `next_action_at` for retry backoff)
    and idempotent — a duplicate re-claim is a no-op, not a lost run (`ADR-003`).
  - A lease that is too tight fires false reclaims under a slow step or a long GC pause; too loose and
    a dead owner lingers. The defaults (`30 s` / `10 s` / `5 s`) are starting points; they are
    configuration and must be retuned against the measured step-latency distribution (`S16`), not
    pinned from instinct.
  - Recovery latency is bounded by `duration-seconds + recovery-interval-seconds`; the test config
    lowers both so `S07-15`'s "resume ≤ 60 s after expiry" holds. Production may widen the TTL.
- **Follow-ups:**
  - `S07-02` adds the expired-lease **index** (`lease_until`) that backs the recovery scan — without it
    the scan table-scans every run.
  - `S07-03` (`RunLeaseService`) implements the atomic claim (set `lease_owner`/`lease_until`, bump
    `lease_epoch`); `S07-04` the heartbeat (extend only on matching owner + epoch); `S07-05` lifts the
    `lease_epoch` fence to the run write; `S07-09` the periodic recovery scan.
  - `S07-06`/`S07-14` add attempt persistence + history so the crash window (persisted `RUNNING`,
    unknown outcome) is diagnosable; `S07-15`/`S07-16` prove crash/restart and ambiguous-outcome
    behaviour; `S07-17` consolidates all of this into `docs/architecture/durable-execution.md` plus a
    recovery runbook.

## Lease model — who persists and how a stale owner is detected

| Situation | Lease state | Result |
|---|---|---|
| Worker claims a `READY` run | `lease_owner = W`, `lease_until = now + duration`, `lease_epoch` bumped | Only `W` may run the next step |
| `W` heartbeats (alive) | `lease_until` pushed forward; `lease_owner`, `lease_epoch` unchanged | Lease renewed; still one owner |
| `W` heartbeats after re-claim by `W2` | `lease_owner` ≠ `W` or `lease_epoch` moved | Heartbeat **rejected** (`S07-04`) |
| `W` crashes; no heartbeat | `lease_until < now`, `lease_owner = W` | Lease expired → recoverable |
| Recovery re-claims to `W2` | `lease_owner = W2`, `lease_until` reset, `lease_epoch` bumped | `W` is now stale |
| Stale `W` writes a step | `lease_epoch` in the `WHERE` ≠ bumped epoch | Zero rows → `409 conflict` (`ADR-009`) |
| `W` writes while still the owner | `lease_epoch` matches | Write proceeds |

## Related

- **Domain glossary §5** — canonical lease vocabulary (`lease_owner`/`lease_until`/`lease_epoch`,
  heartbeat, recovery job).
- [`ADR-002`](ADR-002-postgresql-source-of-truth.md) (PostgreSQL is the source of truth),
  [`ADR-003`](ADR-003-at-least-once-idempotency.md) (at-least-once + idempotency, never exactly-once),
  [`ADR-007`](ADR-007-tenant-isolation.md) (tenant scope flows through the run) and
  [`ADR-009`](ADR-009-single-persistence-spring-data-jdbc.md) (repository maps a moved lease to a
  `409 conflict`) — the invariants the lease model serves.
- [`run-state-machine.md`](../architecture/run-state-machine.md) (`S05-01`) and
  [`step-state-machine.md`](../architecture/step-state-machine.md) (`S05-02`) — the machine the lease
  guards; the run-level lease is released at a `HUMAN_APPROVAL` pause.
- [`engineering-conventions.md`](../architecture/engineering-conventions.md) — the injected `Clock`
  and fixed-clock tests that make `lease_until`, heartbeat and recovery deterministic in tests.
- Migration [`V3_3_6__create_workflow_runs_and_steps.sql`](../../apps/agentflow-app/src/main/resources/migrations/V3_3_6__create_workflow_runs_and_steps.sql) (`S05-08`) — the table carrying the three columns.
- **Follow-ups:** `S07-02` (expired-lease index), `S07-03`…`S07-05`, `S07-09` (claim / heartbeat /
  recovery), `S07-17` (`durable-execution.md` + recovery runbook). The planned `durable-execution.md`
  (`⚠ planned`, `S07-17`) holds the comprehensive engineering guide; this ADR is the lease *decision*.
