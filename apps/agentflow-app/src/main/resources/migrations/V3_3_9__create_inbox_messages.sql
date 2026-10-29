-- S06-04 · inbox_messages · owner: messaging
--
-- The consumer-side dedup store (glossary §6, plan §8.1, ADR-003). One row per
-- (consumer, event) pair a consumer processes. A business change and its inbox
-- record are written in the SAME PostgreSQL transaction (inbox dedup in the
-- transaction, S06-10): the consumer claims an event by inserting its
-- (consumer_name, event_id) row, and Kafka's at-least-once delivery (ADR-003)
-- means the same event can arrive many times. The composite PRIMARY KEY
-- (consumer_name, event_id) makes a second insert of the same pair fail the
-- unique constraint, so a duplicate delivery can never re-run the side effect —
-- only the first claim proceeds. That is the S06-04 acceptance criterion: the
-- DB constraint prevents two workers from processing one event for the same
-- consumer.
--
-- Ownership: `messaging` owns this table, the same way it owns `outbox_events`
-- and `workflow_events` (S05-09, S06-03). The row is created by an inbox consumer
-- (a `workflowexecution` / `ingestion` worker) as part of the business transition
-- it applies; another module never writes it directly.
--
-- Columns (glossary §6 canonical shape: consumer_name, event_id, processed_at,
-- outcome; PK (consumer_name, event_id)) plus the columns the S06-04 task needs
-- for transient-error behaviour and the S06-03 sibling's retry scaffolding:
--   consumer_name     identity of the inbox consumer (e.g. the worker's
--                     step-handler name); the "consumer" half of the dedup key.
--   event_id          the domain event id (UUID) — the SAME id as
--                     outbox_events.event_id / the envelope eventId — the
--                     "event" half of the dedup key and the anchor a consumer
--                     re-checks against. One row per event per consumer.
--   status            processing lifecycle (PENDING, PROCESSED, FAILED);
--                     application-managed by the inbox consumer/dedup helper,
--                     set only through it, never by a controller.
--   attempts          number of processing attempts made; bumped on retry.
--   next_attempt_at   earliest instant the next retry may run (bounded retry
--                     backoff); NULL while a row is still PENDING the first time.
--   last_error        safe, redacted text of the most recent failure; never a
--                     secret, full prompt or full document (invariant 8).
--   outcome           the business outcome recorded on completion (success or
--                     terminal failure); the glossary's `outcome`.
--   processed_at      instant the row durably reached PROCESSED (timestamptz);
--                     NULL until the business change commits. A transient fault
--                     leaves this NULL and keeps the row PENDING so it can be
--                     retried (S06-10: never mark a transient failure processed).
--   created_at        immutable creation instant (timestamptz, UTC); set once.
--
-- No tenant_id / FK: the inbox is keyed by (consumer, event), not by tenant.
-- Tenant isolation is enforced by the consumer, which processes in its own
-- tenant context established from the stored run / verified security context
-- (invariant 1); the envelope tenantId stays routing/correlation only. The
-- composite PK is the sole uniqueness guard and doubles as its own backing
-- index for the dedup INSERT, so no secondary index is required.
--
-- IF NOT EXISTS keeps Testcontainers rebuilds and the controlled apply step
-- quiet; the migration is forward-only (ADR-008).
CREATE TABLE IF NOT EXISTS inbox_messages (
    consumer_name   TEXT        NOT NULL,
    event_id        UUID        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      TEXT,
    outcome         TEXT,
    processed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT inbox_messages_consumer_event PRIMARY KEY (consumer_name, event_id)
);

COMMENT ON TABLE inbox_messages IS
    'S06-04 · inbox_messages · owner: messaging · consumer-side dedup; one row per (consumer_name, event_id) a consumer processes; a business change and its inbox record write in one transaction; the composite PRIMARY KEY (consumer_name, event_id) rejects duplicate delivery so a second claim cannot re-run the side effect (at-least-once, ADR-003); tenant isolation is enforced by the consumer, not the table.';

COMMENT ON COLUMN inbox_messages.consumer_name IS
    'Inbox consumer identity (e.g. the worker step-handler name); the "consumer" half of the (consumer_name, event_id) dedup key.';

COMMENT ON COLUMN inbox_messages.event_id IS
    'Domain event id (UUID); the SAME id as outbox_events.event_id / the envelope eventId; the "event" half of the dedup key and the consumer-side re-check anchor; one row per event per consumer.';

COMMENT ON COLUMN inbox_messages.status IS
    'Processing lifecycle (PENDING, PROCESSED, FAILED); application-managed by the inbox consumer/dedup helper, set only through it.';

COMMENT ON COLUMN inbox_messages.attempts IS
    'Processing attempt count; bumped on retry (ADR-003); a transient fault increments it but does not move the row out of PENDING.';

COMMENT ON COLUMN inbox_messages.next_attempt_at IS
    'Earliest instant the next retry may run (bounded-retry backoff); NULL while the row is still PENDING for the first time.';

COMMENT ON COLUMN inbox_messages.last_error IS
    'Safe, redacted text of the most recent failure; never a secret, full prompt or full document (invariant 8).';

COMMENT ON COLUMN inbox_messages.outcome IS
    'Business outcome recorded on completion (success or terminal failure); the glossary `outcome`.';

COMMENT ON COLUMN inbox_messages.processed_at IS
    'Instant the row durably reached PROCESSED (NULL until the business change commits); a transient failure leaves it NULL so the row stays retryable (S06-10).';

COMMENT ON COLUMN inbox_messages.created_at IS
    'Immutable creation instant (timestamptz, UTC); set once on insert.';
