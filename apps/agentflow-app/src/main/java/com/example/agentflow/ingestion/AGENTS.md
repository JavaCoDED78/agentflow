# AGENTS.md — `ingestion`

> **Owner:** `ingestion` — extract → chunk → embed → index, retry and ingestion idempotency.
> **Applies:** `S02-07` — package-by-feature skeleton for this module.
> **Authority:** `docs/architecture/module-map.md` (owners + dependency direction) and
> `docs/architecture/module-api.md` (public-API boundary). They are the single source of truth;
> this file restates the essentials only.

## Responsibility

extract → chunk → embed → index, retry and ingestion idempotency.

## Data ownership

`ingestion` owns no table of its own; it indexes into `knowledgebase` and is driven through `messaging` events.

## Package layout (internal vs public)

```text
com.example.agentflow.ingestion/               ← public API boundary
├── domain/          pure model + invariants        internal
├── application/     use cases, transactions         public services + DTOs
│   └── config/      bean wiring (parser/chunker/    internal
│                    embedder/ingestion services)
├── web/             controllers + API DTOs          internal
├── persistence/     repositories, mappers, SQL       internal
└── worker/          Kafka consumer                   internal
```

`persistence`, `web`, `worker`, `domain` and `application.config` are internal: another module must
not import them (enforced by the architecture test, `S02-08` / `S02-09`).

## Rules

- Business rules stay in `application` (transactions) or `domain` (invariants); never in `web` or
  infrastructure.
- A new `module → module` edge requires an ADR (root `AGENTS.md`).
- The indexing worker (S10-13) is transport-only: `DocumentIndexingWorker` polls
  `agentflow.document.ingestion-requested.v1` and acks a Kafka offset only after the inbox outcome
  is durable; dedup runs through `messaging` (`InboxConsumerService`), never a local cache. The
  pipeline (`IngestionService`) runs inside that inbox transaction — claim, chunk writes and the
  `document.indexed.v1` outbox record commit or roll back together.
- Chunk idempotency is a database guard (`ON CONFLICT` on the S10-10 unique key
  `(document_id, chunk_index, pipeline_version)`), never a read-then-write; the redelivery policy
  (`agentflow.ingestion.indexing.redelivery-policy`, `skip` default / `update`) is worker-side
  configuration, and an unknown value must fail the wiring at startup.
