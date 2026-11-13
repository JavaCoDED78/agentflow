# AGENTS.md — `knowledgebase`

> **Owner:** `knowledgebase` — document metadata, access policy, tenant/ACL-filtered chunk search and citations.
> **Applies:** `S02-07` — package-by-feature skeleton for this module.
> **Authority:** `docs/architecture/module-map.md` (owners + dependency direction) and
> `docs/architecture/module-api.md` (public-API boundary). They are the single source of truth;
> this file restates the essentials only.

## Responsibility

document metadata, access policy, tenant/ACL-filtered chunk search and citations.

## Document upload validation (`S10-03`)

`knowledgebase` owns the accept/bound checks the upload API (`POST /v1/documents`) runs before a single
byte is written to object storage and before a single `documents` row is committed. It is DB-free (no
`DataSource` / storage dependency) and safe to call from an early API:

- [`application.DocumentUploadValidator`](application/DocumentUploadValidator.java) drives the
  reject-before-persist checks — filename → content type → size → magic bytes → media duration →
  SHA-256 checksum — and every rejection is one `DocumentUploadValidationException` (the HTTP layer maps
  it to a `400`); the payload bytes are never written by this call.
- [`domain.DocumentContentType`](domain/DocumentContentType.java) is the **closed** allowlist (rejects
  anything not on it — an untrusted header cannot expand the surface) and verifies the real leading bytes
  (`matches`). [`domain.FileNameSanitizer`](domain/FileNameSanitizer.java) strips any path/traversal to a
  bare base name so the filename can never become an object path. [`domain.MediaDuration`](domain/MediaDuration.java)
  decodes the WAV duration. [`domain.DocumentUploadLimits`](domain/DocumentUploadLimits.java) is the
  immutable domain view of the three configured limits. [`domain.DocumentUploadStatus`](domain/DocumentUploadStatus.java)
  is the upload lifecycle state.
- The limits bind from `agentflow.upload.*` (`config.AgentFlowProperties.Upload`); `knowledgebase.application.config.DocumentUploadConfiguration`
  wires the validator, following `ToolExecutionConfiguration`.

The upload API, the storage client, the `documents` schema (`S10-05`) and the ingestion worker are
separate; this layer only validates, it owns no row.

## Vector retrieval (`S10-14`)

`knowledgebase` owns the tenant/ACL-filtered vector search the glossary "Retrieval" names:

- [`application.RetrievalService`](application/RetrievalService.java) is the public read entry point —
  `search(tenantId, knowledgeBaseId, SearchRequest)`: it resolves the collection's pinned
  `embedding_model` through [`persistence.KnowledgeBaseRepository`](persistence/KnowledgeBaseRepository.java)
  (the `(tenant_id, id)` read — a cross-tenant collection is indistinguishable from a missing one),
  embeds the query through the neutral `ai.application.EmbeddingPort` (opt-in, `ADR-014`), and returns
  the closest chunks as `ChunkDto`s inside a `SearchResult`. The caller's `topK` falls back to
  `agentflow.retrieval.default-top-k` and is clamped to `max-top-k`; candidates beyond the configured
  cosine `threshold` are dropped.
- [`persistence.DocumentChunkRepository.findNearest`](persistence/DocumentChunkRepository.java) keeps
  the access predicate **inside** the query — `WHERE c.tenant_id = ? AND d.knowledge_base_id = ?`
  (a `documents` join), before the ordering and the limit, never a post-filter — and excludes chunks
  with a `NULL` embedding; the cosine distance is selected as a plain `float8` column (the stored
  `vector` is never read back).
- The fragment text of a `ChunkDto` is **untrusted content** (invariant 7): it is fed to a model only
  as `USER` content and is never logged, hashed or written to metrics/traces (invariant 8).
- `knowledgebase.application.config.RetrievalConfiguration` wires the service; both repositories are
  `@ConditionalOnBean(DataSource.class)` and the port is opt-in, so a default boot has neither and the
  service fails loudly at call time (S01-02).

## Data ownership

`knowledgebase` owns exactly one table (plus related) and no other module may change its migration or schema: `knowledge_bases`, `documents`, `document_chunks`.

## Package layout (internal vs public)

```text
com.example.agentflow.knowledgebase/               ← public API boundary
├── domain/          pure model + invariants        internal
├── application/     use cases, transactions         public services + DTOs
├── evaluation/      RAG eval corpus + runner        internal (S10-20)
├── web/             controllers + API DTOs          internal
├── persistence/     repositories, mappers, SQL       internal
└── worker/          Kafka listeners                 internal
```

`persistence`, `web`, `worker`, `domain` and `evaluation` are internal: another module must not import them
(enforced by the architecture test, `S02-08` / `S02-09`).

## Rules

- Business rules stay in `application` (transactions) or `domain` (invariants); never in `web` or
  infrastructure.
- A new `module → module` edge requires an ADR (root `AGENTS.md`).
