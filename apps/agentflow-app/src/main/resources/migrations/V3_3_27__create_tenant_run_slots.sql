-- S12-05 / usage: the durable per-tenant parallel-run quota slots.
--
-- The usage module owns this table (the same owner as usage_records, tenant_budgets and
-- budget_reservations). It is the durable source of truth for the per-tenant parallel-runs limit
-- the domain glossary "Quota" names:
--
--   * tenant_run_slots   - one row per NON-terminal run a tenant holds: the run acquires its slot
--                          when it is created (before it is charged anything) and releases it when
--                          the run reaches a terminal state (SUCCEEDED / FAILED / CANCELLED) through
--                          the single transition service.
--
-- The limit itself is NOT duplicated here: it stays the tenant_budgets.max_runs column (V3_3_26,
-- S12-04) this quota enforces. A tenant with no tenant_budgets row is unlimited (never blocked),
-- exactly as the cost budget treats a missing budget row.
--
-- Concurrency (the S12-05 acceptance): concurrent acquisitions are counted in PostgreSQL, never in
-- Redis. A slot acquisition takes the tenant's tenant_budgets row lock (SELECT ... FOR UPDATE, the
-- same serialization BudgetService.reserve uses), counts the tenant's active slots under that lock
-- and inserts the new row - so two concurrent run creations cannot both pass the headroom check.
-- Redis is used only for auxiliary rate limiting / caching (module-map.md), never as the durable
-- usage state (invariant 2: PostgreSQL is the source of truth).
--
-- The composite (tenant_id, run_id) PRIMARY KEY is the idempotency guard: a duplicate acquisition
-- for the same run is an ON CONFLICT DO NOTHING no-op (invariant 3, at-least-once - never
-- exactly-once), and a repeated release (DELETE of an absent row) is likewise a no-op. tenant_id /
-- run_id are plain, tenant-scoped values, not cross-module FKs (workflow_runs is owned by
-- workflowexecution); tenant isolation lives on the row and in the predicate (invariant 1).
-- Forward-only (ADR-008), single owner (usage). No prompt / payload / secret is stored here.

CREATE TABLE IF NOT EXISTS tenant_run_slots (
    tenant_id    UUID          NOT NULL,
    run_id       UUID          NOT NULL,
    acquired_at  timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, run_id)
);

COMMENT ON TABLE tenant_run_slots IS
    'S12-05 / usage: one row per non-terminal run a tenant holds - the durable per-tenant parallel-runs quota slot, acquired at run creation and released at the terminal transition. The limit is tenant_budgets.max_runs (V3_3_26). tenant_id / run_id are plain, tenant-scoped values, not cross-module FKs; tenant isolation lives on the row and in the predicate (invariant 1). Concurrent acquisitions are serialized on the tenant_budgets row lock and counted in PostgreSQL, never in Redis (invariant 2).';

COMMENT ON COLUMN tenant_run_slots.acquired_at IS
    'S12-05 / usage: when the run acquired its quota slot (run creation); the release is the row DELETE at the terminal transition, so a slot with an old acquired_at on a non-terminal run is expected - staleness recovery is S12-06.';
