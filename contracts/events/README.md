# `contracts/events/` — event contracts

JSON Schema and examples for the Kafka event envelope and the documented topics.
Contracts are versioned; breaking changes create a new major version rather than
changing an applied contract in place.

Scaffolded in **S01-03**. Envelope v1 was added in **S06-01**; the topic catalogue and
per-event payloads follow in **S06-02** / **S06-16**.

## Envelope v1 (S06-01)

- [`json-schema/envelope-v1.json`](json-schema/envelope-v1.json) — the JSON Schema
  (draft 2020-12, `$id` `.../event-envelope/1.0.0.json`) for the envelope every Kafka
  record carries. Fields (glossary §6): `eventId`, `eventType`, `schemaVersion`,
  `occurredAt`, `tenantId`, `aggregateType`, `aggregateId`, `correlationId`,
  `causationId`, `payload`.
  - **Required:** `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `tenantId`,
    `aggregateType`, `aggregateId`, `correlationId`, `payload`.
  - **Optional:** `causationId` (null for the first event in a correlation chain).
  - `additionalProperties: false` on the envelope; `tenantId` is correlation/routing
    only — never an authorization source (invariant 1).
  - **No secret, full prompt or full document** in the payload or the headers; the
    per-event `payload` is a typed object with an explicit field allowlist, defined per
    event below.
- [`json-schema/envelope-v1.example.json`](json-schema/envelope-v1.example.json) — a
  canonical valid instance (`workflow.run-requested.v1`), used as the producer fixture.

The envelope schema was validated against the example with `ajv` (draft 2020-12) before
commit: the example passes, and a missing `eventId`, an extra property, a non-UUID id, a
wrong `schemaVersion`, and a non-object payload are all rejected.

## Pending (not yet created)

- **Topic catalogue (S06-02):** `docs/architecture/kafka-topics.md` and
  `docs/architecture/events/README.md`; topic names, key strategy (`tenantId:runId` /
  `tenantId:documentId`), retention and consumer owners.
- **Per-event payload schemas (S06-16):** one JSON Schema per documented event, each with
  `additionalProperties: false` and the redacted field allowlist.
- **Event contract tests (S06-13):** producer fixtures validate against the schema;
  required-field and optional-field compatibility are covered by tests; a breaking change
  breaks CI.
