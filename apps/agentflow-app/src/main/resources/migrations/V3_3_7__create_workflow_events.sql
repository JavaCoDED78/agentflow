-- S05-09 · workflow_events · owner: messaging
--
-- Append-only history of run/step transitions (glossary §6, plan §8.1). One row per
-- domain fact appended when a run or step changes state; the single transition service
-- (S05-11) appends the row in the same transaction as the state change, so a run's
-- history can be replayed to reconstruct its state (the S05-09 acceptance criterion).
--
-- Ownership: `messaging` owns this append-only table (module-map §13, glossary §6,
-- run-state-machine.md:119), the same way `audit` owns the append-only `audit_events`
-- table (S04-12). The row is appended by the `workflowexecution` transition service,
-- which reaches `messaging` through its allowed edge (arrow 13 in the module-map), not
-- by writing the table directly.
--
-- Columns (plan §8.1, glossary §6):
--   id             stable system UUID primary key (UUIDv7 by convention); the canonical
--                  history row id.
--   tenant_id      scopes every row to one isolated tenant (tenant isolation, ADR-007)
--                  and is a foreign key to `tenants`. Unlike `workflow_step_runs`, the
--                  tenant is stored here directly: `messaging` owns `workflow_events` and
--                  scopes its own reads by `tenant_id`; another tenant's row is
--                  indistinguishable from a missing one (404/403).
--   run_id         the run every event is scoped to (FK `workflow_runs.id`). NOT NULL: a
--                  step-level event still belongs to a run, so the (run_id, occurred_at)
--                  index covers every event of a run and the run can be replayed in one
--                  ordered read.
--   step_run_id    nullable FK to `workflow_step_runs.id`; set only for step-level
--                  events, null for run-level events.
--   event_type     the transition / trigger name (reason + event type), e.g. the run
--                  state the transition moved to or the command that drove it. Stored as
--                  TEXT (the vocabulary is fixed by the state machine, S05-10); a CHECK
--                  is left to that task rather than hard-coding a set before it exists.
--   payload_redacted redacted free-text metadata (prior -> new status, reason, actor).
--                  Never a secret, a full prompt or a full document (invariant 8).
--   occurred_at    immutable append-only anchor (timestamptz, UTC); set once on insert.
--
-- Append-only by construction: no unique constraint (one row per transition, no
-- duplicate), no optimistic-lock `version`, no `updated_at` — the row is never updated
-- or deleted, and there is no update/delete API over the history. `IF NOT EXISTS` keeps
-- Testcontainers rebuilds and re-runs quiet; the migration is forward-only (ADR-008).
CREATE TABLE IF NOT EXISTS workflow_events (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenants (id),
    run_id         UUID        NOT NULL REFERENCES workflow_runs (id),
    step_run_id    UUID        REFERENCES workflow_step_runs (id),
    event_type     TEXT        NOT NULL,
    payload_redacted TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE workflow_events IS
    'S05-09 · workflow_events · owner: messaging · append-only history of run/step transitions; one row per domain fact; replay a run by (run_id, occurred_at); never updated or deleted (no update/delete API).';

COMMENT ON COLUMN workflow_events.tenant_id IS
    'Owner tenant (FK tenants.id); tenant-scoped isolation per ADR-007; stored directly because messaging owns this table.';

COMMENT ON COLUMN workflow_events.run_id IS
    'Run every event is scoped to (FK workflow_runs.id); NOT NULL so a run is replayable in one ordered read; tenant isolation flows through run_id + tenant_id.';

COMMENT ON COLUMN workflow_events.step_run_id IS
    'Step this event belongs to (nullable FK workflow_step_runs.id); null for run-level events; set for step-level events.';

COMMENT ON COLUMN workflow_events.event_type IS
    'Transition / trigger name (reason + event type), e.g. the run state a transition moved to. TEXT; the vocabulary is fixed by the state machine (S05-10).';

COMMENT ON COLUMN workflow_events.payload_redacted IS
    'Redacted metadata (prior -> new status, reason, actor); never a secret, full prompt or full document.';

-- One index per real lookup path: a run's complete event history, most-recent-first, so
-- the run state can be replayed by reading one run's events ordered by occurred_at.
CREATE INDEX IF NOT EXISTS workflow_events_run_occurred_at_idx
    ON workflow_events (run_id, occurred_at);
