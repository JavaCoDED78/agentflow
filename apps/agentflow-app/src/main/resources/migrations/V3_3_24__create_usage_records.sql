-- S12-01 · usage_records · owner: usage
--
-- The durable token/cost metering table (glossary §10, plan Sprint 12, S12-01).
-- usage owns this table: token/cost metering, budget reservation/settlement and quotas.
-- One row per measurement of one attempt — a single model call, tool call or embedding
-- produced `input_tokens` / `output_tokens`, an estimated cost and the pricing unit and
-- currency. It is the durable record the later budget / settlement / quota tasks (S12-02,
-- S12-03, S12-04, S12-06) read and reconcile, and the audit of what a run spent.
--
-- The row pins the measurement to a SPECIFIC attempt (invariant: the record is tied to the
-- run / step / attempt that produced it): tenant_id, run_id, step_run_id and attempt are all
-- required, so a record is never a floating number but always a measurement of one concrete
-- attempt. The record is metadata only — it NEVER stores the raw prompt, the raw document or
-- a full conversation (invariant 8). It stores the token counts and the cost, never the
-- content that was measured.
--
-- Columns:
--   id                the usage record id (UUIDv7 primary key).
--   tenant_id         the owning tenant (FK tenants.id); every row is tenant-scoped (invariant
--                     1). Unlike run_id below this IS a foreign key — tenants is the shared
--                     lookup table that scopes every business row, and the FK enforces tenant
--                     existence at the storage layer (database-conventions.md, rule 5).
--   run_id            the run the measurement belongs to (NOT a foreign key — workflow_runs is
--                     owned by workflowexecution; a cross-module FK would couple the schemas,
--                     exactly as tool_invocations scopes run_id without one). A tenant-scoped
--                     read filters on it, never a JOIN.
--   step_run_id       the step run the measurement belongs to (nullable — the context may carry
--                     none yet); TEXT, not UUID, matching the step_run_id convention used by
--                     tool_invocations (the run model's step identifier is a name, not a DB key).
--   attempt           the per-attempt number (1-based) the measurement was recorded for; NOT
--                     NULL — a usage record without an attempt is not a measurement of anything
--                     concrete. This is the column that ties the record to a specific attempt.
--   usage_type        what was measured: MODEL / TOOL / EMBEDDING; bounded by a CHECK.
--   measurement       how the recorded values were produced: ESTIMATED (computed locally from
--                     the price catalogue — the pre-call reservation) or PROVIDER_REPORTED
--                     (actual values from the provider); the storage-layer expression of the
--                     "distinguish estimated from provider-reported usage" rule. DEFAULT
--                     'ESTIMATED' so a record written before a provider reply is truthful.
--   provider          the provider name (OPENAI, ANTHROPIC, FAKE, …); free TEXT — providers are
--                     open-ended, not a bounded vocabulary.
--   model             nullable TEXT — the model id, present for MODEL usage (e.g. 'gpt-4o');
--                     absent for TOOL usage.
--   tool_name         nullable TEXT — the tool name, present for TOOL usage (e.g. 'calculator');
--                     absent for MODEL usage.
--   tool_version      nullable TEXT — the tool version the call used.
--   input_tokens      nullable BIGINT — input tokens; NULL means the value was unknown (a
--                     provider reported no count), NOT zero — an unknown is recorded as unknown,
--                     never invented (S12-02).
--   output_tokens     nullable BIGINT — output tokens; NULL = unknown, per input_tokens.
--   estimated_cost    nullable NUMERIC — the estimated cost / reservation (money, NUMERIC).
--                     non-NULL when measurement = 'ESTIMATED'.
--   provider_cost     nullable NUMERIC — the provider-reported cost (money, NUMERIC).
--                     non-NULL when measurement = 'PROVIDER_REPORTED'.
--   unit              nullable TEXT — the pricing unit the cost is expressed in (e.g. 'token',
--                     '1k-tokens'); set when the price catalogue is known (S12-03).
--   currency          nullable TEXT — the cost currency (e.g. 'USD'); money is NUMERIC, never
--                     float (invariant 10, engineering-conventions.md).
--   provider_request_id nullable TEXT — the provider's own request id, for traceability /
--                     reconciliation; nullable.
--   provider_metadata nullable JSONB — any remaining provider metadata (nullable); unbounded
--                     provider metadata would live here, capped by the application.
--   created_at        immutable creation instant (timestamptz, UTC) — this row is append-only,
--                     so it carries no updated_at (database-conventions.md: updated_at only when
--                     a row is mutable and tracked).
--
-- No raw prompt, no document, no full conversation on the row — only the token counts and the
-- cost (invariant 8). IF NOT EXISTS keeps Testcontainers rebuilds and the controlled apply
-- step quiet (database-migrations.md, rule 5). Forward-only (ADR-008).

CREATE TABLE IF NOT EXISTS usage_records (
    id                  UUID         PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES tenants (id),
    run_id              UUID         NOT NULL,
    step_run_id         TEXT,
    attempt             INTEGER      NOT NULL,
    usage_type          TEXT         NOT NULL,
    measurement         TEXT         NOT NULL DEFAULT 'ESTIMATED',
    provider            TEXT         NOT NULL,
    model               TEXT,
    tool_name           TEXT,
    tool_version        TEXT,
    input_tokens        BIGINT,
    output_tokens       BIGINT,
    estimated_cost      NUMERIC,
    provider_cost       NUMERIC,
    unit                TEXT,
    currency            TEXT,
    provider_request_id TEXT,
    provider_metadata   JSONB,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT usage_records_usage_type_check
        CHECK (usage_type IN ('MODEL', 'TOOL', 'EMBEDDING')),
    CONSTRAINT usage_records_measurement_check
        CHECK (measurement IN ('ESTIMATED', 'PROVIDER_REPORTED'))
);

COMMENT ON TABLE usage_records IS
    'S12-01 · usage · durable token/cost metering: one measurement per attempt (tenant/run/step/attempt); usage_type (MODEL/TOOL/EMBEDDING) bounded by CHECK, measurement (ESTIMATED/PROVIDER_REPORTED) distinguishes the recorded source; no raw prompt / document on the row (invariant 8)';

COMMENT ON COLUMN usage_records.tenant_id IS
    'S12-01 · usage · owning tenant (FK tenants.id); tenant-scoped isolation per ADR-007 (invariant 1)';

COMMENT ON COLUMN usage_records.run_id IS
    'S12-01 · usage · run the measurement belongs to (UUID, not a cross-module FK — workflow_runs is owned by workflowexecution; a tenant-scoped read filters on it, never a JOIN)';

COMMENT ON COLUMN usage_records.step_run_id IS
    'S12-01 · usage · step run the measurement belongs to (nullable TEXT, matches tool_invocations.step_run_id); NULL when the context carries no step run';

COMMENT ON COLUMN usage_records.attempt IS
    'S12-01 · usage · per-attempt number (1-based) the measurement is tied to; NOT NULL so every record measures one concrete attempt (S12-01 acceptance)';

COMMENT ON COLUMN usage_records.usage_type IS
    'S12-01 · usage · what was measured (MODEL / TOOL / EMBEDDING); bounded by usage_records_usage_type_check';

COMMENT ON COLUMN usage_records.measurement IS
    'S12-01 · usage · how the values were produced: ESTIMATED (local estimate / reservation) or PROVIDER_REPORTED (actual provider value); DEFAULT ESTIMATED';

COMMENT ON COLUMN usage_records.provider IS
    'S12-01 · usage · provider name (open-ended TEXT, not a bounded vocabulary)';

COMMENT ON COLUMN usage_records.model IS
    'S12-01 · usage · model id for MODEL usage (nullable — absent for TOOL usage)';

COMMENT ON COLUMN usage_records.tool_name IS
    'S12-01 · usage · tool name for TOOL usage (nullable — absent for MODEL usage)';

COMMENT ON COLUMN usage_records.tool_version IS
    'S12-01 · usage · tool version the call used (nullable)';

COMMENT ON COLUMN usage_records.input_tokens IS
    'S12-01 · usage · input tokens (nullable — NULL = unknown, never invented; S12-02)';

COMMENT ON COLUMN usage_records.output_tokens IS
    'S12-01 · usage · output tokens (nullable — NULL = unknown, never invented; S12-02)';

COMMENT ON COLUMN usage_records.estimated_cost IS
    'S12-01 · usage · estimated cost / reservation (NUMERIC, money is never float); non-NULL when measurement = ESTIMATED';

COMMENT ON COLUMN usage_records.provider_cost IS
    'S12-01 · usage · provider-reported cost (NUMERIC, money is never float); non-NULL when measurement = PROVIDER_REPORTED';

COMMENT ON COLUMN usage_records.unit IS
    'S12-01 · usage · pricing unit the cost is expressed in (e.g. token, 1k-tokens); set when the price catalogue is known (S12-03)';

COMMENT ON COLUMN usage_records.currency IS
    'S12-01 · usage · cost currency (e.g. USD); money is NUMERIC, never float (invariant 10)';

COMMENT ON COLUMN usage_records.provider_request_id IS
    'S12-01 · usage · provider-side request id for traceability / reconciliation (nullable)';

COMMENT ON COLUMN usage_records.provider_metadata IS
    'S12-01 · usage · remaining provider metadata (nullable JSONB, application-capped)';

COMMENT ON COLUMN usage_records.created_at IS
    'S12-01 · usage · immutable creation instant (timestamptz, UTC); the row is append-only, so there is no updated_at';

-- One index per real lookup path: a run's usage records, tenant-scoped and in append order
-- (audit / budget reconciliation). The tenant predicate is part of every read (invariant 1),
-- so the leading column is tenant_id.
CREATE INDEX IF NOT EXISTS usage_records_tenant_run_created_idx
    ON usage_records (tenant_id, run_id, created_at);
