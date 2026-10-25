-- S04-12 · audit_events · owner: audit
--
-- Append-only log of security- and business-sensitive actions (glossary §10, plan §8.1):
-- definition mutations, run start/cancel, upload/read, approval, tool allow/deny/execute, ops.
-- One row per audited action. `id` is the system UUID primary key. `tenant_id` scopes every row to
-- one isolated organization (tenant isolation, ADR-007) and is a foreign key to `tenants`.
-- `actor_subject` records the actor that performed the action (never a token or credential);
-- `action` / `resource_type` / `outcome` are bounded vocabularies stored as TEXT; `resource_id`
-- references the affected resource; `correlation_id` ties the row to the running trace (it lives
-- here only, never in metric labels); `metadata_redacted` holds redacted (never raw) free text;
-- `created_at` is the immutable creation instant (timestamptz). No secrets, no full prompts.
--
-- Additive and idempotent (IF NOT EXISTS): the table does not exist yet at this point in the schema
-- history, but the guard keeps Testcontainers rebuilds and re-runs quiet.
CREATE TABLE IF NOT EXISTS audit_events (
    id                UUID        PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenants (id),
    actor_subject     TEXT        NOT NULL,
    action            TEXT        NOT NULL,
    resource_type     TEXT        NOT NULL,
    resource_id       UUID,
    outcome           TEXT        NOT NULL,
    correlation_id    UUID,
    metadata_redacted TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE audit_events IS
    'S04-12 · audit_events · owner: audit · append-only log of security/business actions; no secrets, no full prompts, tenant-scoped.';

COMMENT ON COLUMN audit_events.tenant_id IS
    'Owner tenant (FK tenants.id); tenant-scoped isolation per ADR-007.';

COMMENT ON COLUMN audit_events.actor_subject IS
    'Actor that performed the audited action; never a token or credential.';

COMMENT ON COLUMN audit_events.action IS
    'Audited action verb (e.g. CREATE, UPDATE, ACTIVATE); bounded vocabulary, stored as TEXT.';

COMMENT ON COLUMN audit_events.resource_type IS
    'Entity that was mutated (e.g. WORKFLOW_DEFINITION); bounded vocabulary, stored as TEXT.';

COMMENT ON COLUMN audit_events.outcome IS
    'Outcome of the audited action (e.g. SUCCEEDED); stored as TEXT.';

-- One index per real lookup path: a tenant's audit trail, most recent first.
CREATE INDEX IF NOT EXISTS audit_events_tenant_created_at_idx
    ON audit_events (tenant_id, created_at);
