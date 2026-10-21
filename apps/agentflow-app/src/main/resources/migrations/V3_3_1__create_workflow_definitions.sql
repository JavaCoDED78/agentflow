-- S03-04 · workflow_definitions · owner: workflowdefinition
--
-- The tenant-scoped container for a workflow definition. One row per distinct
-- workflow a tenant defines; its versions live in a separate
-- `workflow_definition_versions` table (glossary §4, plan §8.1). `id` is the
-- stable system UUID primary key — a definition's identity does not change
-- across its versions; `tenant_id` scopes every row to one isolated organization
-- (tenant isolation, ADR-007) and is a foreign key to `tenants`; `name` is the
-- tenant-scoped business name (unique per tenant); `description` is free text;
-- `status` is a lifecycle state ('ACTIVE' by default) managed by the
-- application layer; `created_by` records the actor on creation (nullable — the
-- `tenant_id` foreign key is the owner); `created_at` is the immutable creation
-- instant (`timestamptz` per the engineering conventions). `unique (tenant_id,
-- name)` is enforced by PostgreSQL (plan §8.1); other modules reach a definition
-- through its application service, not this table.
CREATE TABLE IF NOT EXISTS workflow_definitions (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenants (id),
    name        TEXT        NOT NULL,
    description TEXT,
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_by  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT workflow_definitions_tenant_name UNIQUE (tenant_id, name)
);

COMMENT ON TABLE workflow_definitions IS
    'S03-04 · workflow_definitions · owner: workflowdefinition · tenant-scoped container of a workflow definition; unique (tenant_id, name).';

COMMENT ON COLUMN workflow_definitions.tenant_id IS
    'Owner tenant (FK tenants.id); tenant-scoped isolation per ADR-007.';

COMMENT ON COLUMN workflow_definitions.status IS
    'Lifecycle state (e.g. ACTIVE). Managed by the application layer, not set directly.';

COMMENT ON COLUMN workflow_definitions.created_by IS
    'Actor that created the definition; nullable when tenant_id is the owner.';
