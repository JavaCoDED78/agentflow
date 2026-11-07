-- S08-13 · tool_invocations · owner: toolexecution
--
-- The durable tool invocation history (S08-13): one row per tool call attempt that ran through
-- the bounded executor, so an operator can determine WHICH tool executed and HOW its call
-- concluded — without revealing secrets (the S08-13 acceptance). The row is metadata only: it
-- never carries the call arguments, the tool output payload, a prompt or a document (invariant 8).
-- The output is referenced, not stored: output_reference points at the owning step run's
-- checkpoint (workflowexecution's workflow_step_runs.output_inline), so the payload has exactly
-- one owner (workflowexecution) and this table stays a lean index.
--
-- Columns:
--   id             the invocation record id (UUID, assigned by the domain factory).
--   tenant_id      the owning tenant, copied from the verified ToolExecutionContext (invariant 1:
--                  the tenant comes from the verified security context / re-checked stored run,
--                  never from a model argument). It lives on the row (unlike
--                  workflow_step_attempt_history, which JOINs its parent run) because toolexecution
--                  may not JOIN workflowexecution's tables — every table has exactly one owning
--                  module, and this one is ours. The tenant predicate is part of every read.
--   run_id         the run the call belongs to (from the verified context); NOT a foreign key —
--                  workflow_runs is owned by workflowexecution, and a cross-module FK would couple
--                  the schemas.
--   step_run_id    the step run the call belongs to (nullable — the context may carry none yet);
--                  TEXT, not UUID: the toolexecution ToolExecutionContext carries the run model's
--                  step identifier (e.g. 'step-1'), which is a name, not a database key.
--   tool_name      the tool's registered name (the (name, version) identity the registry
--                  deduplicates on), e.g. 'calculator'.
--   tool_version   the tool's registered version, e.g. '1.0.0'.
--   risk_class     the descriptor's risk class at call time (READ_ONLY / WRITE /
--                  EXTERNAL_SIDE_EFFECT) — the side-effect audit decision reads this column.
--   attempt        the per-call attempt number (1-based), supplied by the caller's retry loop.
--   outcome        the bounded outcome class: SUCCEEDED, THROTTLED (rejected under a concurrency
--                  cap, the call never started), TIMED_OUT (ambiguous: the side effect may have
--                  completed — invariant 6 / ADR-003), FAULTED (the tool raised its own fault).
--   latency_ms     wall time the admitted call occupied, in milliseconds (a rejected call is ~0).
--   output_reference  'step-run:<step-id>' pointer to the checkpoint holding the output; NULL when
--                  the call produced no output (throttled / timed out / faulted).
--   error_class    the safe machine-readable token of the failure (e.g. 'concurrency_limited',
--                  'tool_timeout', 'CalculationException', or a stable reason code such as
--                  'division-by-zero'); never the raw error text.
--   error_summary  a short redacted reason (the faulting type + its stable reason code, capped by
--                  the writer); never a secret, a full prompt or a document (invariant 8).
--   created_at     instant the record was appended (timestamptz, UTC).
--
-- IF NOT EXISTS keeps Testcontainers rebuilds and the controlled apply step quiet
-- (database-migrations.md, rule 5). Forward-only (ADR-008).

CREATE TABLE IF NOT EXISTS tool_invocations (
    id                UUID         PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    run_id            UUID         NOT NULL,
    step_run_id       TEXT,
    tool_name         TEXT         NOT NULL,
    tool_version      TEXT         NOT NULL,
    risk_class        TEXT         NOT NULL,
    attempt           INTEGER      NOT NULL,
    outcome           TEXT         NOT NULL,
    latency_ms        BIGINT       NOT NULL,
    output_reference  TEXT,
    error_class       TEXT,
    error_summary     TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE tool_invocations IS
    'S08-13 · toolexecution · one row per tool call attempt through the bounded executor: tool id/version, attempt, latency, outcome class, output reference; metadata only, never arguments or payload (invariant 8)';

COMMENT ON COLUMN tool_invocations.tenant_id IS
    'S08-13 · toolexecution · owning tenant from the verified call context; every read filters on it (invariant 1)';

COMMENT ON COLUMN tool_invocations.attempt IS
    'S08-13 · toolexecution · per-call attempt number (1-based), supplied by the caller retry loop';

COMMENT ON COLUMN tool_invocations.outcome IS
    'S08-13 · toolexecution · bounded outcome class: SUCCEEDED / THROTTLED / TIMED_OUT / FAULTED';

COMMENT ON COLUMN tool_invocations.output_reference IS
    'S08-13 · toolexecution · step-run:<step-id> pointer to the owning step run checkpoint; NULL when the call produced no output';

COMMENT ON COLUMN tool_invocations.error_class IS
    'S08-13 · toolexecution · safe failure token (throttle reason, tool fault type or stable reason code); never raw error text';

-- The tenant-scoped investigation read: a run's invocations in append order.
CREATE INDEX IF NOT EXISTS tool_invocations_tenant_run_created
    ON tool_invocations (tenant_id, run_id, created_at);
