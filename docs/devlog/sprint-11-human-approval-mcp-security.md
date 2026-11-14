# Sprint 11 — human approval, MCP and security of agent actions

## Contents

- [S11-01](#s11-01-tool-risk-classification-a-closed-deny-by-default-vocabulary-owned-by-the-catalogue) — tool risk classification policy (S11-01)
- [S11-02](#s11-02-durable-approvals-schema) — durable approvals schema (S11-02)
- [S11-03](#s11-03-durable-approval-pause) — the durable approval pause (WAITING_FOR_APPROVAL): the worker parks a run at a HUMAN_APPROVAL boundary and releases the worker lease (S11-03)
- [S11-04](#s11-04) — future: tool allowlist enforcement + permission checks on agent actions
- [S11-05](#s11-05) — future: MCP tool allowlist and risk classification
- [S11-06](#s11-06) — future: MCP gateway hardening (transport, secrets, telemetry)

---

## S11-03 — durable approval pause (`WAITING_FOR_APPROVAL`)

**Task (P0):** the worker stops a run at a `HUMAN_APPROVAL` side-effect boundary and parks it in `WAITING_FOR_APPROVAL` — the durable pause the `approvals` table (S11-02) records.

**Deliverable:** the worker pause — a durable checkpoint of where execution stopped, with the worker lease released while the run waits. No new table, no new endpoint, no new module edge.

### What changed

- **A boundary before the handler.** `workflowexecution.application.WorkflowWorker#executeStep` gains the gate: at the top of a step's lifecycle — before the heartbeat and the `StepHandler` — it checks `HUMAN_APPROVAL.equals(node.type())` and, when true, routes to `pauseForApproval` instead of running a handler. The gated side effect never executes (invariant 7), so the model cannot run a forbidden tool past a human gate and no worker thread is held while the run waits (S11-03 acceptance).
- **`pauseForApproval`** records the durable approval first (`ApprovalRepository#create(tenant, run, step, action, payloadFingerprint, actor, actor)` — the redacted `action` summary and the canonical-config SHA-256 `payload_fingerprint`, never the payload; invariant 8), parks the run (`RunStateMachineService#transitionRun(RUNNING → WAITING_FOR_APPROVAL, run.approval-needed)` — applied before the step; the run-level pause is the higher-level fact and if the run's lease moved the pause changes nothing and the next poll recovers) and then the step (`transitionStep(WAITING_FOR_APPROVAL, step.approval-needed)`), and finally releases the run's lease (`WorkflowRunRepository#releaseLease` — a new conditional `UPDATE ... SET lease_owner = NULL, lease_until = NULL, version = version + 1 WHERE id = ? AND tenant_id = ? AND lease_epoch = ?`, fenced on `lease_epoch`, invariant 5) so the parked run is claimable again for the resume (S11-04) rather than re-running its gated step.
- **Best-effort release.** The lease release is wrapped: a `ConflictException` (the lease moved in the gap between the step and the release) is logged, never failed. The pause is durable regardless of the release — a restart re-pauses rather than skipping the approval (the acceptance: a restart neither skips the approval nor leaves a thread occupied). The checkpoint of where execution stopped is the `WAITING_FOR_APPROVAL` state itself — a higher-level pause, not a step failure; the step's in-flight attempt is concluded by the transition service (invariant 4).

### Why

- **Distrust of the model (invariant 7):** the `HUMAN_APPROVAL` node is a deny-by-default gate — the gated side effect must not run without an explicit human decision.
- **Lease/fencing (invariant 5):** the parked run must not hold a worker thread; releasing the lease (fenced on `lease_epoch`) makes it claimable for the resume instead of re-running the gated step.
- **PostgreSQL is the source of truth (invariant 4):** the pause is durable in the run/step status; Redis is not consulted.
- **Consistency (ADR-002/003):** forward-only — no new migration (`approvals` is `V3_3_22` / S11-02); the pause is a runtime `WAITING_FOR_APPROVAL`, not a step failure.

### Verification

One new DB-free acceptance suite, JUnit-XML-verified: `WorkflowWorkerTest` (`apps/agentflow-app/src/test/java/com/example/agentflow/workflowexecution/application/WorkflowWorkerTest.java`, `tests="13"` `failures="0"` `errors="0"` `skipped="0"`) gains `a_human_approval_pause_survives_a_restart_without_skipping_the_side_effect` — a two-node graph (`HUMAN_APPROVAL` → `NOOP`) pauses at the gate (`send-report` in `WAITING_FOR_APPROVAL`, the downstream `send-email` still `PENDING`, its recording handler never invoked), records one durable approval at the pause, releases the run's lease once and never re-holds it across a simulated restart, and proves the restart neither skips the approval nor runs the gated side effect. `ApprovalRepositoryTest` (`persistence`, `tests="2"`) covers the tenant-scoped insert and the SQL-NULL binding of the nullable `requester` / `created_by`.

```bash
./gradlew :apps:agentflow-app:test \
  --tests 'com.example.agentflow.workflowexecution.application.WorkflowWorkerTest.a_human_approval_pause_survives_a_restart_without_skipping_the_side_effect' \
  --tests 'com.example.agentflow.workflowexecution.persistence.ApprovalRepositoryTest' \
  --rerun-tasks
```

`./gradlew :apps:agentflow-app:check --rerun-tasks` → `BUILD SUCCESSFUL`. Unit + architecture gates (`ArchitectureBoundaryTest`(1), `PackageDependencyGraphTest`(4), `NoCyclicModuleDependenciesTest`(1), `AiProviderPortArchitecturalTest`(1) — `failures=0 errors=0`), no-cycles + formatting (`spotless`) + OpenAPI; no new migration, no new endpoint, no new module edge — `S11-03` does not change module ownership. Verified via the XML in `apps/agentflow-app/build/test-results/test/`.

### Documentation

- `docs/domain-glossary.md` — the **Approval node** row in §9 (human approval): "Stops the run at a safe side-effect boundary, moves it to `WAITING_FOR_APPROVAL`, and releases the worker lease while waiting."
- `docs/architecture/run-state-machine.md` — transition 2 (`RUNNING → WAITING_FOR_APPROVAL`, `step.approval-needed`): "The worker lease is released while the run waits."
- `docs/architecture/step-state-machine.md` — the step-level `WAITING_FOR_APPROVAL` row: "the run's lease is released while it awaits a human decision."
- `docs/devlog/sprint-11-human-approval-mcp-security.md` — this section (newest-first, above S11-02).
- `CHANGELOG.md` — an entry in `[Unreleased] > Added`.

### Risks / follow-up

- The release is best-effort and fenced on `lease_epoch`; a `ConflictException` during the release is logged, never failed — the pause is durable regardless.
- The resume (S11-04) is the approve/reject endpoint (`POST /api/v1/runs/{runId}/approvals/{approvalId}`, scope `run:approve`), idempotent (`Idempotency-Key`), binding the decision to the fingerprint (glossary §9).

---

## S11-02 — durable approvals schema

**Task (P0):** create the approvals schema — a durable registry of human-approval requests for
dangerous (WRITE / EXTERNAL_SIDE_EFFECT) actions (S11-01); persist run/step, the action summary,
the payload fingerprint, the requester, the status, the decision (actor/time/comment) and expiry,
and keep secrets out of the approval text.

**Deliverable:** migration `V3_3_22__create_approvals.sql` (owner: `workflowexecution`).

### What changed

- **`approvals`** — one row per approval request at a side-effect boundary (the `HUMAN_APPROVAL`
  node). Columns follow glossary §9: `id`, `tenant_id` (FK `tenants`), `run_id` (FK
  `workflow_runs`), `step_key` (TEXT, like `workflow_step_runs.step_key`), `status`, `action`,
  `payload_fingerprint`, `requester`, `decision_actor`, `decision_at`, `decision_comment`,
  `expiry`; plus `version` (optimistic lock, invariant 4) and `updated_at` (mutable row,
  `database-conventions.md` S03-06 — as `workflow_runs`).
- **`CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED','CANCELLED'))`** — binds the
  status vocabulary at the storage layer (`approvals_status_check`).
- **`UNIQUE (run_id, step_key) WHERE status = 'PENDING'`** (`approvals_run_step_pending`) — a
  partial unique index: a step cannot have two active (PENDING) approvals at once. This is the
  task's acceptance — two active conflicting approvals on one step cannot arise by accident, even
  under a race or a duplicate event (at-least-once, ADR-003).
- Access index `approvals_run_created_at_idx` (a run's approvals in append order).
- No secrets, no full prompt, no arguments and no payload on the row — only `action` (a redacted
  action summary) and `payload_fingerprint` (a hash of the arguments, not the arguments
  themselves) (invariant 8).

### Why

- **Ownership (module-map edge):** `approvals` belongs to `workflowexecution` (see
  `apps/agentflow-app/src/main/java/com/example/agentflow/workflowexecution/AGENTS.md`). The FK
  to `workflow_runs` is allowed — it is the same owning module (unlike `tool_invocations`, where
  a cross-module FK is forbidden).
- **Tenant isolation (invariant 1):** `tenant_id` on the row + FK; `run_id` is already
  tenant-scoped (`workflow_runs.tenant_id`), so another tenant's step is indistinguishable from a
  missing one.
- **Durability (invariant 4):** `version` for the conditional update; the decision is written in
  the same transaction as the status transition (S11-04).
- **Consistency (ADR-002/003/008):** PostgreSQL is the source of truth; at-least-once, never
  exactly-once; forward-only migration with `IF NOT EXISTS`.

### Verification

The migration is applied against a real PostgreSQL (`pgvector/pgvector:0.8.6-pg18`, the same image
as Testcontainers):

```bash
docker compose --file infra/compose/docker-compose.yml --profile core up -d
./gradlew :apps:agentflow-app:flywayMigrate --console=plain
```

`now at version v3.3.22`. The schema is checked on the live DB (columns, `approvals_status_check`,
FK, `approvals_run_step_pending WHERE status='PENDING'`, the table comment). The acceptance is
proven by inserts in one rolled-back transaction:

- two `PENDING` on one `(run_id, step_key)` → `duplicate key value violates unique constraint
  "approvals_run_step_pending"`;
- the same step, a different run — accepted (the constraint is bound to `(run, step)`);
- `tenant_id` NULL → not-null violation; `run_id` → FK violation; `status` → CHECK violation;
- a valid `PENDING` is inserted, then the transaction is rolled back (the volume stays clean: 0
  rows).

`./gradlew :apps:agentflow-app:check --rerun-tasks` → `BUILD SUCCESSFUL`. The architecture gates
(`ArchitectureBoundaryTest`(1), `PackageDependencyGraphTest`(4),
`NoCyclicModuleDependenciesTest`(1), `AiProviderPortArchitecturalTest`(1) — `failures=0 errors=0`,
verified via the XML in `apps/agentflow-app/build/test-results/test/`).

### Documentation

- `docs/domain-glossary.md` — the `approvals` row in §9 (human approval): the canonical columns
  (+`version`/`updated_at`) + the partial unique index.
- `docs/devlog/sprint-11-human-approval-mcp-security.md` — this section (newest-first, above
  S11-01).
- `CHANGELOG.md` — an entry in `[Unreleased] > Added`.

### Risks / follow-up

- `version`/`updated_at` were added per `database-conventions.md` (mutable row); if a decision will
  never mutate the row in the future, the question of whether to revisit it (it affects the
  glossary) stays open.
- The durable approval pause (`WAITING_FOR_APPROVAL`) is done — [S11-03](#s11-03-durable-approval-pause). The next steps (S11-04…S11-06): the approve/reject endpoint (S11-04), expiry, binding the decision to the fingerprint, the audit, and the MCP allowlist / risk classification / gateway hardening (S11-05…S11-06).

---

## S11-01 — tool risk classification: a closed, deny-by-default vocabulary owned by the catalogue

**Task (P0):** classify tools by risk (`READ_ONLY`, `WRITE`, `EXTERNAL_SIDE_EFFECT`); an unknown risk class is denied by default.

**Deliverable:** tool risk policy — a closed risk-class vocabulary, defined once in the owning module, with deny-by-default.

### What changed

- **Acceptance test** — `ToolRiskClassificationAvailabilityTest` (`toolexecution.application`). No new files are created in the repository; it is the only new test. It checks availability at the `ToolRegistry` level (the single source of "which tools are registered"):
  - a classified tool is available for each of the three closed risk classes;
  - a tool without a risk class (`null`) is rejected at `ToolDescriptor` construction (the contract `IllegalArgumentException`), so it cannot reach the registry → it is not available;
  - the registry holds no unclassified tool (`size()==0`, `resolve()` is empty).

- **ADR-017** — `docs/adr/ADR-017-tool-risk-classification.md`. The closed deny-by-default risk-class vocabulary: `READ_ONLY` / `WRITE` / `EXTERNAL_SIDE_EFFECT`, defined in the owning module `agentcatalog`, an unknown risk class is not available (`ToolRiskClass.valueOf` throws), `ToolDescriptor` requires a non-`null` risk class (rejects at construct time), `ToolInvocationPolicy` carries `REASON_UNKNOWN_RISK_CLASS` as a defensive fail-safe for the DB-load path. A tool without a risk class cannot be registered → it is not available.

### Why

- **Distrust of the model (invariant 7, ADR-006):** the model is an untrusted planner, not an authorization authority; the risk is decided by the backend, not the model.
- **Closed vocabulary:** without a closed set, "unknown" is meaningless; `valueOf` rejects anything outside the three values.
- **Ownership:** `agentcatalog` owns "which tools exist and how dangerous they are" (module-map edge 3); `toolexecution` only reads the risk class.
- **Verification (invariant 3):** classification is a closed, cross-module, persistent set — so extending it is a breaking change and requires an ADR.

### Verification

```bash
./gradlew :apps:agentflow-app:test \
  --tests 'com.example.agentflow.toolexecution.application.ToolRiskClassificationAvailabilityTest' \
  --tests 'com.example.agentflow.agentcatalog.domain.ToolRiskClassTest' \
  --tests 'com.example.agentflow.toolexecution.domain.ToolDescriptorTest' \
  --tests 'com.example.agentflow.toolexecution.application.ToolRegistryTest' \
  --tests 'com.example.agentflow.toolexecution.application.ToolInvocationPolicyTest' \
  --tests 'com.example.agentflow.toolexecution.application.ToolAuthoringGuideExamplesTest' \
  --rerun-tasks
```

`BUILD SUCCESSFUL`. `ToolRiskClassificationAvailabilityTest` — 2 tests, both pass (verified via the XML in `apps/agentflow-app/build/test-results/test/`), mtime is current. The tests are DB-free: `ToolRegistry` and `ToolDescriptor` are pure values.

### Documentation

- `docs/adr/ADR-017-tool-risk-classification.md` — new (ADR-017).
- `docs/adr/README.md` — a row in the ADR register (ADR-017, Accepted, 2026-11-14).
- `docs/architecture/README.md` — a row in "Current architectural constraints" (ref. ADR-017).
- `docs/adr/000-template.md`, `docs/adr/README.md` — the ADR template (in use).
- `CHANGELOG.md` — an entry in `[Unreleased] > Added`.

### Risks / follow-up

- Opening a risk class is a breaking change of the closed vocabulary (a new variant + ADR + updating `reason`/taxonomies).
- `unknown_risk_class` in `ToolInvocationPolicy` — a defensive fail-safe: the constructor already rejects an unclassified descriptor, so a valid descriptor never reaches it. It must not be removed.
- The next steps (S11-02…S11-06): the human-approval lifecycle for WRITE/EXTERNAL_SIDE_EFFECT, the approvals store, tool allowlist enforcement, MCP tool allowlist + risk classification, MCP gateway hardening.
