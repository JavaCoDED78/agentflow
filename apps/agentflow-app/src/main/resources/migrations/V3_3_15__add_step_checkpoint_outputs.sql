-- S07-07 · workflow_step_runs.output_inline, output_schema_version · owner: workflowexecution
--
-- Backs the per-step checkpoint (S07-07, glossary "Checkpoint"). A completed step's result is
-- persisted in `workflow_step_runs` so that recovery can answer "is this step's result
-- still valid?" without re-executing it — the idempotency-key pattern of S07-01 extended to
-- the *output* instead of the input.
--
-- Two columns carry the checkpoint:
--   output_schema_version (nullable TEXT) — the version of the step's output schema used when
--       the output was produced. It is the "is the output still valid?" key: a cached output
--       is reusable only when the current step schema version matches (glossary "Step Schema
--       Version"; S07-07 acceptance). NULL when no output exists yet (PENDING / READY).
--   output_inline (nullable TEXT) — the step output itself, stored in-row when small. The
--       output is redacted before it is written here (invariant 8 — secrets never land in a
--       column), its length is bounded to `agentflow.checkpoint.max-inline-bytes` (default
--       256 KiB — S07-07), and a value larger than that is written to object storage instead
--       (the large-payload path, S15-06) where only `output_ref` is kept.
--
-- output_ref already exists from S05-08 and holds the object-storage (Garage) reference for
-- the large-payload path. output_ref and output_inline are mutually exclusive for the same
-- row: an inline checkpoint never also has a ref (the CHECK enforces this; the CHECK is on
-- the column pair, so a later ALTER that drops the ref path keeps working). This mirrors how
-- workflow_runs.input_ref/result_ref externalize the big payloads (S05-08), but here the
-- default path is inline because step outputs are usually small, hot data.

ALTER TABLE workflow_step_runs
    ADD COLUMN output_schema_version TEXT;

ALTER TABLE workflow_step_runs
    ADD COLUMN output_inline TEXT;

ALTER TABLE workflow_step_runs
    ADD CONSTRAINT workflow_step_runs_output_ref_only_when_no_inline
    CHECK (output_inline IS NULL OR output_ref IS NULL);

COMMENT ON COLUMN workflow_step_runs.output_schema_version IS
    'S07-07 · step checkpoint output-schema version (nullable); a cached output is reusable only when it matches the current step schema version.';

COMMENT ON COLUMN workflow_step_runs.output_inline IS
    'S07-07 · step checkpoint output stored inline (redacted, size-bounded to agentflow.checkpoint.max-inline-bytes); NULL unless the output is small enough to store in-row (the large-payload path uses output_ref only).';
