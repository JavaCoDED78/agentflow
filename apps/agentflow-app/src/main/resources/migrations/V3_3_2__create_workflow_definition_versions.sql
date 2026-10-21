-- S03-05 · workflow_definition_versions · owner: workflowdefinition
--
-- One row per immutable version of a workflow definition. The parent
-- `workflow_definitions` row (S03-04) is the tenant-scoped, mutable container;
-- each version is a point-in-time snapshot of the graph that, once activated,
-- never changes (glossary §4, plan §8.1). `id` is the version's own UUID primary
-- key — a run references a *specific version* (glossary "Workflow version"), so
-- callers address versions unambiguously. `workflow_id` is the foreign key to
-- `workflow_definitions.id`; tenant isolation is not stored here (ADR-007) — it
-- flows through the parent's `tenant_id`, which is why another tenant's version
-- is indistinguishable from a missing one. `version` is the monotonic version
-- number assigned by the application layer; `unique (workflow_id, version)`
-- (enforced by PostgreSQL) is what makes each version addressable and never
-- overwritten by another version. `graph_json` holds the declarative graph of
-- typed steps as JSONB; `checksum` (sha256 of the graph) proves an activated
-- version is immutable; `status` is the activation metadata ('DRAFT' until
-- activated, then immutable — S05-12); `created_by` is the actor (nullable,
-- `workflow_id` is the owner); `created_at` is immutable (`timestamptz`). Other
-- modules reach a version through its application service, not this table.
CREATE TABLE IF NOT EXISTS workflow_definition_versions (
    id            UUID        PRIMARY KEY,
    workflow_id   UUID        NOT NULL REFERENCES workflow_definitions (id),
    version       INTEGER     NOT NULL,
    graph_json    JSONB       NOT NULL,
    checksum      TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'DRAFT',
    created_by    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT workflow_definition_versions_workflow_version UNIQUE (workflow_id, version)
);

COMMENT ON TABLE workflow_definition_versions IS
    'S03-05 · workflow_definition_versions · owner: workflowdefinition · one immutable row per workflow-definition version; unique (workflow_id, version), immutable after activation.';

COMMENT ON COLUMN workflow_definition_versions.workflow_id IS
    'Parent workflow definition (FK workflow_definitions.id); tenant isolation flows through the parent tenant_id (ADR-007).';

COMMENT ON COLUMN workflow_definition_versions.version IS
    'Monotonic version number assigned by the application layer; uniqueness per workflow enforced by (workflow_id, version).';

COMMENT ON COLUMN workflow_definition_versions.graph_json IS
    'Declarative graph of typed steps for this version (JSONB).';

COMMENT ON COLUMN workflow_definition_versions.checksum IS
    'Checksum (sha256) of graph_json; proves an activated version is immutable.';

COMMENT ON COLUMN workflow_definition_versions.status IS
    'Activation metadata (e.g. DRAFT until activated, then immutable). Managed by the application layer, not set directly.';

COMMENT ON COLUMN workflow_definition_versions.created_by IS
    'Actor that created the version; nullable when workflow_id is the owner.';
