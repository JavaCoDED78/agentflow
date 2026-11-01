# `contracts/events/` — event contracts

JSON Schema and examples for the Kafka event envelope and the documented topics.
Contracts are versioned; breaking changes create a new major version rather than
changing an applied contract in place.

Scaffolded in **S01-03**. Envelope v1 was added in **S06-01**; the topic catalogue
followed in **S06-02**, and the per-event payload schemas in **S06-16**.

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

## Envelope contract tests (S06-13)

Producer fixtures validate against the committed schema in CI; a breaking schema change fails
`./gradlew check`. The `EventContractTest` suite loads
[`envelope-v1.json`](json-schema/envelope-v1.json) (draft 2020-12) once and asserts, against the
canonical producer fixture [`envelope-v1.example.json`](json-schema/envelope-v1.example.json) and
mutated variants:

- the fixture is accepted;
- every required field is enforced (one is removed at a time and the validation is expected to fail);
- an unauthorized property is rejected (`additionalProperties: false`);
- the `schemaVersion` `const` is enforced — the envelope version-bump gate;
- a non-object payload and a non-UUID `tenantId` are rejected;
- the optional `causationId` is valid when absent or `null`.

The validator is a test-only helper on the application's Jackson 3 (`tools.jackson`) line
(`com.networknt:json-schema-validator:3.0.8`, excluded against `com.fasterxml.jackson` on the
test classpath). It reads the committed schema directory from `-Pvent.contract.dir`, which
defaults to this directory when the Gradle property is absent (and skips the suite, via
`@EnabledIfSystemProperty`, on an IDE run). See `contracts/AGENTS.md` for the compatibility
rule: adding an optional field is compatible; changing, renaming or removing a field — or
bumping `schemaVersion` — is breaking and needs a new major schema.

## Topic catalogue (S06-02)

`docs/architecture/kafka-topics.md` is the canonical catalogue of the six `agentflow.*`
topics. Each row names the purpose, the partition key, the consumer owner and the
retention/partition policy; the envelope every record carries is defined here (`S06-01`).
The topic names, key strategy (`tenantId:runId` / `tenantId:documentId`), retention and
consumer owners in that doc are the source the contract tests and the workers agree on.

## Pending (not yet created)

- **Per-event payload schemas (S06-16):** one JSON Schema per documented event, each with
  `additionalProperties: false` and the redacted field allowlist.
