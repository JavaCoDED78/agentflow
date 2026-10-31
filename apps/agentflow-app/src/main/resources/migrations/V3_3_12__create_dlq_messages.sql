-- S06-11 · dlq_messages · owner: messaging
--
-- The durable, held-for-investigation copy of a failed consumer outcome (glossary §6, plan §6.4,
-- ADR-003). One row per (consumer, event) pair a consumer could not apply: a transient fault that
-- exhausted its bounded immediate retry (status = 'RETRY'), or a permanent / malformed event that
-- short-circuited the retry (status = 'DEAD_LETTER').
--
-- Ownership: `messaging` owns this table, the same way it owns `outbox_events` and `inbox_messages`
-- (S06-03 / S06-04). It is appended by the messaging `DlqService` when a consumer records a RETRY /
-- DEAD_LETTER outcome; another module never writes it directly.
--
-- This is NOT a Kafka dead-letter topic. It is the PostgreSQL side of the S06-11 consumer error
-- policy (invariant 2: PostgreSQL is the source of truth). The consumer's authoritative disposition
-- stays in `inbox_messages` (set through the inbox dedup helper); this table is the investigation
-- view fed from it. Messages here are evidence for investigation, not to be reprocessed by default
-- (docs/architecture/kafka-topics.md, DLQ retention).
--
-- Columns:
--   event_id          the domain event id (UUID) — the SAME id as
--                     outbox_events.event_id / the envelope eventId; the "event" half of the dedup
--                     key and the DLQ dedup anchor. One row per event per consumer.
--   topic             the versioned topic the envelope arrived on (kebab-case + .v<N>).
--   consumer_name     identity of the inbox consumer whose processing failed (the "consumer" half
--                     of the dedup key); mirrors the inbox_messages consumer.
--   status            the consumer error policy disposition (RETRY, DEAD_LETTER), S06-11.
--   attempts          the attempt count after the failed attempts (already the exhausted total).
--   last_error        safe, redacted text of the most recent failure; never a secret, full prompt
--                     or full document (invariant 8).
--   recorded_at       instant the record was appended (timestamptz, UTC).
--
-- No tenant_id: the DLQ is keyed by (consumer, event), not by tenant (the inbox is not tenant
-- scoped either); tenant isolation is enforced by the consumer, not the table. The composite
-- UNIQUE (consumer_name, event_id) deduplicates the DLQ append the same way the inbox composite PK
-- deduplicates the claim (at-least-once, ADR-003), and doubles as its own backing index, so no
-- secondary index is required.
--
-- IF NOT EXISTS keeps Testcontainers rebuilds and the controlled apply step quiet; the migration is
-- forward-only (ADR-008).
CREATE TABLE IF NOT EXISTS dlq_messages (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id      UUID        NOT NULL,
    topic         TEXT        NOT NULL,
    consumer_name TEXT        NOT NULL,
    status        TEXT        NOT NULL,
    attempts      INTEGER     NOT NULL DEFAULT 0,
    last_error    TEXT,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT dlq_messages_consumer_event UNIQUE (consumer_name, event_id)
);

COMMENT ON TABLE dlq_messages IS
    'S06-11 · dlq_messages · owner: messaging · durable held-for-investigation copy of a failed consumer outcome; one row per (consumer_name, event_id); appended by the messaging DlqService on a RETRY/DEAD_LETTER disposition; the PostgreSQL side of the consumer retry/error policy (invariant 2); tenant isolation is enforced by the consumer, not the table.';

COMMENT ON COLUMN dlq_messages.event_id IS
    'Domain event id (UUID); the SAME id as outbox_events.event_id / the envelope eventId; the "event" half of the (consumer_name, event_id) dedup key and the DLQ dedup anchor; one row per event per consumer.';

COMMENT ON COLUMN dlq_messages.topic IS
    'Versioned topic the envelope arrived on (kebab-case + .v<N>).';

COMMENT ON COLUMN dlq_messages.consumer_name IS
    'Inbox consumer identity whose processing failed; the "consumer" half of the (consumer_name, event_id) dedup key; mirrors inbox_messages.consumer_name.';

COMMENT ON COLUMN dlq_messages.status IS
    'Consumer error policy disposition (S06-11): RETRY (transient fault exhausted the bounded immediate retry) or DEAD_LETTER (permanent / malformed event short-circuited the retry).';

COMMENT ON COLUMN dlq_messages.attempts IS
    'Attempt count after the failed attempts (already the exhausted total).';

COMMENT ON COLUMN dlq_messages.last_error IS
    'Safe, redacted text of the most recent failure; never a secret, full prompt or full document (invariant 8).';

COMMENT ON COLUMN dlq_messages.recorded_at IS
    'Instant the record was appended (timestamptz, UTC); set once on insert.';
