-- S05-08 · workflow_runs, workflow_step_runs · owner: workflowexecution
--
-- The durable execution state of a workflow run and its steps (glossary §5, plan §8.1).
-- One owning module (`workflowexecution`) owns both tables; the run is the top-level
-- aggregate and every step references it through `run_id`.
--
-- workflow_runs: one row per workflow execution against a pinned, validated version
-- (glossary "Run"). `id` is the stable system UUID primary key — this is the canonical
-- `runId` surfaced in API responses. `tenant_id` scopes every row to one isolated
-- organization (tenant isolation, ADR-007) and is a foreign key to `tenants`; the run
-- `status` is a lifecycle state managed by the single state machine (run-state-machine.md,
-- S05-01) and set only through the transition service (S05-11) — never from a controller.
-- `workflow_version_id` pins the immutable version a run executes; another tenant's version
-- is indistinguishable from a missing one through the parent's `tenant_id` (ADR-007).
-- `idempotency_key` (nullable) lets a repeated create-run return the same run (S06-06); the
-- `unique (tenant_id, idempotency_key)` guard deduplicates only non-null keys. `attempt` is
-- persisted before an external call and bumped on retry; an ambiguous external outcome is
-- marked, never treated as exactly-once (ADR-003). `input_ref` / `result_ref` hold an
-- object-storage (Garage) reference (link + checksum) for the payloads — the big inputs and
-- the final result live in Garage, and only the small reference stays in this row, so an
-- unbounded payload never lands in a normal row. `lease_owner` / `lease_until` / `lease_epoch`
-- are the recovery primitives (S07-17): a worker atomically claims a run and every write
-- re-checks the expected `lease_epoch` (fencing). `next_action_at` schedules the retry backoff.
-- `version` is the application-managed optimistic-lock counter (conditional update → 409).
-- `created_by` records the actor (nullable — `tenant_id` is the owner); `updated_at` is bumped
-- by the application on each state change.
CREATE TABLE IF NOT EXISTS workflow_runs (
    id                  UUID        PRIMARY KEY,
    tenant_id           UUID        NOT NULL REFERENCES tenants (id),
    workflow_version_id UUID        NOT NULL REFERENCES workflow_definition_versions (id),
    status              TEXT        NOT NULL DEFAULT 'QUEUED',
    idempotency_key     UUID,
    attempt             INTEGER     NOT NULL DEFAULT 1,
    input_ref           TEXT,
    result_ref          TEXT,
    lease_owner         UUID,
    lease_until         TIMESTAMPTZ,
    lease_epoch         INTEGER     NOT NULL DEFAULT 0,
    next_action_at      TIMESTAMPTZ,
    version             INTEGER     NOT NULL DEFAULT 0,
    created_by          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    CONSTRAINT workflow_runs_tenant_idempotency_key UNIQUE (tenant_id, idempotency_key)
);

COMMENT ON TABLE workflow_runs IS
    'S05-08 · workflow_runs · owner: workflowexecution · one workflow execution per pinned version; unique (tenant_id, idempotency_key); state set only through the state machine; payloads externalized via input_ref/result_ref refs.';

COMMENT ON COLUMN workflow_runs.tenant_id IS
    'Owner tenant (FK tenants.id); tenant-scoped isolation per ADR-007.';

COMMENT ON COLUMN workflow_runs.workflow_version_id IS
    'Pinned workflow version a run executes (FK workflow_definition_versions.id); immutable once activated (S05-12).';

COMMENT ON COLUMN workflow_runs.status IS
    'Run lifecycle state (QUEUED, RUNNING, WAITING_FOR_APPROVAL, RETRY_WAIT, SUCCEEDED, FAILED, CANCEL_REQUESTED, CANCELLED); managed by the state machine, not set directly.';

COMMENT ON COLUMN workflow_runs.idempotency_key IS
    'Idempotency key (nullable); non-null keys are deduplicated per tenant (S06-06).';

COMMENT ON COLUMN workflow_runs.attempt IS
    'Execution attempt number; persisted before an external call and bumped on retry (ADR-003).';

COMMENT ON COLUMN workflow_runs.lease_epoch IS
    'Fencing counter; every worker write re-checks the expected epoch so a stale worker cannot overwrite the current owner.';

COMMENT ON COLUMN workflow_runs.version IS
    'Optimistic-lock counter; bumped on each successful update, mapped to 409 on conflict.';

-- One index per real lookup path: a tenant's run list, scoped and most-recent-first.
CREATE INDEX IF NOT EXISTS workflow_runs_tenant_created_at_idx
    ON workflow_runs (tenant_id, created_at);


-- workflow_step_runs: one row per step execution within a run (glossary "Step").
-- `id` is the system UUID primary key. `run_id` references the run (FK workflow_runs.id);
-- tenant isolation flows through the parent's `tenant_id`, so another tenant's step is
-- indistinguishable from a missing one. `step_key` names the graph node a step runs;
-- `unique (run_id, step_key, attempt)` makes each (step, attempt) pair addressable.
-- `status` is the step lifecycle state (step-state-machine.md, S05-02). `input_hash`
-- fingerprints the step input for checkpoint dedup; `output_ref` holds the object-storage
-- (Garage) reference to the result, again keeping large payloads out of the row.
-- `error_code` / `error_summary` record a failed step (bounded, redacted text — never a
-- full prompt or document). `lease_epoch` fences worker writes; `created_by` records the
-- actor (nullable). No optimistic-lock `version` on steps — the lease epoch is the fencing
-- primitive (glossary shape).
CREATE TABLE IF NOT EXISTS workflow_step_runs (
    id            UUID        PRIMARY KEY,
    run_id        UUID        NOT NULL REFERENCES workflow_runs (id),
    step_key      TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'PENDING',
    attempt       INTEGER     NOT NULL DEFAULT 1,
    input_hash    TEXT,
    output_ref    TEXT,
    error_code    TEXT,
    error_summary TEXT,
    lease_epoch   INTEGER     NOT NULL DEFAULT 0,
    created_by    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    CONSTRAINT workflow_step_runs_run_step_attempt UNIQUE (run_id, step_key, attempt)
);

-- One index per real lookup path: a run's step list, scoped and most-recent-first.
CREATE INDEX IF NOT EXISTS workflow_step_runs_run_created_at_idx
    ON workflow_step_runs (run_id, created_at);

COMMENT ON TABLE workflow_step_runs IS
    'S05-08 · workflow_step_runs · owner: workflowexecution · one step execution per (run, step_key, attempt); unique (run_id, step_key, attempt); tenant isolation via the parent run.';

COMMENT ON COLUMN workflow_step_runs.run_id IS
    'Run this step belongs to (FK workflow_runs.id); tenant isolation flows through the parent run.';

COMMENT ON COLUMN workflow_step_runs.status IS
    'Step lifecycle state (PENDING, READY, RUNNING, RETRY_WAIT, WAITING_FOR_APPROVAL, SUCCEEDED, FAILED, SKIPPED, CANCELLED); managed by the state machine, not set directly.';

COMMENT ON COLUMN workflow_step_runs.error_summary IS
    'Human-readable failure summary; redacted (never a full prompt, document or secret).';
