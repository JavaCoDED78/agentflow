-- S03-10 · workflow_definitions.version · owner: workflowdefinition
--
-- Optimistic-lock counter for the mutable `workflow_definitions` row (glossary §4,
-- `database-conventions.md`, *Optimistic versioning*). This is a concurrency
-- counter, NOT the monotonic business `version` of `workflow_definition_versions`
-- (an addressable identity): it starts at 0, is bumped inside the conditional
-- UPDATE, and a stale update (a row changed since it was read) affects zero rows,
-- which the repository surfaces as 409 Conflict (S03-10).
--
-- Additive and idempotent (IF NOT EXISTS): the table is empty at this point in the
-- schema history, but the guard keeps Testcontainers rebuilds and re-runs quiet.
ALTER TABLE workflow_definitions
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN workflow_definitions.version IS
    'Optimistic-lock counter; starts at 0, bumped on each successful update, mapped to 409 on conflict (S03-10).';
