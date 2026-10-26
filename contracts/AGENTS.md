# AGENTS.md — `contracts/`

Machine-readable integration contracts. General rules are in the root `AGENTS.md`.

## Contents

- `openapi/` — HTTP API (`/api/v1`). Generated from the application or saved deterministically; serves as the baseline for compatibility checks (P1).
- `events/json-schema/` — JSON Schema of envelope v1 and the payload of every event; fixtures for contract tests.
- `asyncapi/` — description of topics and consumer/producer roles (P1).
- The workflow graph JSON Schema (`S05-03`) — [`workflow-graph/`](workflow-graph/): `workflow-graph.json`, the `valid/` and `invalid/` fixtures and a `README.md`. One place only.

## Rules

- A contract changes **in the same PR** as the code. A mismatch between code and contract breaks CI.
- **Compatible:** adding an optional field. **Breaking:** changing/removing a field, its meaning or the partition key — a new major schema/topic version (`agentflow.<domain>.<event>.v<N>`), an explicit baseline update, a `CHANGELOG.md` entry (API/event) and an ADR when needed. A published contract is never changed silently.
- Envelope v1 contains: `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `tenantId`, `aggregateType`, `aggregateId`, `correlationId`, `causationId`, `payload`. Describe `tenantId` as a correlation field, **not** an authorization source.
- Topics and partition keys follow §7: `tenantId:runId` for workflow events, `tenantId:documentId` for documents; each topic's semantics and consumer owner match `docs/architecture/kafka-topics.md` and `docs/architecture/events/README.md`.
- OpenAPI: security schemes, RFC 9457 errors, pagination with a max size, `Idempotency-Key` where needed, and `202` for starting a workflow are all described. Generation order is stable so the diff shows only real changes; never edit the generated file by hand.
- **Examples are safe:** no tokens, secrets, real data, full documents or prompts in payloads and fixtures.
- Contracts are checked in CI: producer fixtures validate against the schema, required fields and optional-field compatibility are covered by tests. Avro + Apicurio Registry — later, not a blocker.

## Verifying changes

- A new schema/event → valid and invalid fixtures + a contract test + a row in the topic catalogue.
- Any API/event contract change → check the owning module (`messaging.event`, `*.web`) and update its `AGENTS.md` if the rules change.
