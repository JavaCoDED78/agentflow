-- S03-03 · tenants · owner: identity
--
-- The tenant table. One row per isolated organization/team — the unit of data
-- separation (glossary §1, plan §8.1). `id` is the system UUID primary key;
-- `slug` is the unique, human-facing tenant identifier; `status` is an
-- administrator-only lifecycle field; `created_at` is the immutable creation
-- instant (`timestamptz` per the engineering conventions). Other modules reach
-- this table through a `tenant_id` foreign key, so `tenants` itself has no
-- outgoing foreign key.
CREATE TABLE IF NOT EXISTS tenants (
    id         UUID        PRIMARY KEY,
    slug       TEXT        NOT NULL UNIQUE,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE tenants IS
    'S03-03 · tenants · owner: identity · one row per isolated organization/team; status is administrator-only.';

COMMENT ON COLUMN tenants.status IS
    'Administrator-only lifecycle state (e.g. ACTIVE). Changing it is an operator action.';
