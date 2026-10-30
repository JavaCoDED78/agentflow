-- S06-06 · workflow_runs.idempotency_fingerprint · owner: workflowexecution
--
-- POST /api/v1/runs is idempotent under an Idempotency-Key header (invariant 11, ADR-003 /
-- ADR-004). The key is stored in workflow_runs.idempotency_key (S06-05) scoped to the tenant, so a
-- repeated create for (tenant_id, idempotency_key) can never produce a second run. A repeat with the
-- SAME request payload must return the original run, while a repeat with a DIFFERENT payload under
-- the same key is a permanent conflict (409) rather than a silent overwrite — this migration adds
-- the payload fingerprint that makes that distinction observable.
--
-- The fingerprint is the lowercase-hex SHA-256 of the request payload, stored alongside the run so a
-- replay can compare it against the incoming payload. It is opaque (no tenant id, no secret, no full
-- document/prompt — invariant 8) and never used as authorization. It is nullable: a create with no
-- Idempotency-Key stores NULL, so only keyed creates participate in dedup / mismatch detection.
ALTER TABLE workflow_runs ADD COLUMN IF NOT EXISTS idempotency_fingerprint TEXT;

COMMENT ON COLUMN workflow_runs.idempotency_fingerprint IS
    'Lowercase-hex SHA-256 of the create-run request payload (S06-06); NULL when no Idempotency-Key was supplied. A same-key replay is accepted only when this matches the incoming payload, otherwise 409.';
