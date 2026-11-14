-- S11-04 · approvals.idempotency_key / idempotency_fingerprint · owner: workflowexecution
--
-- The approve endpoint (POST /api/v1/runs/{runId}/approvals/{approvalId}, glossary §9) is
-- idempotent two ways. By state: a repeat approve on an already-APPROVED approval replays the
-- recorded decision (the conditional update is guarded on status = 'PENDING', so a decision can
-- never be written twice). By key: a client-chosen Idempotency-Key (invariant 11 lists approval
-- next to create-run / upload-finalize) is stored with the decision, and the
-- unique (tenant_id, idempotency_key) guard — exactly the workflow_runs convention (S06-05 /
-- S06-06) — makes a repeat under the same key answerable: the stored idempotency_fingerprint
-- (the lowercase-hex SHA-256 of the canonical {approvalId, decision, comment} payload) proves a
-- replay (same fingerprint) or a payload change on a reused key (409 conflict, never a silent
-- overwrite). Both columns are nullable: a decision made without a key stores neither, and the
-- state-based replay still answers. The fingerprint is an opaque digest, never a secret, a full
-- prompt or a document (invariant 8), and never an authorization source (invariant 1).
--
-- Forward-only (ADR-008); IF NOT EXISTS keeps Testcontainers rebuilds and the controlled apply
-- step quiet (database-migrations.md, rule 5).

ALTER TABLE approvals
    ADD COLUMN IF NOT EXISTS idempotency_key UUID,
    ADD COLUMN IF NOT EXISTS idempotency_fingerprint TEXT;

COMMENT ON COLUMN approvals.idempotency_key IS
    'S11-04 · workflowexecution · client-chosen Idempotency-Key stored with the decision; unique (tenant_id, idempotency_key) answers a repeat (replay) or a payload change (409)';

COMMENT ON COLUMN approvals.idempotency_fingerprint IS
    'S11-04 · workflowexecution · lowercase-hex SHA-256 of the canonical {approvalId, decision, comment} payload the key was decided under; an opaque digest, never a secret (invariant 8)';

-- The (tenant_id, idempotency_key) guard deduplicates only non-NULL keys (PostgreSQL: NULLs never
-- conflict), so an unkeyed decision is not constrained — the state-based replay answers it.
ALTER TABLE approvals
    ADD CONSTRAINT approvals_tenant_idempotency_key UNIQUE (tenant_id, idempotency_key);
