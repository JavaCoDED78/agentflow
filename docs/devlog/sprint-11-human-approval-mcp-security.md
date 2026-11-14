# Sprint 11 — human approval, MCP and security of agent actions

## Contents

- [S11-01](#s11-01-tool-risk-classification-a-closed-deny-by-default-vocabulary-owned-by-the-catalogue) — tool risk classification policy (S11-01)
- [S11-02](#s11-02-durable-approvals-schema) — durable approvals schema (S11-02)
- [S11-03](#s11-03) — future: approvals store, consent lifecycle and audit (append-only)
- [S11-04](#s11-04) — future: tool allowlist enforcement + permission checks on agent actions
- [S11-05](#s11-05) — future: MCP tool allowlist and risk classification
- [S11-06](#s11-06) — future: MCP gateway hardening (transport, secrets, telemetry)

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
- The next steps (S11-03…S11-06): the durable approval pause (`WAITING_FOR_APPROVAL`), the
  approve/reject endpoint, expiry, binding the decision to the fingerprint, and the audit.

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
