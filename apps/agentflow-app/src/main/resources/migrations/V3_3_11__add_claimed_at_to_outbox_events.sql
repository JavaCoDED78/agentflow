-- S06-08 · outbox_events.claimed_at · owner: messaging
--
-- The outbox publisher (S06-07) claims a row with an atomic conditional UPDATE
-- (PENDING -> CLAIMED) and then publishes it OUTSIDE a database transaction
-- (invariant 6: state and the broker delivery stay decoupled). If the publisher
-- crashes after the broker has received the envelope but before it records the
-- outcome (the S06-08 crash window), the row is left in CLAIMED with no outcome.
-- Without a claim timestamp the publisher could not tell a row it is still
-- publishing (a live, slow publisher) from a row its predecessor abandoned, so
-- the abandoned row would be re-fetched forever or, worse, never re-fetched
-- (a lost event).
--
-- claimed_at is set by the publisher on every claim (PENDING, stale-CLAIMED and
-- due-FAILED, S06-08) so the poll can identify a *stale* claim — one whose
-- claimed_at is older than agentflow.outbox.publisher.claim-timeout-seconds —
-- and re-publish it. Re-publishing a row the broker already delivered is a
-- duplicate, not a loss: the consumer-side inbox dedups it (S06-10), and the
-- at-least-once guarantee (ADR-003) is honored. claimed_at is publisher-internal
-- claim tracking; it is NOT part of the OutboxEvent domain record (which maps
-- the business columns only).
--
-- Forward-only (ADR-008); IF NOT EXISTS keeps Testcontainers rebuilds quiet.
ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN outbox_events.claimed_at IS
    'S06-08 · publisher claim timestamp (set on every PENDING/CLAIMED/FAILED claim); a CLAIMED row whose claimed_at is older than the configured claim-timeout-seconds is stale (its publisher crashed mid-publish) and is re-published by a recovery pass — a duplicate, never a loss (at-least-once, ADR-003).';
