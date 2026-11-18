-- S12-06 / usage: reconciliation of reservations left unsettled by a crash.
--
-- S12-04 added the idempotent reserve/settle lifecycle, but a reservation is settled only when the
-- operation settles after it runs. If the worker dies mid-operation (or a provider call is
-- ambiguous, S12-14) the reservation is left floating: settled_at IS NULL, so it keeps counting at
-- its reserved estimate forever, and the run's true consumption is unknown. S12-06 reconciles
-- those stranded reservations: settle them at their best-known amount and mark them reconciled so
-- the reconciliation itself is idempotent (a duplicate reconcile is a no-op - invariant 3).
--
-- Two columns are added:
--   * reconciled_at - set when the reservation was reconciled by the S12-06 flow (unsettled ->
--     settled-at-best-known). It distinguishes a recovered orphan from one that was settled by the
--     normal settle path, and makes the reconcile idempotent (settled_at IS NULL AND reconciled_at
--     IS NULL guard).
--   * attempt - the operation attempt the reservation was keyed on. The idempotency key (the
--     reservation_id) is derived from (tenant, run, attempt, metric), so a duplicate attempt of the
--     same operation reuses the same idempotency key and a repeated reserve/settle is a no-op -
--     "use attempt ID for idempotency" (invariant 3). NULL for reservations created before S12-06
--     (they predate the attempt key and are reconciled by reservation_id).
--
-- Forward-only (ADR-008), single owner (usage). No new FK: tenant_id / run_id stay plain,
-- tenant-scoped values (invariant 1).

ALTER TABLE budget_reservations
    ADD COLUMN IF NOT EXISTS reconciled_at timestamptz;

ALTER TABLE budget_reservations
    ADD COLUMN IF NOT EXISTS attempt BIGINT;

COMMENT ON COLUMN budget_reservations.reconciled_at IS
    'S12-06 / usage: set when the reservation was reconciled (unsettled -> settled-at-best-known) by the reconciliation flow. Distinguishes a recovered orphan from one settled by the normal S12-04 settle path, and makes the reconcile idempotent (settled_at IS NULL AND reconciled_at IS NULL guard).';

COMMENT ON COLUMN budget_reservations.attempt IS
    'S12-06 / usage: the operation attempt the reservation was keyed on - the idempotency key (reservation_id) is derived from (tenant_id, run_id, attempt, metric), so a duplicate attempt reuses the same idempotency key (invariant 3). NULL for reservations created before S12-06.';
