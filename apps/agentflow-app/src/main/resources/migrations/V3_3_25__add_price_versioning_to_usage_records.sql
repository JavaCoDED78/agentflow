-- S12-03: price-catalogue versioning on usage_records.
--
-- The usage module owns this table (the same owner as tenant_budgets and the settlement / quota
-- tasks S12-02 ... S12-06). These two columns let every historical estimate be explained through the
-- exact price version that produced it:
--
--   * price_version    - the specific model+version price the estimate was computed against (for
--                         example 'gpt-4o/2026-01-01'); NULL when the catalogue does not yet know
--                         the model, so an unpriced estimate is recorded as unknown, never invented
--                         (invariant 8, S12-02).
--   * pricing_revision - the revision tag of the usage.pricing catalogue that produced the estimate;
--                         NULL when no price was resolved, so the row still records that no price was
--                         applied rather than a guessed one.
--
-- Both are nullable on purpose: a NULL here means "no price was resolved", which is a real state (the
-- S12-02 unknown path), not a missing value that must be rejected. They are written together by the
-- usage module, so a row never stores one without the other. Forward-only (ADR-008), single owner
-- (usage).

ALTER TABLE usage_records
    ADD COLUMN IF NOT EXISTS price_version TEXT,
    ADD COLUMN IF NOT EXISTS pricing_revision TEXT;

COMMENT ON COLUMN usage_records.price_version IS
    'S12-03 / usage: the model plus version price the estimate was computed against; NULL when no price was resolved (unknown stays unknown, S12-02)';
COMMENT ON COLUMN usage_records.pricing_revision IS
    'S12-03 / usage: the revision tag of the usage.pricing catalogue that produced the estimate; NULL when no price was resolved (unknown stays unknown, S12-02)';
