-- S07-14 · workflow_step_attempt_history · owner: workflowexecution
--
-- Durable per-attempt history for a run's steps (S07-14). workflow_step_runs keeps the CURRENT
-- attempt's identity (the `attempt` counter) and the crash-window marker (attempt_started_at,
-- S07-06); this table records every attempt across a run's lifetime so a failed retry is
-- investigable without viewing the secret payload. One row per (run_id, step_key, attempt):
--
--   attempt (INTEGER) — the per-step attempt number (1-based), incremented on each entry to
--       RUNNING. The unique (run_id, step_key, attempt) makes a repeated attempt a collision.
--   worker_instance (nullable TEXT) — the worker that ran this attempt (a correlation/owner
--       trace; NULL when begun without an actor).
--   outcome (nullable TEXT) — the status the attempt reached (SUCCEEDED / FAILED / RETRY_WAIT /
--       WAITING_FOR_APPROVAL / CANCELLED); NULL while the attempt is still RUNNING.
--   started_at (TIMESTAMPTZ, default now()) — when the attempt began (the step entering RUNNING,
--       before the external side effect).
--   finished_at (nullable TIMESTAMPTZ) — when the attempt concluded (NULL while in flight).
--   error_class (nullable TEXT) — the safe ErrorClass token (e.g. transient_infra) of the fault;
--       never the raw error and never the secret payload (invariant 8 / S07-14 "safe error
--       category").
--   error_summary (nullable TEXT) — the short redacted reason, bounded, never a secret or a full
--       prompt (S07-14).
--   created_at (TIMESTAMPTZ, default now()).
--
-- Tenant isolation (invariant 1): no tenant_id on this row. Every read JOINs workflow_runs and
-- filters by the parent run's tenant_id, so a run of another tenant is indistinguishable from a
-- missing one. The write joins the single @Transactional step-writer (RunStateMachineService) so
-- it commits / rolls back with the state change (invariant 3). Duration is finished_at -
-- started_at, computed by the reader (S07-14).
--
-- IF NOT EXISTS keeps Testcontainers rebuilds and the controlled apply step quiet
-- (database-migrations.md, rule 5). Forward-only (ADR-008).

CREATE TABLE IF NOT EXISTS workflow_step_attempt_history (
    id                UUID         PRIMARY KEY,
    run_id            UUID         NOT NULL REFERENCES workflow_runs (id),
    step_key          TEXT         NOT NULL,
    attempt           INTEGER      NOT NULL,
    worker_instance   TEXT,
    outcome           TEXT,
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    error_class       TEXT,
    error_summary     TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE workflow_step_attempt_history IS
    'S07-14 · workflowexecution · one row per (run_id, step_key, attempt): the durable attempt history (attempt number, start/finish, worker instance, safe error category + redacted reason); tenant-scoped via the parent run.';

COMMENT ON COLUMN workflow_step_attempt_history.attempt IS
    'S07-14 · workflowexecution · per-step attempt index (1-based), incremented on each entry to RUNNING';

COMMENT ON COLUMN workflow_step_attempt_history.worker_instance IS
    'S07-14 · workflowexecution · the worker that ran this attempt (nullable when begun without an actor)';

COMMENT ON COLUMN workflow_step_attempt_history.outcome IS
    'S07-14 · workflowexecution · the status the attempt reached (SUCCEEDED/FAILED/RETRY_WAIT/WAITING_FOR_APPROVAL/CANCELLED); NULL while still RUNNING';

COMMENT ON COLUMN workflow_step_attempt_history.error_class IS
    'S07-14 · workflowexecution · the safe ErrorClass token of the attempt fault (nullable); never the raw error or the secret payload (invariant 8)';

COMMENT ON COLUMN workflow_step_attempt_history.error_summary IS
    'S07-14 · workflowexecution · the short redacted reason for the attempt fault (nullable); never a secret or a full prompt';

-- The composite unique is the per-attempt address and the read's access path (order by step,
-- attempt within a run).
CREATE UNIQUE INDEX IF NOT EXISTS workflow_step_attempt_history_run_step_attempt
    ON workflow_step_attempt_history (run_id, step_key, attempt);
