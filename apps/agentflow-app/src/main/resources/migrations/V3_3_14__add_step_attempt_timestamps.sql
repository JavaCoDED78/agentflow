-- S07-06 · workflow_step_runs.attempt_started_at · owner: workflowexecution
--
-- Backs the attempt lifecycle required by invariant 6 ("an external call is not exactly-once")
-- and ADR-011 ("attempt persistence + history so the crash window is diagnosable", S07-06 /
-- S07-14). Before the worker performs the external side effect it moves the step to RUNNING and
-- records here the moment the attempt began; when the attempt reaches a terminal outcome the
-- marker is cleared. A row that is still RUNNING with a non-NULL marker but with no terminal
-- event recorded after the "attempt started" transition is the crash window: the worker died
-- mid-attempt, so the outcome is ambiguous and the attempt must be re-claimed, never assumed to
-- have failed (invariant 6).
--
-- The marker is nullable so it carries no meaning when no attempt has begun (PENDING / READY) and
-- after the attempt finishes (terminal). The step already stores the attempt number in `attempt`
-- (this is the attempt identity); this column adds the "when", which is the part required to
-- diagnose the crash window. Full per-attempt history with worker instance and duration is S07-14.
--
-- IF NOT EXISTS keeps Testcontainers rebuilds and the controlled apply step quiet (database-migrations.md,
-- rule 5). Forward-only (ADR-008).
ALTER TABLE workflow_step_runs
    ADD COLUMN IF NOT EXISTS attempt_started_at TIMESTAMPTZ;

COMMENT ON COLUMN workflow_step_runs.attempt_started_at IS
    'S07-06 · workflowexecution · when the current attempt began: set to now() when the step enters RUNNING (before the external side effect), cleared to NULL on a terminal outcome (SUCCEEDED/FAILED/CANCELLED); NULL means no attempt has started or the attempt has finished. Enables diagnosing the crash window between "attempt started" and "outcome recorded" (invariant 6 / ADR-011).';
