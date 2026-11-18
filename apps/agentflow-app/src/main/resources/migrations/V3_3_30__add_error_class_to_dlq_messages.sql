-- S12-10 · dlq_messages · error_class · owner: messaging
--
-- Enrich the S06-11 DLQ record with the consumer error-classification of the failure
-- (com.example.agentflow.messaging.application.InboxErrorClassification), so the operator
-- investigating a held-for-investigation row sees not only the disposition (status: RETRY /
-- DEAD_LETTER) but ALSO the nature of the fault that produced it.
--
--   status            (S06-11) the disposition the consumer applied: RETRY (transient fault
--                     exhausted its bounded immediate retry) or DEAD_LETTER (a permanent /
--                     malformed event short-circuited the retry). "What to do with the message".
--   error_class       (S12-10)   the InboxErrorClassification the consumer derived from the
--                     thrown Throwable: PERMANENT (the envelope can never be processed on a
--                     redelivery - malformed / invalid - so it is dead-lettered) or TRANSIENT
--                     (an infrastructure / external fault that may succeed on a later attempt).
--                     "Why it failed".
--
-- These are two distinct diagnostics of the same outcome and both are kept: a RETRY is always
-- paired with error_class = TRANSIENT and a DEAD_LETTER with error_class = PERMANENT (the
-- InboxDedupService classifies PERMANENT -> DEAD_LETTER and TRANSIENT -> RETRY, one-to-one), but
-- recording the class alongside the disposition keeps the DLQ self-describing for an operator who
-- reads the row without the application's mapping.
--
-- The value vocabulary is enforced at the storage layer (the same pattern as the
-- usage_records / approvals CHECKs) so an unknown class cannot reach the investigation view.
--
-- Payload vs pointer (S12-10 decision). The DLQ stores a POINTER to the original event, NOT the
-- payload inline. The pointer is already the `event_id` column (the SAME id as the outbox
-- event_id / envelope eventId, and the anchor a consumer re-checks against): an operator opens the
-- DLQ record, reads the safe diagnostics (error_class / attempts / last_error / timestamps), and
-- navigates from `event_id` to the redacted payload in `workflow_events` / `outbox_events`. The
-- DLQ row itself never carries the (possibly secret-laden) payload, so a redacted diagnostic is
-- sufficient without disclosing document text, prompts or secrets (invariants 7 / 8 / 9). No new
-- payload / pointer-storage column is added now — if a payload snapshot is ever required it lands as
-- a Garage ref + checksum column on its own (the S15-06 large-payload idiom), never inline.

ALTER TABLE dlq_messages
    ADD COLUMN error_class TEXT NOT NULL DEFAULT 'TRANSIENT'
    CONSTRAINT dlq_messages_error_class_check CHECK (error_class IN ('PERMANENT', 'TRANSIENT'));

COMMENT ON COLUMN dlq_messages.error_class IS
    'S12-10 · consumer error classification of the failed outcome (InboxErrorClassification): PERMANENT (malformed / invalid event, dead-lettered) or TRANSIENT (retryable infrastructure / external fault). Recorded alongside the S06-11 status disposition; a RETRY pairs with TRANSIENT and a DEAD_LETTER with PERMANENT. The DLQ stores a POINTER to the original event (event_id), never the payload inline (S12-10); enforced by the CHECK above.';
