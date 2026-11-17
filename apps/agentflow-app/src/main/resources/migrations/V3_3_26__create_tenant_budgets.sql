-- S12-04 / usage: the durable per-tenant cost budget and its idempotent reservations.
--
-- The usage module owns this table (the same owner as usage_records and the later quota work).
-- It is the durable source of truth for the "Cost budget" / "Budget" the domain glossary names:
--
--   * tenant_budgets        - the per-tenant limits (max_runs / max_tokens / max_cost) measured
--                             over a period. One row per tenant.
--   * budget_reservations   - one row per reservation an operation makes against that budget: the
--                             estimated amount reserved before the operation, reconciled to the
--                             actual cost when the operation settles. Every reservation counts toward
--                             the tenant's consumption at its reserved estimate until it settles, and
--                             at its reconciled actual_cost afterwards (both count, so the budget
--                             stays meaningful once everything settled).
--
-- The budget is checked and reserved BEFORE an operation starts (invariant 7: bound the cost of
-- AI / workflow calls). Settlement is idempotent: re-settling the same reservation is a no-op, so
-- a duplicate delivery or a crash-retry never double-charges (invariant 3, at-least-once — never
-- exactly-once). No raw prompt / document / cost secret is stored here: only the reservation id
-- (the idempotency key) and its amount (invariant 8). Forward-only (ADR-008), single owner (usage).

CREATE TABLE IF NOT EXISTS tenant_budgets (
    tenant_id   UUID          PRIMARY KEY,
    period      TEXT          NOT NULL,
    max_runs    BIGINT        NOT NULL,
    max_tokens  BIGINT        NOT NULL,
    max_cost    NUMERIC(20,8) NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now()
);

COMMENT ON TABLE tenant_budgets IS
    'S12-04 / usage: per-tenant hard budget (max_runs / max_tokens / max_cost) measured over a period; the durable cost budget the usage module owns (same owner as usage_records). tenant_id is a plain, tenant-scoped value, not a cross-module FK (tenants / workflow_runs are owned elsewhere); tenant isolation lives on the row and in the predicate (invariant 1).';

CREATE TABLE IF NOT EXISTS budget_reservations (
    reservation_id    UUID          PRIMARY KEY,
    tenant_id         UUID          NOT NULL,
    run_id            UUID,
    metric            TEXT          NOT NULL,
    reserved_amount   NUMERIC(20,8) NOT NULL,
    actual_cost       NUMERIC(20,8),
    settled_at        timestamptz,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT budget_reservations_metric_check
        CHECK (metric IN ('COST', 'TOKEN'))
);

COMMENT ON TABLE budget_reservations IS
    'S12-04 / usage: one reservation per operation against a tenant budget. Counts toward consumption while NOT settled (settled_at IS NULL); the reserved estimate is reconciled to actual_cost on settle. reservation_id is the idempotency key - a duplicate reservation (same id) never double-charges (invariant 3). tenant_id / run_id are plain, tenant-scoped values, not cross-module FKs; tenant isolation lives on the row and in the predicate (invariant 1).';

COMMENT ON COLUMN budget_reservations.metric IS
    'S12-04 / usage: the budget dimension reserved against - COST (estimated cost) or TOKEN (estimated tokens).';

-- Consumption is the accrual sum of every reservation the tenant made for the metric: an
-- unsettled reservation counts at its reserved estimate, and once it settles the same row counts at
-- its reconciled actual_cost. So the read sums COALESCE(actual_cost, reserved_amount) over the
-- tenant's rows for the metric (never filtered by settled_at - a settled row still counts, now at
-- its actual cost, otherwise the budget would look unlimited once everything settled). The plain
-- (tenant_id, metric) index covers the read.
CREATE INDEX IF NOT EXISTS budget_reservations_tenant_metric_idx
    ON budget_reservations (tenant_id, metric);
