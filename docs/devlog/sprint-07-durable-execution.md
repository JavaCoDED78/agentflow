# Changelog



# Sprint 7 — Durable execution



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
