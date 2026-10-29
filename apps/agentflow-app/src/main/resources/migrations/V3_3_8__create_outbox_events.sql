-- S06-03 · outbox_events · owner: messaging
--
-- The DB-side transactional outbox (glossary §6, plan §8.1, ADR-004). One row per
-- domain event that the owning module (workflowexecution / ingestion) must publish to
-- Kafka after it commits its durable state, so state and the event cannot diverge: a
-- run is never accepted without its outbox event and an event is never published without
-- durable state. The outbox publisher (S06-07) polls the PENDING rows, publishes each to
-- Kafka and marks it PUBLISHED; a second publish of the same row is a no-op, so publisher
-- restarts, retries and duplicate delivery stay safe (at-least-once, ADR-003).
--
-- Ownership: `messaging` owns this table (module-map §13, glossary §6), the same way it
-- owns the append-only `workflow_events` table (S05-09). The row is inserted by the
-- `workflowexecution` transition service (and later `ingestion`) in the same PostgreSQL
-- transaction as the state it describes, reaching `messaging` through its allowed edge
-- (arrow 13 / arrow 11 in the module-map), never by writing the table directly.
--
-- Columns (plan §8.1 data model line: event_id, aggregate_type, aggregate_id, topic,
-- event_key, payload, headers, created_at, published_at, publish_attempts, last_error;
-- the S06-03 task adds `status` and `next_attempt_at`; `tenant_id` is stored directly
-- because `messaging` owns the table and scopes its own reads by it — the same pattern as
-- `workflow_events`):
--   id               stable system UUID primary key (UUIDv7 by convention); the row identity.
--   event_id         the domain event id; the idempotent publish key (ADR-003) and the
--                    dedup anchor the inbox re-checks at the consumer. One row per event,
--                    so a re-published record is the same event_id and is not published
--                    twice.
--   tenant_id        scopes every row to one isolated tenant (tenant isolation, ADR-007)
--                    and is a foreign key to `tenants`. `messaging` owns `outbox_events`
--                    and scopes its own pending reads by it; another tenant's row is
--                    indistinguishable from a missing one (404/403). The tenant is a
--                    typed FK here, never parsed out of `event_key` (invariant 1: the
--                    envelope `tenantId` is routing/correlation only, never an
--                    authorization source, so the outbox stores it as real data).
--   aggregate_type   the aggregate root type the event describes (e.g. `run`, `document`);
--                    the "type" half of the event's identity.
--   aggregate_id     the aggregate id (the run id / document id) the event is scoped to.
--   topic            the versioned Kafka topic the publisher sends the envelope to
--                    (e.g. `agentflow.workflow.run-requested.v1`); the `vN` suffix is the
--                    event's `version` half, fixed per event by S06-02.
--   event_key        the stable aggregate key `tenantId:aggregateId` so every state event
--                    of one aggregate lands in the same partition and is ordered (S06-02);
--                    a re-published record re-routes to the same partition and dedups.
--   payload          the redacted event payload the envelope carries (JSON text). Never a
--                    secret, a full prompt or a full document (invariant 8).
--   headers          redacted correlation metadata the envelope carries (correlationId,
--                    causationId, traceparent) — correlation only, never a secret.
--   status           publish lifecycle (`PENDING`, `PUBLISHED`, `FAILED`); application-
--                    managed by the publisher, set only through it, never by a controller.
--   publish_attempts number of publish attempts made; persisted before the call and
--                    bumped on retry; an ambiguous external (broker) outcome is recorded
--                    in `last_error` and never treated as exactly-once (ADR-003).
--   next_attempt_at  earliest instant the next publish attempt may run (bounded retry
--                    backoff); NULL while a row is still PENDING for the first time.
--   last_error       safe, redacted text of the most recent publish failure; never a
--                    secret, a full prompt or a full document (invariant 8).
--   created_at       immutable creation instant (timestamptz, UTC); set once on insert.
--   published_at     instant the row was published (timestamptz); NULL until published.
--
-- Append-only-ish in the sense that a row is never updated in place by a controller; only
-- the publisher transitions `status` (PENDING -> PUBLISHED / FAILED) as it makes progress.
-- `IF NOT EXISTS` keeps Testcontainers rebuilds and the controlled apply step quiet; the
-- migration is forward-only (ADR-008).
CREATE TABLE IF NOT EXISTS outbox_events (
    id               UUID        PRIMARY KEY,
    event_id         UUID        NOT NULL,
    tenant_id        UUID        NOT NULL REFERENCES tenants (id),
    aggregate_type   TEXT        NOT NULL,
    aggregate_id     UUID        NOT NULL,
    topic            TEXT        NOT NULL,
    event_key        TEXT        NOT NULL,
    payload          TEXT        NOT NULL,
    headers          TEXT,
    status           TEXT        NOT NULL DEFAULT 'PENDING',
    publish_attempts INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ
);

COMMENT ON TABLE outbox_events IS
    'S06-03 · outbox_events · owner: messaging · transactional DB outbox; one row per domain event to publish to Kafka; state + outbox row in one transaction; publish idempotent by event_id; tenant scoped (FK tenants.id); pending rows fetched by outbox_events_pending_idx.';

COMMENT ON COLUMN outbox_events.event_id IS
    'Domain event id; idempotent publish key (ADR-003) and consumer-side inbox dedup anchor; one row per event.';

COMMENT ON COLUMN outbox_events.tenant_id IS
    'Owner tenant (FK tenants.id); tenant-scoped isolation per ADR-007; stored directly because messaging owns this table and scopes its pending reads by it.';

COMMENT ON COLUMN outbox_events.event_key IS
    'Stable aggregate key "tenantId:aggregateId" so all state events of one aggregate land in the same partition and are ordered (S06-02); the tenant is parsed here for routing only, never used as an authorization source.';

COMMENT ON COLUMN outbox_events.status IS
    'Publish lifecycle (PENDING, PUBLISHED, FAILED); application-managed by the outbox publisher, set only through it.';

COMMENT ON COLUMN outbox_events.publish_attempts IS
    'Publish attempt count; persisted before the external call and bumped on retry (ADR-003); never treated as exactly-once.';

COMMENT ON COLUMN outbox_events.last_error IS
    'Safe, redacted text of the most recent publish failure; never a secret, full prompt or full document (invariant 8).';

COMMENT ON COLUMN outbox_events.created_at IS
    'Immutable creation instant (timestamptz, UTC); set once on insert.';

COMMENT ON COLUMN outbox_events.published_at IS
    'Instant the row was published to Kafka (NULL until published); recorded only after a broker acknowledgement (S06-08).';

-- One index per real lookup path. The polling publisher (S06-07) fetches a bounded batch of
-- unpublished rows ordered oldest-first (FIFO) so retries drain in creation order; the
-- partial index on the pending status backs that scan directly (invariant: the pending read
-- is backed by an index, not a full table scan), and lets concurrent publisher instances
-- scan and claim rows without a full scan. The atomic claim itself (UPDATE ... SET status
-- WHERE id = ? AND status = 'PENDING') is fenced by the conditional WHERE and the lease, so
-- two publishers cannot publish the same row.
CREATE INDEX IF NOT EXISTS outbox_events_pending_idx
    ON outbox_events (created_at)
    WHERE status = 'PENDING';
