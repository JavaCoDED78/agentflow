-- S11-02 · approvals · owner: workflowexecution
--
-- The durable, payload-bound human-approval pause point for a side effect (glossary §9, plan
-- Sprint 11, S11-02). workflowexecution owns this table — the run/step state machine is the same
-- owner as workflow_runs and workflow_step_runs. One row per approval request created at a
-- side-effect boundary (the HUMAN_APPROVAL node); a decision (approve / reject) or an expiry moves
-- it to a terminal status. The row records WHAT needs approving and WHO decided, never the secret
-- payload (invariant 8):
--
--   id                the approval record id (UUIDv7 primary key).
--   tenant_id         the owning tenant (FK tenants.id); every row is tenant-scoped (invariant 1).
--   run_id            the run this approval belongs to (FK workflow_runs.id); tenant isolation flows
--                     through the parent run, exactly as workflow_step_runs scopes it.
--   step_key          the graph node / step the request is gating (TEXT — the step name, matching
--                     workflow_step_runs.step_key, not the step_run UUID).
--   status            the approval lifecycle state (PENDING / APPROVED / REJECTED / EXPIRED /
--                     CANCELLED); a CHECK bounds it. At most one PENDING (active) approval per
--                     (run_id, step_key) — see the partial unique index below, the S11-02 acceptance:
--                     two active conflicting approvals on one step cannot arise by accident.
--   action            a short, human-readable summary of the side effect awaiting approval (redacted
--                     — never a secret, a full prompt or a document; invariant 8).
--   payload_fingerprint a hash of the exact tool arguments / tool / version the request pins; the
--                     decision is bound to this value (a different argument set requires a new
--                     approval). It is a digest, never the arguments themselves.
--   requester         the actor who triggered the gated side effect (who requested the approval).
--   decision_actor    nullable TEXT — the actor who made the decision.
--   decision_at       nullable TIMESTAMPTZ — when the decision was made.
--   decision_comment  nullable TEXT — a free-text decision note (redacted; never a secret).
--   expiry            nullable TIMESTAMPTZ — the instant after which a decision is no longer valid.
--   version           application-managed optimistic-lock counter (conditional update -> 409); the
--                     status transition (S11-04 / S11-05) bumps it in the same transaction as the
--                     decision (invariant 4).
--   created_by        nullable TEXT — the actor that created the request.
--   created_at        immutable creation instant (timestamptz, UTC).
--   updated_at        nullable; bumped by the application when the decision is applied.
--
-- No secrets, no full prompts, no arguments or payload on the row: only the summary, the fingerprint
-- and the decision metadata (invariant 8). IF NOT EXISTS keeps Testcontainers rebuilds and the
-- controlled apply step quiet (database-migrations.md, rule 5). Forward-only (ADR-008).

CREATE TABLE IF NOT EXISTS approvals (
    id                  UUID         PRIMARY KEY,
    tenant_id           UUID         NOT NULL REFERENCES tenants (id),
    run_id              UUID         NOT NULL REFERENCES workflow_runs (id),
    step_key            TEXT         NOT NULL,
    status              TEXT         NOT NULL DEFAULT 'PENDING',
    action              TEXT         NOT NULL,
    payload_fingerprint TEXT         NOT NULL,
    requester           TEXT         NOT NULL,
    decision_actor      TEXT,
    decision_at         TIMESTAMPTZ,
    decision_comment    TEXT,
    expiry              TIMESTAMPTZ,
    version             INTEGER      NOT NULL DEFAULT 0,
    created_by          TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    CONSTRAINT approvals_status_check
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED'))
);

COMMENT ON TABLE approvals IS
    'S11-02 · workflowexecution · durable payload-bound human-approval pause point for a side effect; unique (run_id, step_key) WHERE PENDING -> one active approval per step (S11-02 acceptance); decision bound to payload_fingerprint; no secrets/payload on the row (invariant 8)';

COMMENT ON COLUMN approvals.tenant_id IS
    'S11-02 · workflowexecution · owning tenant (FK tenants.id); tenant-scoped isolation per ADR-007';

COMMENT ON COLUMN approvals.run_id IS
    'S11-02 · workflowexecution · run this approval gates (FK workflow_runs.id); tenant isolation flows through the parent run';

COMMENT ON COLUMN approvals.step_key IS
    'S11-02 · workflowexecution · the step / graph node the request is gating (TEXT, matches workflow_step_runs.step_key)';

COMMENT ON COLUMN approvals.status IS
    'S11-02 · workflowexecution · approval lifecycle (PENDING/APPROVED/REJECTED/EXPIRED/CANCELLED); bounded by CHECK; transitions via the state machine (S11-04/S11-05)';

COMMENT ON COLUMN approvals.action IS
    'S11-02 · workflowexecution · short redacted summary of the gated side effect (never a secret, full prompt or document; invariant 8)';

COMMENT ON COLUMN approvals.payload_fingerprint IS
    'S11-02 · workflowexecution · digest of the exact tool arguments/tool/version the request pins; the decision is bound to it (a different argument set needs a new approval); never the arguments themselves';

COMMENT ON COLUMN approvals.requester IS
    'S11-02 · workflowexecution · actor who triggered the gated side effect and requested the approval';

COMMENT ON COLUMN approvals.decision_actor IS
    'S11-02 · workflowexecution · actor who made the decision (nullable while PENDING)';

COMMENT ON COLUMN approvals.decision_at IS
    'S11-02 · workflowexecution · decision instant (nullable while PENDING)';

COMMENT ON COLUMN approvals.decision_comment IS
    'S11-02 · workflowexecution · free-text decision note, redacted (never a secret)';

COMMENT ON COLUMN approvals.expiry IS
    'S11-02 · workflowexecution · instant after which a decision is no longer valid (nullable); expiry handled by the state machine (S11-05)';

COMMENT ON COLUMN approvals.version IS
    'S11-02 · workflowexecution · optimistic-lock counter; bumped on each successful decision update, mapped to 409 on conflict (invariant 4)';

COMMENT ON COLUMN approvals.created_by IS
    'S11-02 · workflowexecution · actor that created the request (nullable when begun without an actor)';

COMMENT ON COLUMN approvals.updated_at IS
    'S11-02 · workflowexecution · last-mutation instant; bumped by the application when the decision is applied (nullable until the first update)';

-- Acceptance (S11-02): at most one ACTIVE (PENDING) approval per step. The partial unique index makes
-- a second PENDING request on the same (run_id, step_key) a unique violation, so two active conflicting
-- approvals on one step cannot arise by accident, even under a race or a duplicate event (at-least-once,
-- ADR-003). run_id is tenant-scoped (FK workflow_runs -> tenant_id), so the pair is already
-- tenant-isolated; another tenant's step is indistinguishable from a missing one (invariant 1).
CREATE UNIQUE INDEX IF NOT EXISTS approvals_run_step_pending
    ON approvals (run_id, step_key)
    WHERE status = 'PENDING';

-- One index per real lookup path: a run's approval requests and decisions, in append order (audit /
-- approve endpoint).
CREATE INDEX IF NOT EXISTS approvals_run_created_at_idx
    ON approvals (run_id, created_at);
