-- S03-07 · tenant-query indexes · owner: workflowdefinition
--
-- Minimal index set for the *real* lookup paths of the three S03 tables,
-- verified with `EXPLAIN (ANALYZE, BUFFERS)` against pgvector/pgvector:0.8.6-pg18.
-- The rule is "index the lookup path, not every column"
-- (database-conventions.md, Index naming): a dedicated secondary index is added
-- only where the primary key or a unique-constraint index does *not* already
-- cover the path. That is why this migration adds a single index.
--
-- Covered WITHOUT a secondary index (proven with EXPLAIN, no Seq Scan):
--   * tenants.id                       -- PRIMARY KEY
--   * tenants.slug                     -- UNIQUE constraint
--   * workflow_definitions.id          -- PRIMARY KEY
--   * workflow_definitions (tenant_id, name)
--                                        -- UNIQUE constraint; its index also backs
--   -- the tenant_id prefix scan for a tenant list.
--   * workflow_definition_versions.id                    -- PRIMARY KEY
--   * workflow_definition_versions (workflow_id, version)
--                                        -- UNIQUE constraint; the ordered
--                                        -- (workflow_id, version) index also
--                                        -- backs "WHERE workflow_id = ? ORDER
--                                        -- BY version", so no second index.
--
-- The one genuine gap, closed below:
--   * workflow_definitions tenant LIST
--     "SELECT ... WHERE tenant_id = ? ORDER BY created_at LIMIT ? OFFSET ?"
--     was served by the unique (tenant_id, name) index followed by an explicit
--     Sort. (tenant_id, created_at) gives an ordered index scan for the list
--     endpoint (S03-14) with no Sort node.
--
-- Idempotent (IF NOT EXISTS) and forward-only (never edit an applied migration).

CREATE INDEX IF NOT EXISTS workflow_definitions_tenant_created_at_idx
    ON workflow_definitions (tenant_id, created_at);

COMMENT ON INDEX workflow_definitions_tenant_created_at_idx IS
    'S03-07 · workflow_definitions tenant-LIST lookup (tenant_id, created_at). The unique (tenant_id, name) index covers the id and (tenant_id, name) point lookups, and (workflow_id, version) covers every workflow_definition_versions lookup — this is the only path needing a secondary index.';
