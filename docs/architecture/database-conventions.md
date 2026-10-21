# Database Conventions — Tables, Columns, Indexes, and Versioning — AgentFlow

> **Status:** Accepted
> **Owner:** engineering (cross-cutting; every module applies these — the
> migrations directory is a shared application resource, not one domain module)
> **Task:** `S03-06`
> **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (Sprint 3, `S03-06`)
> · `docs/domain-glossary.md` (canonical column/table names) ·
> `docs/architecture/engineering-conventions.md` (identifier and time *format*) ·
> `docs/architecture/database-migrations.md` (adding a migration) ·
> `docs/adr/ADR-002`, `ADR-003`, `ADR-004` (consistency) · root `AGENTS.md`
> (invariant 10)
> **Purpose:** the conventions every database table must follow —
> `timestamptz` timestamps, a single set of audit timestamps, constraint and index
> naming, and optimistic versioning — so that the first migration already matches
> the style and adding a new table requires no new conventions.

---

## Why these conventions

The schema is built from scratch in Sprint 3, and the first migration
(`tenants`, `S03-03`) already sets the shape. If every later table re-invents its
own column names, timestamp columns, constraint names, or concurrency control, a
reader cannot trust that two tables mean the same thing and every new table
introduces a fresh convention. This document fixes *how a table is written* so a
new table matches the existing ones without a new decision.

These conventions operate one layer below the migration mechanics.
`engineering-conventions.md` fixes the **format** of an identifier and a timestamp
(`UUID`, UTC `timestamptz`, injectable `Clock`); this document fixes the
**schema layout** — which timestamp columns a table carries, how its constraints
and indexes are named, and how mutable rows are versioned — and
`database-glossary.md` (`docs/domain-glossary.md`) fixes *which* name each
concept has. A new table must satisfy all three at once.

PostgreSQL is the source of truth (`ADR-002`); exactly-once is never implied
(`ADR-003`); the `version` column described below is application-managed
optimistic concurrency, not a database trigger.

---

## Timestamps — `timestamptz` only

Every timestamp column at rest is PostgreSQL `TIMESTAMPTZ`
(`timestamp with time zone`), stored in **UTC**, never `timestamp without time
zone`. This is the same rule as `engineering-conventions.md` and invariant 10;
restating it here makes the *table* checklist unambiguous.

- A created-instant column is `TIMESTAMPTZ NOT NULL DEFAULT now()`.
- The Java type for a persisted instant is `Instant` (or `OffsetDateTime` only
  when a zone offset must be carried), never `java.util.Date`, `Calendar`,
  `LocalDateTime`/`LocalDate`, or `System.currentTimeMillis()`.
- Money is `NUMERIC` / `BigDecimal`, never `float` / `double` (see
  `engineering-conventions.md`).
- Never store a local-zone timestamp with an assumed zone: it drops the
  timezone, breaks lease/retry/recovery boundaries across DST, and makes
  time-dependent tests flaky.

---

## Unified audit timestamps

Every business table carries the **same** audit columns, in the same style, with
the same meaning. No ad-hoc names (`created_on`, `created_ts`, `modtime`,
`updated_ts`, …) and no extra timestamp columns that another table does not have.

| Column | Present when | Meaning | Type |
|---|---|---|---|
| `created_at` | always | immutable creation instant; never updated after insert | `TIMESTAMPTZ NOT NULL DEFAULT now()` |
| `updated_at` | only if the row is mutable **and** the change is worth tracking | last-mutation instant; nullable until the first update, then bumped by the application layer | `TIMESTAMPTZ` (nullable), set by the application |
| `created_by` / `updated_by` | only if the actor is tracked | the actor on create/update; nullable when the owning FK (`tenant_id`, `workflow_id`) is the owner | `TEXT` |

Consequences:

- `created_at` is the immutable anchor; it is set once on insert by `DEFAULT now()`
  and never appears in an `UPDATE`.
- `updated_at` exists only where a row genuinely changes over time and the last
  change is meaningful (for example a mutable definition). An
  immutable-after-activation row (e.g. an activated
  `workflow_definition_versions` row) has no `updated_at`.
- The glossary is the single source of truth for *which* timestamp column a
  concept has; a table must use the canonical names and not invent a sibling.
  For example `workflow_runs`, `documents` and `agent_profiles` all keep a
  `version` (see *Optimistic versioning below*), and the audit columns follow the
  table above.

---

## Identifiers, foreign keys, and types

Anchored to `engineering-conventions.md` and the glossary:

- **Primary key:** exactly one UUID `id` per table, `UUID` (generated as
  `UUIDv7` by default so it is time-sortable and indexes cleanly). No
  sequential/integer business primary keys — guessable IDs enable enumeration and
  tenant/resource leakage. Keep the PostgreSQL default `<table>_pkey` name; do
  not rename it.
- **Foreign keys:** `<resource>_id` in `snake_case` (`tenant_id`, `workflow_id`,
  `run_id`, `document_id`), `NOT NULL REFERENCES <parent> (id)`. A tenant-scoped
  row carries its `tenant_id`; tenant isolation is enforced by the `(tenant_id,
  resource_id)` read, not by a `tenant_id` column that is left nullable.
- **Enumerations / statuses** are stored as `TEXT` with an application-managed
  lifecycle (`'ACTIVE'`, `'DRAFT'`, …) and, where a bounded set is worth
  enforcing at the storage layer, a `CHECK` constraint. Enum values are
  `UPPER_SNAKE_CASE` in the database (`RUNNING`, `WAITING_FOR_APPROVAL`).
- **JSON / graphs** that have no stable relational shape (for example the
  workflow `graph_json`) are `JSONB`; the application bounds the payload size and
  validates it before an activated version is persisted.

---

## Constraint naming

Explicit constraints are named so the owner and intent are obvious from the
name alone. The two existing migrations already follow this:
`workflow_definitions_tenant_name` and
`workflow_definition_versions_workflow_version`.

| Constraint | Name | Example |
|---|---|---|
| Primary key | keep PostgreSQL default `<table>_pkey` | `workflow_definitions_pkey` (implicit) |
| Foreign key | inline `REFERENCES`; implicit default `<table>_<column>_fkey` | `workflow_definitions_tenant_id_fkey` |
| Unique (composite / business) | `<table>_<col1>_<col2>` | `workflow_definitions_tenant_name` |
| Unique (single column) | implicit `<table>_<column>_key` or explicit `<table>_<column>_unique` | `tenants_slug_key` |
| Check | `<table>_<column>_<value>_check` | `workflow_definitions_status_active_check` |

Consequences:

- Composite business uniqueness uses the explicit `<table>_<descriptor>` form —
  this is what the two existing migrations do, so new composite uniques match.
- Single-column uniques and foreign keys may keep the PostgreSQL implicit
  default name; name them explicitly only when a clearer intent is useful.
- Names are lowercase snake_case with no trailing `constraint` keyword and no
  random suffix, so `psql` output and schema diffs read consistently.

---

## Index naming

- **Lookups:** `<table>_<col1>_<col2>_idx`, one index per real lookup path, not
  per column — for example `workflow_runs_tenant_status_idx`,
  `workflow_runs_tenant_id_idx`. Index the lookup path that is actually queried
  (`S03-07` verifies the real set with `EXPLAIN`); do not index every column.
- **Backing a unique constraint:** the constraint already creates the unique
  index — do not create a second one. A standalone unique index (not tied to a
  constraint) is `<table>_<cols>_uniq`.
- **Full-text / vector (pgvector):** `<table>_<col>_fts_idx`,
  `<table>_<col>_ivfflat_idx` / `<table>_<col>_hnsw_idx` — named by the indexed
  column and the method (these are `S03-07` / `S08` scope; only the naming
  convention lives here).

---

## Optimistic versioning

Tables whose rows are mutated by the application layer (not immutable after
activation) carry an application-managed `version` column for optimistic
concurrency control. A stale update must fail loudly rather than silently
overwrite a concurrent change (`AGENTS.md`, invariant: state changes only through
the state machine with a conditional update).

```sql
-- illustrative: a mutable business table
ALTER TABLE sample_tables ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
```

```sql
-- optimistic conditional update (the application reads `:expectedVersion`)
UPDATE sample_tables
SET name = :name, updated_at = now(), version = version + 1
WHERE id = :id AND version = :expectedVersion;
```

- A `RowCountException` / no-row-affected means another writer changed the row
  first: the application maps it to **`409 Conflict`** and never overwrites
  silently (`S03-10`).
- The `version` column is bumped by the application (a `DEFAULT 0`, incremented
  in the conditional `UPDATE`), **not** by a database trigger — it participates in
  the optimistic-lock protocol.

**Do not confuse the two kinds of `version`.** One table can carry both, and
their meaning is different:

| Kind | Where | Meaning | Mutated? |
|---|---|---|---|
| **Optimistic-lock counter** | `workflow_runs.version`, `documents.version`, `agent_profiles.version` | concurrency control; bumps on every successful update | yes |
| **Monotonic business version** | `workflow_definition_versions.version` | a stable identifier in the composite `unique (workflow_id, version)` key; each row is one immutable version | no — part of the address, never rewritten |

The monotonic business version is an addressable identity, not a lock counter, so
it is set once at creation and never bumped.

---

## Adding a new table — the checklist

The `S03-06` acceptance criterion is that adding a new table requires no new
convention: the table must satisfy every rule above by construction. Before a
migration is opened, confirm each point — this is the checklist a new
`V<version>__<desc>.sql` follows:

1. **Owning module.** One module owns the table; the migration filename and a
   leading `-- S<task> · <table> · owner: <module>` `COMMENT` state it
   (`database-migrations.md`, rule 4).
2. **Primary key.** One UUID `id` (`UUIDv7`); no integer PK.
3. **Audit timestamps.** `created_at TIMESTAMPTZ NOT NULL DEFAULT now()` present;
   `updated_at` only if the row is mutable and tracked; no ad-hoc timestamp names.
4. **Time.** All timestamp columns `TIMESTAMPTZ` at UTC; money `NUMERIC`.
5. **Foreign keys.** `<resource>_id` `NOT NULL REFERENCES <parent> (id)`; tenant
   scope via `tenant_id` on every business row.
6. **Constraints named.** Composite unique `≤ <table>_<cols>`, FK/PG-default PK
   kept implicit, checks `<table>_<column>_check`.
7. **Indexes by lookup.** One index per real lookup path (`<table>_<cols>_idx`),
   not one per column; the constraint's unique index is not duplicated.
8. **Versioning.** Mutable rows get an application-managed `version INTEGER NOT
   NULL DEFAULT 0` and the conditional-update + `409` path; immutable rows use
   `status` + `checksum`, not a lock counter.
9. **Forward-only & idempotent.** `IF NOT EXISTS` where practical; forward-only
   (never edit an applied migration); applied as the one controlled step, not on
   startup (`database-migrations.md`, `ADR-008`).

---

## Worked example (illustrative)

This table is **illustrative** — it is not a migration added in `S03-06`; it
shows how the rules combine. Follow this *shape* for a new table.

```sql
-- S03-XX · sample_tables · owner: <module>
--
-- One mutable row per business record. UUID PK, immutable created_at,
-- updated_at bumped on change, tenant-scoped composite unique,
-- application-managed optimistic version, one lookup index.
CREATE TABLE IF NOT EXISTS sample_tables (
    id         UUID        PRIMARY KEY,                       -- UUIDv7, <table>_pkey
    tenant_id  UUID        NOT NULL REFERENCES tenants (id),  -- inline FK -> tenant_id_fkey
    name       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_by TEXT,                                          -- nullable; tenant_id is the owner
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),            -- immutable creation instant
    updated_at TIMESTAMPTZ,                                   -- mutable; bumped by the app
    version    INTEGER     NOT NULL DEFAULT 0,                -- optimistic-lock counter
    CONSTRAINT sample_tables_tenant_name UNIQUE (tenant_id, name)   -- composite unique name
);

-- One index per real lookup path: list/get by tenant, scoped and ordered.
CREATE INDEX IF NOT EXISTS sample_tables_tenant_status_idx
    ON sample_tables (tenant_id, status, created_at);

COMMENT ON TABLE sample_tables IS
    'S03-XX · sample_tables · owner: <module> · one mutable row per business record; unique (tenant_id, name), optimistic version.';

COMMENT ON COLUMN sample_tables.version IS
    'Optimistic-lock counter; bumped on each successful update, mapped to 409 on conflict.';
```

The corresponding update (the `409` path):

```sql
UPDATE sample_tables
SET name = :name, status = :status, updated_at = now(), version = version + 1
WHERE id = :id AND version = :expectedVersion;   -- no row affected -> 409 Conflict
```

---

## Related

- [`engineering-conventions.md`](./engineering-conventions.md) — the
  identifier and time *format* (`UUID`, UTC `timestamptz`, injectable `Clock`,
  naming per layer) that these schema conventions format.
- [`database-migrations.md`](./database-migrations.md) — migration rules and the
  single process for changing the schema: forward-only, expand/contract,
  forward-fix, adding a migration (`S03-02`).
- [`domain-glossary.md`](../domain-glossary.md) — the canonical *names* of the
  columns and tables these conventions lay out.
- [`ADR-002`](../adr/ADR-002-postgresql-source-of-truth.md),
  [`ADR-003`](../adr/ADR-003-at-least-once-idempotency.md),
  [`ADR-004`](../adr/ADR-004-transactional-outbox.md) — PostgreSQL is the source
  of truth; at-least-once + idempotency; transactional outbox.
- root `AGENTS.md` — invariant 10 (data conventions) and the state-machine /
  conditional-update rule.

---

## Acceptance

The first migration (`tenants`, `workflow_definitions`,
`workflow_definition_versions`) already matches this style, and adding a new
table requires no new convention: every table follows `timestamptz` timestamps,
the unified `created_at` (+ `updated_at` when mutable) audit set, the
`<table>_<…>` constraint/index naming, and application-managed optimistic
`version` for mutable rows.
