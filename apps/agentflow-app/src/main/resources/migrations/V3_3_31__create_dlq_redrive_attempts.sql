-- S12-12 · dlq_redrive_attempts · owner: messaging
--
-- The durable record of a single operator-controlled redrive of one dead-lettered message (S12-12).
-- One row per {@code dlq_messages} record ({@code dlq_id} is the primary key), so the redrive
-- The redrive is idempotent: a second redrive of the same DLQ record returns the already-recorded
-- redrive (no second re-published envelope), and this table's PRIMARY KEY on dlq_id is the
-- storage-level guarantee that a second linkage row can never be inserted.
--
-- Ownership: `messaging` owns this table, the same way it owns {@code dlq_messages},
-- {@code outbox_events} and {@code inbox_messages}. It is written by the messaging
-- {@code DlqRedriveService} inside the redrive transaction, together with the re-published
-- {@code outbox_events} row and the audit row.
--
-- The redrive re-publishes the ORIGINAL envelope (its payload lives only in {@code outbox_events};
-- the DLQ record is a pointer, per the S12-10 decision). This table is the linkage between the
-- failed record and its fresh re-delivery:
--
--   dlq_id             the DLQ record being redriven (FK to dlq_messages.id; the primary key)
--   event_id           the ORIGINAL envelope id being redriven (DLQ.event_id); references the
--                      original failed outbox_events.event_id
--   requeued_event_id  the NEW envelope id the redrive re-published (a fresh outbox_events row,
--                      so the inbox dedup — keyed by (consumer, event) — treats it as a new claim
--                      and the business change re-runs once)
--   reason             the operator-supplied reason for the redrive (safe, redacted at audit time)
--   operator           the operator subject that performed the redrive (the actor subject)
--   tenant_id          the operator's tenant, recorded for operational visibility (the DLQ / inbox
--                      are tenant-wide, keyed by (consumer, event); there is no FK / tenant scope)
--   created_at         instant the redrive was recorded (timestamptz, UTC)
--
-- IF NOT EXISTS keeps Testcontainers rebuilds and the controlled apply step quiet; the migration is
-- forward-only (ADR-008).
CREATE TABLE IF NOT EXISTS dlq_redrive_attempts (
    dlq_id            UUID        PRIMARY KEY,
    event_id          UUID        NOT NULL,
    requeued_event_id UUID        NOT NULL,
    reason            TEXT        NOT NULL,
    operator          TEXT        NOT NULL,
    tenant_id         UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE dlq_redrive_attempts IS
    'S12-12 · dlq_redrive_attempts · owner: messaging · one row per operator-controlled redrive of a DLQ record (dlq_id PRIMARY KEY); records the linkage event_id -> requeued_event_id so the redrive is idempotent (a second redrive of the same record returns the existing redrive, never a second linkage row) and auditable; written by DlqRedriveService in the redrive transaction alongside the re-published outbox_events row and the audit row.';

COMMENT ON COLUMN dlq_redrive_attempts.dlq_id IS
    'DLQ record (dlq_messages.id) being redriven; the primary key and the redrive idempotency anchor (invariant 4).';

COMMENT ON COLUMN dlq_redrive_attempts.event_id IS
    'Original envelope id being redriven (dlq_messages.event_id); references the original failed outbox_events.event_id.';

COMMENT ON COLUMN dlq_redrive_attempts.requeued_event_id IS
    'New envelope id the redrive re-published (a fresh outbox_events row); the inbox dedup key by (consumer, requeued_event_id) so the business change re-runs once.';

COMMENT ON COLUMN dlq_redrive_attempts.reason IS
    'Operator-supplied reason for the redrive; redacted through SecretRedactor before the audit row is written (invariant 8).';

COMMENT ON COLUMN dlq_redrive_attempts.operator IS
    'Operator subject that performed the redrive (the audit actor subject).';

COMMENT ON COLUMN dlq_redrive_attempts.tenant_id IS
    'Operator tenant, recorded for operational visibility only; the DLQ / inbox are tenant-wide (keyed by consumer_name, event_id), so this column is not a scoping FK.';

COMMENT ON COLUMN dlq_redrive_attempts.created_at IS
    'Instant the redrive was recorded (timestamptz, UTC); set once on insert.';
