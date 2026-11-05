# Changelog



# Sprint 7 — Durable execution



## S07-14 — Persistent per-attempt step execution history (S07-14).

- **Persistent per-attempt step execution history (S07-14).** A step's history is now durable across
  every attempt it makes, so a failed retry is investigable without re-running it and without viewing
  the secret payload. The new `workflow_step_attempt_history` table (`V3_3_16`, `workflowexecution`)
  records one row per `(run_id, step_key, attempt)`: the `attempt` number (1-based, `MAX(attempt)+1`
  per `(run_id, step_key)` so a retry gets a fresh, monotonic number), the `worker_instance` that ran
  it, the `outcome` it reached (`SUCCEEDED`/`FAILED`/`RETRY_WAIT`/`WAITING_FOR_APPROVAL`/`CANCELLED`;
  `NULL` while still running), the `started_at` (the step entering `RUNNING`, before the side effect)
  and `finished_at` (`NULL` while in flight), and the safe fault metadata — the `error_class` ErrorClass
  token and the short redacted `error_summary` (S07-11) — never the raw error and never the secret
  payload (invariant 8). The writes join the single `@Transactional` step-writer, `RunStateMachineService`:
  a step begins an attempt when it enters `RUNNING` (`beginAttempt`) and concludes when it leaves it
  (`concludeAttempt`), both in the same transaction as the state change (invariant 3); the highest
  attempt is fenced by `COALESCE(MAX(attempt))` on conclude so a duplicate conclude can never double-write.
  The table stores no `tenant_id`: tenant isolation flows through the parent run — every read and write
  `JOIN`s `workflow_runs` and filters by the parent's `tenant_id` (invariant 1), so a cross-tenant run is
  indistinguishable from a missing one. The API exposes the history through `GET /api/v1/runs/{runId}/attempts`
  (in `WorkflowRunController`), served by `RunService#getAttemptHistory`, which first loads the run through
  the tenant-scoped `WorkflowRunRepository#findRun` (a cross-tenant/missing run is a `404`, never an empty
  list — no leak), then maps the rows to `AttemptRecord`, deriving `durationSeconds` from `started_at`/
  `finished_at` (invariant 10 — derived, never stored; `0` while in flight) and surfacing only the safe
  error category. The bean is `@ConditionalOnBean(DataSource.class)`, so the DB-less skeleton (S01-02) still
  boots: `beginAttempt`/`concludeAttempt` are a no-op when the repository is absent, and `getAttemptHistory`
  reports `503 unavailable`.

#### What changed
- Migration `V3_3_16__create_step_attempt_history.sql` (`owner: workflowexecution`) adds the
  `workflow_step_attempt_history` table (`id PK`, `run_id FK → workflow_runs`, `step_key`, `attempt`,
  `worker_instance`, `outcome`, `started_at`, `finished_at`, `error_class`, `error_summary`, `created_at`)
  with a composite unique index `(run_id, step_key, attempt)`; every column carries a S07-14 /
  `workflowexecution` comment.
- `workflowexecution.persistence.WorkflowStepAttemptHistoryRepository` (new, `@ConditionalOnBean(DataSource.class)`)
  — `beginAttempt` (the next `attempt` number + a NULL-outcome row), `concludeAttempt` (tenant-scoped,
  fenced update of the highest attempt with `COALESCE` guards so a prior class survives a later clean
  outcome), and `findHistory` (tenant-scoped `JOIN`, ordered by step then attempt).
- `workflowexecution.persistence.AttemptHistoryRow` — the row-mapped projection.
- `workflowexecution.application.AttemptRecord` — the API record; `fromRow` derives `durationSeconds`
  from the start/finish instants (`0` while in flight) and carries only the safe error category.
- `workflowexecution.application.RunStateMachineService` — `recordStepAttempt(...)` (null-guarded) begins
  an attempt on entry to `RUNNING` and concludes it on leaving it, in the same `@Transactional` transition;
  both the 6-arg and the 8-arg (`errorClass`) step transitions call it.
- `workflowexecution.application.RunService` — `getAttemptHistory(tenantId, runId)`: a tenant-scoped 404
  pre-check on the run, then the tenant-scoped history mapped to `AttemptRecord` (503 when no database).
- `workflowexecution.web.WorkflowRunController` — `GET /api/v1/runs/{runId}/attempts` returning
  `ResponseEntity<List<AttemptRecord>>`.

#### Why
A worker that failed a step and retried left no durable, queryable trace of *what each attempt did* —
only the current attempt's row on `workflow_step_runs`. To investigate why a retry failed an operator
either re-ran the step (re-executing an untrusted side effect) or read the raw error (which can carry
the secret payload — invariant 8). `S07-14` records the attempt lifecycle durably and safely: the attempt
identity, its start/finish, the worker that ran it, and the safe error category (a redacted `ErrorClass`
token + short reason), so a failed retry is investigable in the history API without re-running or viewing
the secret. Recording begin/conclude through the single transition service keeps it in the same transaction
as the state change (invariant 3) and fenced by the same tenant-scoped read as the rest of the run
(invariant 1), and the derived `durationSeconds` (invariant 10) is observable without a new stored column.

#### Verification
- `:apps:agentflow-app:compileTestJava` — green (main + test).
- `:apps:agentflow-app:test --tests "com.example.agentflow.workflowexecution.application.AttemptRecordTest"` —
  3/3: `fromRow_maps_the_identity_and_the_safe_error_category`,
  `fromRow_derives_duration_from_start_and_finish_in_whole_seconds`,
  `fromRow_reports_zero_duration_while_the_attempt_is_in_flight`.
- `:apps:agentflow-app:test --tests "com.example.agentflow.workflowexecution.web.WorkflowRunControllerTest"` —
  green: `get_attempt_history_returns_the_records_for_the_run` (200 with the safe records, a clean attempt's
  `errorClass` absent from the JSON) and `get_attempt_history_on_an_unknown_run_is_a_404` (a missing/foreign
  run surfaces as `404 not found`, never a leak).

#### Tests
- `AttemptRecordTest` (new, unit, DB-free): the three methods above.
- `WorkflowRunControllerTest` — the two methods above (`200` happy path + the tenant-scoped `404`).

#### Docs
`CHANGELOG.md` `[Unreleased] → Added`; this devlog section; the endpoint added to the OpenAPI contract
(`generateOpenApiContract`).

#### Schema / contract
Additive only: one new table (`V3_3_16`) and one new read-only endpoint (`GET /api/v1/runs/{runId}/attempts`);
no change to the `WorkflowStepState` enum, event shapes, the module boundary, or any existing call path — the
transition service gains the null-guarded `recordStepAttempt` hook, and no existing transition changed behaviour.
## S07-13 — Graceful shutdown of the workflow worker (S07-13).

- **Graceful shutdown of the workflow worker (S07-13).** The worker event loop is now driven by a Spring `Lifecycle` bean, `WorkerLifecycle` (`workflowexecution.worker`), and termination on SIGTERM no longer leaves a run `RUNNING` without recovery. `WorkerLifecycle.start()` spins a loop thread that drives `WorkflowWorker#pollOnce` per configured tenant every `agentflow.worker.poll-interval-ms`; `WorkerLifecycle.stop()` sets the worker's cooperative `shuttingDown` flag (so `pollOnce` stops claiming new runs — `pollOnce` returns `0` once the flag is set, and the per-run loop `break`s before starting a run it has not begun), then joins the loop thread for up to `agentflow.worker.shutdown-timeout-seconds` (a bounded drain of the step currently executing), and if the in-flight step is still running it **abandons the loop thread and logs a warning** — the run is never lost: its run-level lease expires and the S07-09 stale-run recovery loop re-claims it. `WorkerLifecycle` is `@ConditionalOnProperty(name = "agentflow.worker.loop-enabled", matchIfMissing = true)`, so it is active where the loop matters (the `workflow-worker` role) and harmless in the `api` role, mirroring the recovery scheduler. New config (in `agentflow.worker`): `poll-interval-ms` (default `5000`, `@Min 100`) is the poll cadence, `shutdown-timeout-seconds` bounds the drain, `loop-enabled` toggles the driver, and `tenants` is the operator-set startup set (an operator hint, never a trust anchor — invariant 1).

#### What changed
- `workflowexecution.application.WorkflowWorker` — `volatile boolean shuttingDown`; `isShuttingDown()`; `stop()` sets the flag; `pollOnce` returns `0` (claims nothing new) once shutting down; the per-run loop `break`s before starting a run it has not begun.
- `workflowexecution.worker.WorkerLifecycle` (new) — one-thread `Lifecycle` driver of `WorkflowWorker#pollOnce` per tenant plus the bounded-drain graceful shutdown; role-gated via `@ConditionalOnProperty(... loop-enabled, matchIfMissing = true)`. `stop()` makes the S07-13 heartbeat contract explicit and observable: once the shutdown flag is set the worker stops extending the run lease (the heartbeat) — `pollOnce` claims nothing new — and the drain path logs whether the loop drained cleanly or was abandoned, both stating that the in-flight run's lease is no longer extended (heartbeat stopped) and is left to expire for the S07-09 recovery re-claim.
- `config.AgentFlowProperties.Worker` — new validated fields `pollIntervalMs` (`@Min 100`, default `5000`), `shutdownTimeoutSeconds`, `loopEnabled` (default `true`), and `tenants`.

#### Why
The worker loop (S07-08) ran each claimed run to completion, but nothing in production started it and — more importantly for termination — there was no lifecycle hook: a worker stopping mid-run would leave the run `RUNNING` until its lease expired and recovery re-claimed it, with no bounded way to drain an in-flight step and no observability into the shutdown. `WorkerLifecycle` makes the loop first-class (it drives `pollOnce` on a cadence) and gives SIGTERM a defined, bounded drain that leaves runs recoverable rather than stuck or lost (invariants 1, 3). The abandoned-but-running thread is intentional and documented: we never promise exactly-once (invariant 6), so an in-flight step that outlasts the drain is recorded as incomplete in usage/history and re-driven on recovery — it is never silently dropped.

#### Verification
- `:apps:agentflow-app:compileTestJava` — green.
- `:apps:agentflow-app:test --tests "com.example.agentflow.workflowexecution.application.WorkflowWorkerShutdownTest" --tests "com.example.agentflow.workflowexecution.worker.WorkerLifecycleTest"` — **BUILD SUCCESSFUL, 7/6** (`WorkflowWorkerShutdownTest` 4/4: `pollOnce_returnsZeroAndSkipsClaimingOnceStopping`, `stop_setsTheShuttingDownFlag`, `pollOnce_processesRunsNormallyBeforeStopping`, `shutdown_extendsNothingOnTheLeaseAfterStopping`; `WorkerLifecycleTest` 3/3: `startThenStop_setsShuttingDownAndDrainsTheLoop`, `stopWithoutStart_isAnNoOp`, `stopDrainsWithinTheBoundedTimeEvenWhenTheStepBlocks`).

#### Tests
- `WorkflowWorkerShutdownTest` (new, unit, no DB — mocks for repos/transitions/lease/checkpoint): `pollOnce_returnsZeroAndSkipsClaimingOnceStopping`, `stop_setsTheShuttingDownFlag`, `pollOnce_processesRunsNormallyBeforeStopping`, `shutdown_extendsNothingOnTheLeaseAfterStopping` (on shutdown the worker never re-claims or extends the run lease, so the in-flight run is left owned for recovery).
- `WorkerLifecycleTest` (new, unit, no DB — real `WorkerLifecycle` + real `WorkflowWorker` over mocks): `startThenStop_setsShuttingDownAndDrainsTheLoop`, `stopWithoutStart_isAnNoOp`, `stopDrainsWithinTheBoundedTimeEvenWhenTheStepBlocks` (a step handler that sleeps past the drain ceiling proves the drain is bounded, not the step).

#### Docs
`CHANGELOG.md` `[Unreleased] → Added`; this devlog section.

#### Schema / contract
Additive only: no new migration or column, no API change; the transition service keeps its existing lease-fenced `transition`, `pollOnce` gains a shutdown short-circuit, and the only new contract surface is the `AgentFlowProperties.Worker.*` config fields.
## S07-12 — Cancel a run (S07-12).



- **Cooperative cancellation of a run (S07-12).** A user can now ask to cancel a run that is already running: `POST /api/v1/runs/{runId}/cancel` (in `WorkflowRunController`, returns `202`) hands the request to `RunService#requestCancellation(tenantId, runId, source)`, which moves the run `QUEUED → CANCEL_REQUESTED` through the run transition service. Cancel is **idempotent and one-way** (`RequestConflictException` if asked twice; a `no-op` when the run is already terminal or `CANCEL_REQUESTED`), so a duplicate request never fails loudly and never resurrects a run. The run stays `CANCEL_REQUESTED` until a worker observes it; the worker (`WorkflowWorker`) drains cooperatively rather than tearing anything down mid-flight. A step that is already `RUNNING` (holding its lease, executing its `StepHandler`) is **left to finish or fail on its own and its result is kept if it succeeded** — cancellation coordinates a stop at the next safe boundary, not a signal to abort a running side effect. The worker only moves the not-yet-started steps (`PENDING`/`READY`, and — via the new `RETRY_WAIT → CANCELLED` edge in `WorkflowStepState`, so a run parked in retry-backoff can still be cancelled) to `CANCELLED`; **every already-completed step's result and checkpoint is left untouched** (invariant 4: a completed result is never overwritten). Once every step is terminal, `reconcileRun` closes the run to `CANCELLED`. Cancellation therefore never erases a completed result and never moves an already-terminal run. This backs invariant 4 and the S07-12 acceptance criterion; it also fixes the race where a run whose last step is still running could otherwise appear cancelled while a completed result still sits on disk.
- **Refuse cancel on an already-terminal run.** `POST /api/v1/runs/{runId}/cancel` on a run that has already reached a terminal state (`SUCCEEDED`/`FAILED`/`CANCELLED`) returns `409 Conflict` — the run transition service rejects the terminal→`CANCEL_REQUESTED` transition — so a late or duplicate cancel is a clear conflict, not a silent no-op and never a backwards state change.


#### What changed
- `workflowexecution.web.WorkflowRunController` — adds `POST /api/v1/runs/{runId}/cancel` returning `202 Accepted` (and, through `RunService`, `409` for a terminal run).
- `workflowapplication.RunService` — `requestCancellation(tenantId, runId, source)`; the run transition is idempotent/one-way.
- `workflowapplication.WorkflowWorker` — cooperative drain: detects `CANCEL_REQUESTED` and cancels only the not-yet-started steps, reconciling the run to `CANCELLED` once every step is terminal; completed steps and their results are left untouched.
- `workflowexecution.domain.WorkflowStepState` — adds the `RETRY_WAIT → CANCELLED` edge so a run in retry-backoff remains cancellable.
- `RunStateMachineService` / `WorkflowStepRunRepository` — the run-level transition to and from `CANCEL_REQUESTED`/`CANCELLED`, guarded by the existing lease fence (invariant 5).

#### Why
Without a cancel path a stuck or unwanted run can never be stopped; with a naive one it would either leave the run in an inconsistent `CANCEL_REQUESTED` forever, or clobber a result that already completed (invariant 4), or interrupt an in-flight external call and produce an ambiguous outcome the worker cannot reconcile. Cooperative draining at the next safe boundary keeps a completed result authoritative, bounds the blast radius of a cancel to not-yet-started work, and keeps cancellation idempotent so duplicate HTTP requests and at-least-once delivery cannot double-apply (invariant 3).

#### Verification
- `:apps:agentflow-app:check` — green (compile, unit, architecture, formatting, OpenAPI contract gates; `spotlessApply` applied): **BUILD SUCCESSFUL, 0 failures** across unit + architecture + formatting gates.
- `:apps:agentflow-app:integrationTest` — **BUILD SUCCESSFUL**; `WorkflowCancelEndToEndIT` (real PostgreSQL, Testcontainers `pgvector/pgvector:0.8.6-pg18`, never `H2` per `AGENTS.md`) 3/3: `cancel_a_queued_run_drains_over_real_sql_and_cancels_the_not_started_steps`, `cancel_preserves_a_completed_step_and_cancels_only_the_pending_dependent`, `cancel_is_refused_on_an_already_completed_run_by_the_state_machine`.

#### Tests
- `WorkflowCancelEndToEndIT` (new, `src/integrationTest`, Testcontainers real PostgreSQL): `cancel_a_queued_run_drains_over_real_sql_and_cancels_the_not_started_steps`, `cancel_preserves_a_completed_step_and_cancels_only_the_pending_dependent`, `cancel_is_refused_on_an_already_completed_run_by_the_state_machine`.
- `RunServiceTest` — `request_cancellation_moves_a_queued_run_to_cancelling`, plus idempotent/duplicate and terminal-run refusal cases.
- `WorkflowWorkerTest` — drain cancels only the not-yet-started steps and preserves the completed step's result; a `CANCEL_REQUESTED` run closes to `CANCELLED` once every step is terminal.
- `WorkflowRunControllerTest` — `POST /runs/{runId}/cancel` returns `202` and verifies `requestCancellation`.
- `WorkflowStateMachineTest` / `TransitionTableServiceTest` — the new `RETRY_WAIT → CANCELLED` step edge.

#### Docs
`CHANGELOG.md` `[Unreleased] → Added`; this devlog section; `docs/architecture/run-state-machine.md` (drain design note) and `docs/architecture/step-state-machine.md` (cooperative cancel / incomplete-external-call note), both updated to match.

#### Schema / contract
Additive only: no new migration or column (`status` is still `TEXT`; `CANCEL_REQUESTED`/`CANCELLED` already exist on `WorkflowRunState` and `WorkflowStepState`), no change to API pagination, event shapes, or the module boundary beyond the new `/cancel` endpoint; the transition service gains the cancel path, the repository keeps its existing lease-fenced `transition`, and no existing call path changed behaviour.
## S07-10 — A retry policy value object with reproducible, bounded backoff (S07-10).


- **A retry policy value object with reproducible, bounded backoff (S07-10).** A failed step now has a *configured* answer to "how do I retry?" before the classifier (`S07-11`) even decides whether a fault is retryable. The new `RetryPolicy` (`workflowexecution.domain`, a `record`) is an immutable, dependency-free value object — value-equal like [`Checkpoint`](#s0707--persist-a-redacted-reusable-per-step-checkpoint-for-recovery-s0707) — that captures the five retry parameters: `maxAttempts`, `baseDelay`, `multiplier`, `maxDelay` and `jitter`. It exposes the delay before the next retry — exponential backoff `min(baseDelay * multiplier^(attemptsUsed-1), maxDelay)` — plus two seams that make it testable without the system clock or `Math.random` (invariant 10): `delayMillisForAttempt(attemptsUsed, jitter)` takes the jitter source as a parameter and `nextAttemptTime(from, attemptsUsed)` takes the reference instant. Because it only *computes* rather than sleeps, the worker stores the returned instant (in the attempt row, `S07-14`) and checks `now >= nextAttemptTime` instead of busy-looping. It is **bounded**: `isRetryAllowed(attemptsUsed)` is `attemptsUsed < maxAttempts`, so the `maxAttempts`-th failure is terminal and a step cannot retry forever. The `jitter` flag is honoured — the no-argument call applies full jitter (`random(0,1) * cappedDelay`, which keeps the maximum wait at the cap while spreading retries) when the flag is set and returns the plain capped delay otherwise.

#### What changed
- `workflowexecution.domain.RetryPolicy` — the value object: `of(...)`/`ofDefaults()` factories, validated canonical constructor (`maxAttempts >= 1`, `multiplier >= 1.0`, non-negative delays, `maxDelay >= baseDelay`), `delayMillisForAttempt` (with and without an injected jitter supplier), `isRetryAllowed`, `nextAttemptTime` (with and without an injected jitter supplier) and value equality.
- `config.AgentFlowProperties.Worker.Retry` — the retry tuning bound from `agentflow.worker.retry.*`: `max-attempts=3`, `base-delay-seconds=1`, `multiplier=2.0`, `max-delay-seconds=60`, `jitter=true`. They are configuration, not magic numbers (apps/agentflow-app AGENTS.md); `multiplier` and the `maxDelay >= baseDelay` relationship are rejected at bean creation through `RetryPolicy#of` rather than Jakarta-validated here.
- `config.WorkerRetryConfiguration` — exposes the `RetryPolicy` as a `@Bean` built from the config group. It is free of `DataSource`/Kafka so it is safe in every profile (the `api` role and the DB-less skeleton too, `S01-02`); a misconfiguration fails boot at bean creation.

#### Why
The classifier (`S07-11`) and the cooperative-shutdown path (`S07-13`) both need to know *how* a step retries; the policy is that configuration half, read by whoever makes the decision. Keeping the values in `agentflow.worker.retry.*` (not inline) means a runaway backoff can never ship as a hardcoded constant, and a single shared bean means the worker and the shutdown hook retry with the same policy.

#### Verification
- `:apps:agentflow-app:check` — green (unit, architecture, formatting; `spotlessApply` applied).
- `:apps:agentflow-app:test --tests com.example.agentflow.workflowexecution.domain.RetryPolicyTest` — 14/14 pass (DB-free).
- Architecture gate `NoCyclicModuleDependenciesTest` ran active (`tests=1, skipped=0`) — the new `config → workflowexecution.domain` dependency edge is acyclic.

#### Tests
`RetryPolicyTest` (14, DB-free, time and randomness injected): exponential backoff progression (`100, 200, 400, 800 ms`), the `maxDelay` cap (saturates, no overflow at `attemptsUsed=50`), a constant `multiplier=1.0`, jitter reproducibility under a fixed fraction, "full jitter never exceeds the cap" over `6 × 500` real draws, an out-of-range jitter fraction rejected, `isRetryAllowed` bounded by `maxAttempts`, `maxAttempts` bounds the total number of attempts, `nextAttemptTime` from an injected instant, value equality, and every invalid-construction case.

#### Configuration
New group `agentflow.worker.retry.*` (`max-attempts`, `base-delay-seconds`, `multiplier`, `max-delay-seconds`, `jitter`).

#### Docs
`docs/domain-glossary.md` §5 gains a *Retry policy* row; this devlog section; `CHANGELOG.md` `[Unreleased] → Added`.


## S07-11 — Classify step failures for a retry decision (S07-11).


- **Classify step failures into a retry decision (S07-11).** A step handler runs untrusted content (invariant 7) and can fail for many reasons; before a failed step is retried or declared terminal the worker must know *which* kind of fault it is, so a failed step's throwable is classified into one canonical {@link ErrorClass} from the shared `common` taxonomy (S02-10), and the retry decision is read off that class. This is the "retry classifier" named in [`docs/architecture/error-taxonomy.md`](docs/architecture/error-taxonomy.md). The new `StepFailureClassifier` (`workflowexecution.application`, a pure stateless function — shared through `StepFailureClassifier.defaults()`) maps the thrown throwable most-specifically-first: `ConflictException` and constraint/duplicate-key faults → {@link ErrorClass#CONFLICT}; malformed input (`IllegalArgumentException`, `IllegalStateException`, NPE, cast/format/regex) → {@link ErrorClass#VALIDATION}; `SecurityException` and Spring-Security authentication faults (recognised by the `org.springframework.security.*` class-name prefix, **not** by importing Security — no compile edge) → {@link ErrorClass#AUTHENTICATION}/{@link ErrorClass#AUTHORIZATION}; `SQLException`/connection resets/`ConnectException`/`UnknownHostException`/Kafka network/timeout faults → {@link ErrorClass#TRANSIENT_INFRA}/{@link ErrorClass#PROVIDER_TIMEOUT}; message heuristics cover HTTP 429 ("rate limit" / "throttl" / "too many requests" / "429") → {@link ErrorClass#PROVIDER_RATE_LIMIT} and "timeout"/"connection reset"/"unreachable" messages; a `null` throwable or anything unrecognized → {@link ErrorClass#INTERNAL} (permanent, operator-visible, never an unbounded retry). The retry decision is {@link ErrorClass#isRetryable()} (transient classes retry with bounded exponential backoff from the `RetryPolicy`, `S07-10`; every other class is permanent and the step ends {@link WorkflowStepState#FAILED}). The class mirrors the `messaging` inbox policy (`InboxErrorPolicy`) for the workflow failure surface. The persisted reason is {@link StepFailure#safeReason()} — the fault type and message redacted through the existing {@link CheckpointRedactor} and length-bounded to 300 chars — so the class never lands a secret, a full prompt or a document in `workflow_step_runs.error_summary` or a log (invariant 8).


#### What changed
- `workflowexecution.application.StepFailure` — the classification value object (`record StepFailure(ErrorClass, String)`; `isRetryable()` delegates to `ErrorClass.isRetryable()`); value-equal, pure, like `Checkpoint`.
- `workflowexecution.application.StepFailureClassifier` — the classifier: stateless `classify(Throwable)` producing a `StepFailure`; `defaults()` for the shared instance; framework/broker faults recognised by class-name prefix so `workflowexecution` never gains a Kafka or Security compile edge; the safe reason redacted through `CheckpointRedactor` and length-bounded.
- `workflowexecution.application.WorkflowWorker.executeStep` — on a handler fault (not a `ConflictException` / cancelled / skipped) it classifies through `StepFailureClassifier` and branches: a **transient** fault parks the step for a bounded retry (`transitionStep(..., RETRY_WAIT, null, null)`), a **permanent** fault ends it (`transitionStep(..., FAILED, errorClass, safeReason)`); the lease is released after classification so a crashed retry attempt is re-claimed (invariant 4).
- `workflowexecution.application.RunStateMachineService` — adds a second transition path (an 8-arg `transitionStep(runId, tenantId, stepKey, RUNNING, expectedLeaseEpoch, to, errorClass, safeReason)`) that records the fault's canonical class token and its short safe reason (both `null` for a clean transition).
- `workflowexecution.persistence.WorkflowStepRunRepository` — adds a second guarded `transition` overload (8-arg) that writes the error metadata: `error_code`/`error_summary` bound `COALESCE(existing, ?)` so a retry only records the class on the faulting transition (the value is set once and, per `S07-14`, cleared at a later attempt); a retry that passes `null` preserves any prior value.

#### Why
The `error-taxonomy.md` documents the {@link ErrorClass} taxonomy but nothing mapped a step failure to it; without a classification the worker would retry every fault or fail every fault. Classifying first makes the retry decision explicit and testable, matches the inbox error policy already in the `messaging` module, keeps the error class out of the metric labels (invariant 9 — the class is the canonical label, not a `tenantId`/`runId`), and records a redacted, operator-visible reason on the faulting row (invariant 8) without persisting the full, potentially-secrets-and-prompt message.

#### Verification
- `:apps:agentflow-app:check` — green (compile, unit, architecture, formatting; `spotlessApply` applied): **BUILD SUCCESSFUL, 504 tests, 0 failures, 0 skipped** across 74 classes.
- `:apps:agentflow-app:test --tests com.example.agentflow.workflowexecution.application.StepFailureClassifierTest` — 21/21 pass (DB-free: every class boundary, the security-prefix heuristic, connection/timeout/rate-limit heuristics, redaction, null).
- `:apps:agentflow-app:test --tests com.example.agentflow.workflowexecution.application.WorkflowWorkerTest` — green (the transient-fault parks for retry; the permanent-fault ends `FAILED` with the class recorded).
- `:apps:agentflow-app:test --tests com.example.agentflow.workflowexecution.application.RunStateMachineServiceTest` — green (the 8-arg `transitionStep` records the class + reason; the clean 6-arg path is unchanged).
- `:apps:agentflow-app:test --tests com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepositoryTest` — green (the 8-arg `transition` records error metadata with `COALESCE`; a `null` bind preserves a prior value).

#### Tests
- `StepFailureClassifierTest`: added `a_null_failure_is_classified_as_internal_and_permanent`, `an_unrecognized_fault_is_classified_as_internal`, `a_spring_security_authentication_fault_is_classified_as_authentication`, `a_spring_security_access_denied_fault_is_classified_as_authorization`, `a_connection_refused_message_is_transient_infra`, `a_rate_limit_message_is_provider_rate_limit`, `a_redacted_reason_is_safe_to_persist`, plus the 8 existing cases (14 → 21).
- `RunStateMachineServiceTest`: added `transitionStep_records_the_error_class_and_appends_it_to_the_event_for_a_fault` and `transitionStep_records_no_error_class_on_a_normal_retry_transition` (8-arg).
- `WorkflowStepRunRepositoryTest`: added `transition_records_the_error_class_and_safe_reason_with_a_coalesce_guard` and `transition_with_no_error_leaves_the_binds_null_so_a_prior_error_survives` (8-arg).
- `WorkflowWorkerTest`: added `a_transient_fault_is_classified_and_parks_the_step_for_a_retry` and `a_permanent_fault_is_classified_and_moves_the_step_to_failed`.

#### Docs
`CHANGELOG.md` `[Unreleased] → Added`; this devlog section; `docs/architecture/error-taxonomy.md` is the taxonomy this classifier implements (already present).

#### Schema / contract
Additive only: no new column (`error_code`/`error_summary` already exist, `V3_3_6`, `S05-08`), no change to the API, the `WorkflowStepState` enum, event shapes, or the module boundary; the repository gains a second overload, the transition service a second overload, and no existing 6-arg call path changed behaviour.


## S07-09 — Stale-run recovery loop that re-claims runs whose lease expired (S07-09).

- **Stale-run recovery loop that re-claims runs whose lease expired (S07-09).** The lease model [`ADR-011`](docs/adr/ADR-011-workflow-run-lease.md) — one live lease per run, fenced by a monotonic `lease_epoch` — is now *recovered* in code. The new `WorkflowRecoveryScheduler` (`workflowexecution.worker`, `@Component`) is the periodic scan the ADR describes: on a fixed cadence it finds the runs whose run-level lease has expired (`lease_owner` still set but `lease_until` already past) and re-claims each one atomically through the existing `RunLeaseService#claim(tenantId, runId)`, which bumps `lease_epoch` (so the dead owner is fenced, invariant 5) and resets `lease_until` (invariant 2: a stale run is paused, never lost). It is **enabled explicitly** (`@ConditionalOnProperty(prefix = "agentflow.worker", name = "recovery-enabled", havingValue = "true")` — `agentflow.worker.recovery-enabled=true`, default `false`), so lease recovery lives only where a live worker can drive a re-claimed run to progress, never "everywhere by default" (apps/agentflow-app AGENTS.md); it also needs a `WorkflowRunRepository` (`@ConditionalOnBean(DataSource.class)`, wired only when a database is configured). The scan is a **platform-wide** lease scan (`WorkflowRunRepository#findExpiredLeaseRuns(limit)`) — it reads rows by lease state, ordered `lease_until ASC`, bounded by `max-concurrent-runs`, so one pass re-claims a limited batch rather than table-scanning every run (ADR-011 operational consequence); every re-claim is still tenant-scoped through `claim` (invariant 1), so a cross-tenant id is indistinguishable from a missing run. A claim that returns `-1` (terminal run, a worker just took it, or a cross-tenant/missing id) is skipped — the re-claim is idempotent, so a duplicate is a no-op, not a lost run (invariant 3). Each successful reclaim publishes the new `agentflow.workflow.recoveries` counter once (invariant 9 — no `tenantId`/`runId` label). The scheduler owns the reclaim only, not the step execution: once re-claimed the run is owned by this worker and is driven by the [`S07-08`](docs/devlog/sprint-07-durable-execution.md) poll loop. **Config:** `agentflow.worker.recovery-enabled` (default `false`), `agentflow.worker.max-concurrent-runs` (default `8`), `agentflow.worker.recovery-fixed-rate-ms` (default `5000`, matching the ADR `recovery-interval-seconds`), wired into `AgentFlowProperties.Worker`; `@EnableScheduling` on `AgentflowApplication` backs the `@Scheduled` cadence. **Tests:** the DB-free `WorkflowRecoverySchedulerTest` (6 — reclaim + counter increment, skip a `-1` claim, claim only the runs that match, the scan is bounded by `max-concurrent-runs`, `IllegalStateException` when no database, and no tenant/run label on the counter) over mocked collaborators; the real-PostgreSQL acceptance suite `WorkflowRecoverySchedulerIT` (`src/integrationTest`, Testcontainers `pgvector/pgvector:0.8.6-pg18`, never `H2` per `AGENTS.md`): an expired run is re-claimed with the epoch bumped (`5 → 6`) and the lease reset; a terminal run is never re-claimed even with an expired lease; and `max-concurrent-runs = 3` bounds a pass that seeds ten expired runs to exactly three reclaims. The IT isolates each run in its own tenant and cleans the tenant up after each test (`@AfterEach`) because the scan is platform-wide. **Verification:** `:apps:agentflow-app:spotlessCheck` and `:apps:agentflow-app:test` are green (unit + architecture + formatting), and `:apps:agentflow-app:integrationTest --tests com.example.agentflow.workflowexecution.worker.WorkflowRecoverySchedulerIT` is green (3/3, real PostgreSQL). **No** migration change — the three lease columns already exist on `workflow_runs` (`V3_3_6`, `S05-08`); **no** contract, API, schema, dependency or module-boundary change (the scheduler imports only `workflowexecution`, `AgentFlowProperties`, and the external `micrometer` / JDK `javax.sql.DataSource`).


## S07-08 — A first workflow worker: a durable event loop that runs a claimed run to completion (S07-08).



- **A first workflow worker: a durable event loop that runs a claimed run to completion (S07-08).** The worker polls a tenant for claimable runs, claims each one (`run.started`) by atomically acquiring the lease, loads the pinned definition version, materializes a `PENDING` row per graph node, and steps the run through the state machine until it is quiescent; it then reconciles the terminal state. A deterministic 2–3 step workflow now completes end to end (proven through the real state machine over a real PostgreSQL in {@link WorkflowWorkerEndToEndIT}, not a mock). The worker never sets status directly — every transition goes through the single transition service with the tenant-scoped conditional update and the `lease_epoch` fence (invariants 4 and 5). It re-checks the run's owner through the tenant-scoped `WorkflowRunRepository#findRun` before claiming (invariant 1), and it publishes the domain events (`run.started`, `step.ready/running/succeeded/failed/skipped`, `run.finished/failed`) that the transactional-outbox forwards — it never mutates state inline and holds no in-flight state of its own, so a restart leaves the run at its last persisted state for the recovery scheduler (S07-09) to resume.

#### What changed
- `workflowexecution.application.WorkflowWorker` is the event loop: `pollOnce(tenantId)` runs every claimable run of a tenant to quiescence; each run is claimed once, its nodes materialized (each node once per run — the `unique (run_id, step_key, attempt)` index makes a second materialization a collision rather than a silent duplicate), and executed in graph order with a step's `SUCCEEDED` output wired as the downstream input.
- `workflowexecution.application.DeterministicStepHandler` is the demo step handler driving the acceptance path (supports `NOOP`); the real `StepHandler` implementations (`toolexecution`, `ai`) plug into the same seam.
- `workflowexecution.persistence.WorkflowStepRunRepository` gains three tenant-scoped methods — `createStep`, `findStepStatuses`, `findStepOutputs` — reusing the existing `workflow_step_runs` columns (`step_key`, `output_inline`, `lease_epoch`, `attempt`, `created_by`); no new migration is required. `findStepStatuses` / `findStepOutputs` join `workflow_runs` and filter by the parent's `tenant_id` (invariant 1); `createStep` seeds the step's `lease_epoch` from the run's claimed epoch so the step fence matches the epoch the worker sends on its transitions (invariant 5).
- Unit tests (`WorkflowWorkerTest`, `DeterministicStepHandlerTest`) cover claim / poll / execute / quiesce / fence and the handler seam over mocked collaborators (DB-free).
- `WorkflowWorkerEndToEndIT` (the integration suite, Testcontainers `pgvector/pgvector:0.8.6-pg18`) drives a deterministic two-step workflow through the real migrations, the real state machine and the real worker, and asserts the run reaches `SUCCEEDED` with both step checkpoints persisted (`a-done`, `b-done`).

#### Why
The worker is the consumer of the durable state machine: state changes only through the transition service (invariant 4), leases/fencing fence every step write (invariant 5), and tenant isolation flows through the parent run (invariant 1). Because it drives the persisted machine instead of holding state in memory, recovery never has to reconstruct an in-flight run. The acceptance criterion (a deterministic 2–3 step workflow completes) is backed by the real-machine integration test, not a mock, so the claim is a measurement rather than an assertion (AGENTS.md: any reliability/performance claim is backed by a test or a measurement).



## S07-07 — Persist a redacted, reusable per-step checkpoint for recovery (S07-07).



- **Persist a redacted, reusable per-step checkpoint for recovery (S07-07).** A completed step's
  output is now stored as a checkpoint the recovery path can reuse instead of re-executing the
  step. The output is stored inline in the new nullable `workflow_step_runs.output_inline`, with
  its `output_schema_version`, bounded to `agentflow.checkpoint.max-inline-bytes` (256 KiB by
  default). It is redacted before it is persisted (invariant 8), and recovery reuses it only when
  the stored schema version matches the step's current output-schema version (invariant 13); a
  mismatch re-executes rather than serving a stale value. `output_ref` stays NULL — the
  large-payload path (object storage + ref/checksum) is `S15-06`.
- **`CheckpointAdapter` — the single seam that saves and reuses a step's checkpoint.** It redacts
  the output via `CheckpointRedactor`, bounds it to `agentflow.checkpoint.max-inline-bytes`,
  persists it through `WorkflowStepRunRepository.saveCheckpoint` (guarded by the tenant, the
  `lease_epoch` fence and a `SUCCEEDED` step), and answers the reuse decision `canReuseCheckpoint`
  (stored `output_schema_version` matches the current version and the output is inline). It is the
  seam the future step executor calls on `SUCCEEDED` and before a re-run; here it is exercised by
  unit tests directly.
- **A configurable size bound for inline checkpoints.** `agentflow.checkpoint.max-inline-bytes`
  defaults to `262_144` (256 KiB); a value below 1 fails startup (`@Min(1)`).

#### What changed
- Migration `V3_3_15__add_step_checkpoint_outputs.sql` (`owner: workflowexecution`) adds the
  nullable `workflow_step_runs.output_inline TEXT` and `output_schema_version TEXT`, each carrying a
  `COMMENT` attributing it to S07-07 / `workflowexecution` and invariant 8. A `CHECK(output_ref IS
  NULL OR output_inline IS NULL)` enforces that a row never carries both an inline checkpoint and
  an object-storage ref.
- `workflowexecution.domain.Checkpoint` is the persisted value type (`schemaVersion`, `output`);
  `workflowexecution.persistence.CheckpointSnapshot` is the row-mapped projection.
- `workflowexecution.persistence.WorkflowStepRunRepository` gains `saveCheckpoint(tenantId, runId,
  stepKey, expectedLeaseEpoch, Checkpoint)` (a guarded conditional update that joins `workflow_runs`
  for the tenant, fences by `lease_epoch`, and matches only a `SUCCEEDED` step) and
  `findCheckpoint(tenantId, runId, stepKey)` (tenant-scoped, returns the snapshot or empty).
- `workflowexecution.application.CheckpointRedactor` redacts bearer/authorization headers, common
  secret JSON keys (`password`, `token`, `api_key`, `access_token`, `client_secret`, …),
  social-security numbers, credit-card numbers and emails before persist.
- Unit tests cover the redactor rules, the adapter's redact/size-reuse decisions, the repository's
  tenant/lease/status guard, and the migration's nullable columns, S07-07 attribution and
  `output_ref`/`output_inline` mutual exclusion (integration).

#### Why
Recovery must be able to answer "is this step's result still valid?" without re-executing a step.
The checkpoint is the idempotency-key pattern (`S07-01`, dedupe by input) extended to the output:
recovery compares the stored schema version with the current one, and only when they match accepts
the stored output. Redaction before persist (invariant 8) keeps secrets out of the row; the size
bound keeps the hot path small; the schema-version gate (invariant 13) makes a changed output
contract a re-execution rather than a silent stale value. `output_ref` is left NULL because the
object-storage path is out of scope until `S15-06`, so the mutual-exclusion `CHECK` simply protects
that invariant for the future.



## S07-06 — Persist the attempt lifecycle so the crash window is diagnosable (S07-06).



- **Persist the attempt lifecycle so the crash window is diagnosable (S07-06).** The
  `workflow_step_runs` table gains an `attempt_started_at` marker: it is set to `now()` when the
  step enters `RUNNING` (recorded before the external side effect) and cleared to `NULL` when the
  attempt reaches a terminal outcome (`SUCCEEDED`/`FAILED`/`CANCELLED`). A step row still `RUNNING`
  with a non-null marker but no terminal event after the `RUNNING` transition is the crash window —
  the worker died mid-attempt, so the outcome is ambiguous and the attempt is re-claimed, never
  assumed to have failed. `attempt_started_at` is nullable, which distinguishes a step that has not
  begun an attempt (`NULL`) from an in-flight attempt with an unknown outcome (`RUNNING` + set
  marker).
- **`workflow_run` transition writes the attempt marker atomically (S07-06).** The step-run
  repository's single guarded write (`status` + `lease_epoch`) now also manages
  `attempt_started_at` in one SQL statement: a `CASE` on two bound booleans sets `now()` on the
  `RUNNING` entry, clears `NULL` on the terminal transition, and leaves the marker unchanged on a
  working transition (`RUNNING → RETRY_WAIT`, `RUNNING → WAITING_FOR_APPROVAL`).

#### What changed
- Migration `V3_3_14__add_step_attempt_timestamps.sql` (`owner: workflowexecution`) adds the
  nullable `workflow_step_runs.attempt_started_at TIMESTAMPTZ` with a comment attributing it to
  S07-06 / `workflowexecution` and invariant 6 (`ADR-011`).
- `workflow_step_runs.transition` in `WorkflowStepRunRepository` manages `attempt_started_at` in
  the same transactional `UPDATE` as `status`; the `attempt` number (the attempt identity, bumped
  on retry) is unchanged.
- Unit tests assert the marker binding for begin (`PENDING → READY → RUNNING`, `RETRY_WAIT →
  RUNNING`), clear (`RUNNING → SUCCEEDED/FAILED/CANCELLED`), keep (`RUNNING → RETRY_WAIT`,
  `RUNNING → WAITING_FOR_APPROVAL`), and never-set (`PENDING → READY`) transitions.
- Integration test asserts the column exists, is nullable, and carries its S07-06 comment.

#### Why
Invariant 6 / `ADR-011` requires that an external call is not exactly-once and that a worker crash
mid-attempt leaves a diagnosable trace rather than an assumed failure. The `attempt` identity
(`S07-14`) says *which* attempt ran; `attempt_started_at` says *when* it began, so the boundary
between "attempt started" and "outcome recorded" is observable and an in-flight attempt is
re-claimed rather than silently failed.

#### Verification
- `:apps:agentflow-app:check` (unit, architecture, formatting) — green; `gradlew spotlessApply`
  applied.
- `:apps:agentflow-app:integrationTest --tests com.example.agentflow.MigrationsTest` — green (real
  PostgreSQL via Testcontainers, 15 tests).
- `:apps:agentflow-app:test --tests com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepositoryTest` — green.

#### Tests
- `WorkflowStepRunRepositoryTest`: added `transition_into_running_records_the_attempt_begin_marker`,
  `transition_from_retry_wait_to_running_restarts_the_attempt_marker`, `transition_to_success_clears_the_attempt_marker`,
  `transition_to_failed_clears_the_attempt_marker`, `transition_to_cancelled_clears_the_attempt_marker`,
  `transition_running_to_retry_wait_keeps_the_attempt_marker`, `transition_running_to_waiting_for_approval_keeps_the_attempt_marker`,
  `transition_from_pending_never_sets_the_attempt_marker`, and the `assertMarker` helper.
- `MigrationsTest`: added `step_attempt_marker_is_nullable_and_attributed` (`workflow_step_runs.attempt_started_at` nullable + `S07-06` comment);
  `"3.3.14"` added to the ordered-applied list.

#### Docs
- `docs/architecture/step-state-machine.md` documents the attempt lifecycle and the crash window
  in §3 design notes and §5 step-state aggregation.

#### Schema / contract
- Additive only: one nullable column. No change to the API, the `WorkflowStepState` enum, event
  shapes, or the module boundary. No new dependency.



## S07-05 — Run-level write fenced by `lease_epoch` (S07-05).



- **Run-level write fenced by `lease_epoch` (S07-05).** The ADR-011 lease model now fences the run's *write*, not just the step's — a stale owner can no longer persist a result after its run has been re-claimed by another worker. Previously the run write (`WorkflowRunRepository#transition`) was guarded only by the optimistic-lock `version` (`AND status = ? AND version = ?`); `S07-05` adds the same fencing primitive the step write already used (`WorkflowStepRunRepository`), checking the run's `lease_epoch`: the new SQL is `... WHERE id = ? AND tenant_id = ? AND status = ? AND version = ? AND lease_epoch = ?`, and `transition` now takes an `expectedLeaseEpoch` argument. The single transition service (`RunStateMachineService`) reads the current snapshot through the tenant-scoped `WorkflowRunRepository#findState` (which now also selects `lease_epoch`) and echoes that epoch back on the write, so the read and the fence close on the same value. A zero-row update — the run is gone, belongs to another tenant, is no longer in the expected state, the `version` is stale, or the `lease_epoch` moved because another worker re-claimed the run — is mapped to `ConflictException` (HTTP 409) instead of silently overwriting the new owner. This satisfies the S07-05 acceptance criterion: **worker A cannot save a result after claim by worker B.** **Tests:** `WorkflowRunRepositoryTest` asserts the 6th bind (`lease_epoch`); `RunStateMachineServiceTest` and `TransitionTableServiceTest` were updated to the 6-arg signature (the stale-owner guard now flows through the service, so the happy-path verify checks `transition(runId, tenant, from, version, leaseEpoch, to)`); the DB-free conflict test still passes a stale epoch. A new real-PostgreSQL integration test (`WorkflowTransitionIntegrationTest#run_transition_with_a_stale_lease_epoch_is_a_conflict`) bumps the seeded run's `lease_epoch` and asserts the second worker's update matches zero rows and becomes a `409` while the run stays in its state — the mirror of the existing step-level `step_transition_with_a_stale_lease_epoch_is_a_conflict`. **Docs:** updated [`docs/adr/ADR-011-workflow-run-lease.md`](docs/adr/ADR-011-workflow-run-lease.md) (both the run and step writes now fence on `lease_epoch`; a run row added to the stale-owner table), `docs/architecture/run-state-machine.md` §4 (the conditional-update predicate), and `docs/architecture/module-api.md` (the run-write fence noted; the release job's `S07-05` reference was a stale attribution and is corrected — `S07-05` is this fence, not the release job). **Verification — all green:** `:apps:agentflow-app:compileTestJava`, `:compileIntegrationTestJava`, the unit suite (`workflowexecution`, 104 tests, 0 failures) and the transition integration suite (12 tests, 0 failures, real PostgreSQL via Testcontainers `pgvector/pgvector:0.8.6-pg18`). Additive to the schema (the `lease_epoch` column pre-exists, `V3_3_6`); it changes the repository contract (one more guarded parameter), so the new argument must be threaded by every caller.



## S07-04 — Run-level lease heartbeat (`S07-04`).



- **Run-level lease heartbeat (`S07-04`).** The ADR-011 lease model — one live lease per run, fenced by a monotonic `lease_epoch` — now renews itself in code so a running worker is never mistaken for a crashed owner and its run is not reclaimed prematurely. The new `RunHeartbeatService` (`workflowexecution.application`, `@Service`) extends the run's `lease_until` one lifetime per call, computed through the injected `Clock` (invariant 10 — never the system clock in domain/application code) as `lease_until = clock.instant() + duration-seconds`, and performs the renewal through the new `WorkflowRunRepository#heartbeat`: a single conditional `UPDATE workflow_runs SET lease_until = ? WHERE id = ? AND tenant_id = ? AND lease_owner = ? AND lease_epoch = ?`. The `WHERE` is the fence (invariant 5): the lease is renewed only while the run still belongs to the tenant, is still owned by this worker, and still holds the `lease_epoch` the claim returned — so **an old owner cannot renew its lease after it is re-claimed** (the S07-04 acceptance criterion; the returned row count is `0` = rejected). A heartbeat never bumps the epoch (only the claim does) and never clears the lease. Extension stops per the cancel policy automatically: once a run reaches a state that releases the lease (a `HUMAN_APPROVAL` pause or a cancelled run, per `step-state-machine.md` §10 / `run-state-machine.md`) its `lease_owner` is no longer this worker, so the fenced heartbeat matches zero rows and stops renewing. A rejected heartbeat is counted (`RunHeartbeatService#failedHeartbeats()`) — a per-process counter, carrying no `tenantId`/`runId` (invariant 9), for surfacing as a metric in the S07-09 recovery task. The bean wires only when a `WorkflowRunRepository` (a `@ConditionalOnBean(DataSource.class)` repository) is present, reporting `503`/throwing otherwise when no database is configured (S01-02), matching `RunLeaseService` and the transition service. **Tests:** the DB-free `RunHeartbeatServiceTest` (4 — the clock-driven renewed `lease_until`, `extended` only on a matching owner+epoch, rejection + failure counting when the run was re-claimed, and `503` when no database) and `WorkflowRunRepositoryHeartbeatTest` (3 — the fenced `WHERE`, the binds, and the zero-row rejection) through a mocked `JdbcTemplate`; the real-PostgreSQL acceptance suite `RunLeaseHeartbeatIT` (`src/integrationTest`, Testcontainers `pgvector/pgvector:0.8.6-pg18`, never `H2` per `AGENTS.md`): `old_owner_cannot_extend_after_reclaim` (claim → extend → re-claim bumps the epoch → stale owner's heartbeat matches 0 rows, new owner's extends) and `live_owner_heartbeat_extends_until_without_bumping_the_epoch`. **Verification:** `./gradlew check` is green (unit + architecture + spotless + OpenAPI contract gates, 413 unit tests + 6 architecture tests, 0 failures) and `./gradlew :apps:agentflow-app:integrationTest --tests *RunLeaseHeartbeatIT*` is green (2/2, real PostgreSQL). **No** migration change — the three lease columns already exist on `workflow_runs` (`V3_3_6`, `S05-08`); **no** contract, API, schema, dependency or module-boundary change. **Docs:** [`docs/architecture/module-api.md`](docs/architecture/module-api.md) now lists `RunLeaseService` (claim) and the new `RunHeartbeatService` (heartbeat) as the `workflowexecution` public lease services (correcting the earlier `LeaseService` placeholder that also bundled the not-yet-implemented release/recovery); [`docs/adr/ADR-011-workflow-run-lease.md`](docs/adr/ADR-011-workflow-run-lease.md) already fixes the heartbeat model and `docs/domain-glossary.md` §5 already canonicalizes the lease vocabulary; this entry records the implementation.

- **Heartbeat woven into the worker loop (gap-b fix).** `RunHeartbeatService` existed but the event loop never called it (S07-08 executed each step synchronously with no lease renewal during a long-running step): a step longer than `duration-seconds` (30 s) would let the run-level lease expire mid-step, so the S07-09 recovery scan would re-claim a run this worker was still executing and fence its writes (a `409`). The fix drives the heartbeat from the step loop: `WorkflowWorker` now `implements DisposableBean`, holds a `RunHeartbeatService` and a single daemon-thread `ScheduledExecutorService`, and in `executeStep` starts a heartbeat task (one `heartbeat(tenantId, runId, owner, epoch)` per `heartbeat-interval-seconds`, on the dedicated thread) right after the step reaches `RUNNING` and cancels it in `finally` the instant the step concludes. The fence is the stable lease `owner` (`RunLeaseService#workerId()`, memoized — never regenerated per call) and the `epoch` `claim` returned, so the fenced update matches. **Critical detail:** the blocking `StepHandler#handle` must run *outside* the poll transaction — otherwise the fenced lease `UPDATE` blocks on the row lock the poll transaction holds from `claim` + `transitionRun(RUNNING)`, and the lease is still never extended. So `WorkflowWorker#pollOnce` is no longer `@Transactional`; the claim and each step/run transition commit through their own `@Transactional` `RunStateMachineService` method, leaving no lock held across the handler. `destroy()` shuts the scheduler down (S07-13 hygiene; the in-flight step's own heartbeat already stops when the step concludes). **Tests:** the DB-free `WorkflowWorkerHeartbeatTest` (1 — a `SLOW` handler blocks on a latch while the cadence is at its minimum; asserts `heartbeat` is called with the exact `(tenant, run, owner, epoch)` fence while the step is in flight, then the run reconciles to `SUCCEEDED` after release) added to the existing `WorkflowWorkerTest`/`WorkerLifecycleTest`/`WorkflowWorkerShutdownTest` suites (all green). **No** migration, contract, API, schema, dependency or module-boundary change; the transaction-boundary change (one poll = several short commits instead of one) makes each durable state change immediately visible to recovery, which is the intended behaviour. **Docs:** this entry; `WorkflowWorker` Javadoc records the boundary.



## S07-03 — Atomic workflow-run claim (`S07-03`).



- **Atomic workflow-run claim (`S07-03`).** The lease model [`ADR-011`](docs/adr/ADR-011-workflow-run-lease.md) fixes — one live lease per run, fenced by a monotonic `lease_epoch` — is now claimable in code. The new `RunLeaseService` (`workflowexecution.application`, `@Service`) claims a run before a worker executes it: it computes the lease expiry `lease_until = clock.instant() + duration-seconds` through the injected `Clock` (invariant 10 — never the system clock in domain/application code), resolves this worker's identity from `AgentFlowProperties.Worker.instance-id` (`agentflow.worker.instance-id`), generating a UUID when unset so `lease_owner` is never blank on the recovery scan, and performs the atomic claim through the new `WorkflowRunRepository#claim`. The claim is a single conditional `UPDATE workflow_runs SET lease_owner = ?, lease_until = ?, lease_epoch = lease_epoch + 1 WHERE id = ? AND tenant_id = ? AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND (lease_owner IS NULL OR lease_until < now()) RETURNING lease_epoch`: one statement, tenant-scoped in the `WHERE` (invariant 1), matching only a claimable run — not terminal and whose lease is absent or expired — so two racing workers end with exactly one winner (invariant 4) and a zero-row match is indistinguishable from a missing run. The returned `lease_epoch` is the fence the worker must echo on every subsequent write (`S07-05`); `claim` returns it, or `-1` when no row matched (a terminal run, a live owner, or a cross-tenant id). The lease tuning is configuration, not magic numbers — the new `AgentFlowProperties.Worker.Lease` group bound from `agentflow.worker.lease.*` (`duration-seconds = 30`, `heartbeat-interval-seconds = 10`, `recovery-interval-seconds = 5`, the ADR-011 ratios) — and the bean wires only when a `WorkflowRunRepository` (a `@ConditionalOnBean(DataSource.class)` repository) is present, reporting `503`/throwing otherwise when no database is configured (S01-02), matching the transition service. **Tests:** the DB-free `RunLeaseServiceTest` (4 — the clock-driven `lease_until`, epoch passthrough, `-1` on a zero-row claim, and the configured and generated worker ids) and `WorkflowRunRepositoryClaimTest` (2 — the SQL binds, the tenant-scoped `WHERE`, the terminal exclusion and the absent/expired-lease guard through a mocked `JdbcTemplate`); the real-PostgreSQL acceptance suite `RunLeaseClaimIT` (`src/integrationTest`, Testcontainers `pgvector/pgvector:0.8.6-pg18`, never `H2` per `AGENTS.md`): the S07-03 criterion `two_concurrent_claims_leave_exactly_one_owner` (two threads claim one row over independent physical connections, exactly one wins), plus a fresh claim, a live lease not being stolen, a terminal run never re-claimed and an expired lease reclaimed with the epoch bumped. **Verification:** `./gradlew clean check` is green (unit + architecture + spotless + OpenAPI contract gates, 0 failures) and `./gradlew :apps:agentflow-app:integrationTest --tests *RunLeaseClaimIT*` is green (5/5, real PostgreSQL). **No** migration change — the three lease columns already exist on `workflow_runs` (`V3_3_6`, `S05-08`); **no** contract or schema change. **Docs:** [`docs/adr/ADR-011-workflow-run-lease.md`](docs/adr/ADR-011-workflow-run-lease.md) already names `RunLeaseService` (S07-03) as the claim owner; `docs/domain-glossary.md` §5 already canonicalizes the lease vocabulary; this entry records the implementation.



## S07-02 — Workflow-run lease-recovery index (`S07-02`).



- **Workflow-run lease-recovery index (`S07-02`).** The durable-execution Sprint 7 lease model
  (`ADR-011`) needs the recovery scheduler to find expired runs efficiently: a run whose
  `lease_until` has passed and whose `lease_owner` still names a worker that stopped heartbeating,
  re-claimed atomically. The three lease columns (`lease_owner`, `lease_until`, `lease_epoch`)
  already exist on `workflow_runs` (`V3_3_6`, `S05-08`); `S07-02` adds the missing piece — an index
  on `lease_until` (`V3_3_13__add_lease_until_index.sql`,
  `workflow_runs_lease_until_idx`) that turns the recovery scan into an index range scan instead of
  a full table scan. The index is on the lease column only, keeps the lease state separate from the
  run's business state, and does not duplicate any constraint. **Docs:** updated the migration
  integration suite (`MigrationsTest`: the ordered apply list now ends at `3.3.13`, plus a
  `lease_recovery_index_exists` assertion) and `docs/architecture/database-conventions.md` (the
  index set). **Verification: PARTIAL —** the migration applies in order and the index exists, but
  the real-PostgreSQL suite (`MigrationsTest`, Testcontainers `pgvector/pgvector:0.8.6-pg18`)
  could not be run in this session because the Docker daemon is not reachable; it is the intended
  verifier (`./gradlew integrationTest`) and must pass before merge. `./gradlew check` (compile +
  formatting + architecture + unit gates) is the offline gate run here. The pre-existing columns
  mean this is a purely additive, forward-only schema change (`ADR-008`); **no** Java, contract,
  dependency or module-boundary change.



## S07-01 — Workflow-run lease, heartbeat and recovery model (`S07-01`).



- **Workflow-run lease, heartbeat and recovery model (`S07-01`).** The durable-execution Sprint 7
  gets its lease decision recorded as [`ADR-011`](docs/adr/ADR-011-workflow-run-lease.md): a single
  run has one live lease held by the worker instance that atomically claims it, expressed in three
  columns on `workflow_runs` — `lease_owner` (the worker instance, set on claim, cleared at a
  terminal state or a `HUMAN_APPROVAL` pause), `lease_until` (a `timestamptz` expiry, computed and
  extended through the injected `Clock`, never `System.nanoTime()`), and `lease_epoch` (a monotonic
  fencing counter, already present in `V3_3_6`, bumped on every re-claim). Only the **current owner**
  (matching `lease_owner` **and** `lease_epoch`) may persist progress — a stale worker's write hits a
  moved `lease_epoch`, matches zero rows and becomes a `409 conflict` (the same mapping
  [`ADR-009`](docs/adr/ADR-009-single-persistence-spring-data-jdbc.md) documents, and the behaviour
  already enforced on step writes by `workflowexecution.persistence.WorkflowStepRunRepository`); a
  **stale owner is detected** by the recovery scan when `lease_until < now()` and `lease_owner` still
  names a worker that stopped heartbeating, and re-claimed atomically (bumping the epoch so it can no
  longer write). The lease/fencing parameters are configuration (`AgentFlowProperties.Worker.Lease`),
  not magic numbers: `duration-seconds = 30`, `heartbeat-interval-seconds = 10` (≈ `duration / 3`, so
  a single lost heartbeat never expires a healthy lease) and `recovery-interval-seconds = 5`, chosen
  so `duration + recovery ≤ 60` and the `S07-15` "resume ≤ 60 s after expiry" holds in test config.
  The decision is **at-least-once, never exactly-once** (`ADR-003`, invariant 6): the model makes
  ownership explicit and crash recovery possible, but a step whose external call crashed mid-flight is
  re-run after re-claim and recorded in attempt history (`S07-06`/`S07-14`), never pretended to be a
  single execution. Fence lives in PostgreSQL (`lease_epoch`), not JVM state (invariant 5). This is
  the *decision*; the columns already exist (`V3_3_6__create_workflow_runs_and_steps.sql`, `S05-08`)
  and the expired-lease index is now implemented (`S07-02`, `V3_3_13__add_lease_until_index.sql`),
  but the claim/heartbeat/recovery services (`S07-03`, `S07-04`, `S07-09`) are not yet implemented,
  and the ADR records that split honestly. The domain
  glossary §5 already canonicalizes this exact concept (`lease_owner`/`lease_until`/`lease_epoch`,
  heartbeat, recovery job), so `docs/domain-glossary.md` needs no change. **Docs:** the new
  [`docs/adr/ADR-011-workflow-run-lease.md`](docs/adr/ADR-011-workflow-run-lease.md); the ADR register
  ([`docs/adr/README.md`](docs/adr/README.md), which was also missing the `ADR-010` row, now added)
  and the [`docs/README.md`](docs/README.md) index tree (pointer to the new ADR, alongside the
  `⚠ planned (S07-17)` `durable-execution.md` line). **Verification: documentation-only — there is no
  `./gradlew check` gate for a docs change.** Link integrity verified: `git diff --check` passes and
  every relative in-document link in the new and edited files resolves to a present, on-topic
  destination (the cited ADR ids, `V3_3_6`, `WorkflowStepRunRepository`, `AgentFlowProperties.Worker.Lease`,
  the glossary §5 entry and the `S07-02`…`S07-09`/`S07-15`/`S07-17` follow-ups all resolve and are
  on-topic). **No** Java, contract, schema, migration or dependency change.
