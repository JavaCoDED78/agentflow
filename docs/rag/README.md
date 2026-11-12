# `docs/rag/` — grounding answers in a tenant-scoped knowledge base

This document describes **retrieval-augmented generation (RAG)** in AgentFlow: how a model's
answer is grounded in a tenant's own indexed documents instead of the model's training data. It
separates what the code does today from what the plan fixes later, so a reader never mistakes a
planned step for an implemented one.

The shared terms live in [`domain-glossary.md`](../domain-glossary.md) (§7: *Retrieval*, *Citation*,
*Embedding*); the module boundaries that own each step live in
[`architecture/module-map.md`](../architecture/module-map.md) and
[`architecture/module-api.md`](../architecture/module-api.md).

> One source of truth: this document links to the classes and the other docs; it does not copy
> their behaviour. When a statement here and a linked document disagree, the code is the final word.

## What RAG is for AgentFlow

RAG has two directions that run at different times:

1. **Indexing (writing vectors).** A document is extracted and chunked (`ingestion`, `S10-09`), each
   chunk is embedded into a fixed-size vector (`ai` embedding port), and the chunk *and* its vector
   are written to the owning table (`knowledgebase`).
2. **Retrieval (reading vectors).** A user query is embedded the same way and matched against the
   tenant's stored vectors; the closest chunks are pulled back and fed to the model as *context*,
   and every claim the model makes is attributed to a real retrieved chunk (a *citation*).

Both directions are bound by three invariants that shape every line of the pipeline (see
*Invariants* below).

## The pipeline (implemented vs. planned)

The diagram separates the two. **Implemented** steps exist in code (`S10-11` storage + query
primitive, `S10-12` embedding batching, `S10-13` the indexing path, `S10-14` the tenant/ACL-
filtered vector retrieval service, `S10-15` the lexical (full-text) half over the PostgreSQL FTS
index, `S09` planner). **Planned** steps (`S10-16` hybrid rank fusion, `S10-17` citations,
`S10-18` the no-evidence policy) are described in prose below and are *not* yet in the code.

```mermaid
flowchart TB
    subgraph Impl["Implemented in code"]
        Repo["knowledgebase.persistence<br/>DocumentChunkRepository<br/>insert · scoped findNearest + distance<br/>(S10-11 / S10-14)"]
        Embed["ai.application<br/>EmbeddingPort · embed → float[]<br/>(S09-01)"]
        Ing["ingestion.application<br/>IngestionService + EmbeddingGenerationService<br/>claim → embed → ON CONFLICT upsert<br/>(S10-12 / S10-13)"]
        Ret["knowledgebase.application<br/>RetrievalService (S10-14)<br/>vector half: tenant + KB predicate in-query · top-k bound · threshold"]
        Lex["knowledgebase.application<br/>RetrievalService.lexicalSearch (S10-15)<br/>lexical half: ts_rank over the English FTS index · tenant + KB predicate in-query"]
        Planner["ai.application<br/>PlannerService · propose<br/>(ADR-006, S09-06)"]
        Catalogue["ai.prompt<br/>PromptCatalogue · SYSTEM policy<br/>+ USER untrusted content"]
    end
    subgraph Planned["Planned — S10-16 … S10-18"]
        Hybrid["hybrid fusion<br/>vector + FTS rank fusion<br/>only the S10-16 step"]
        Cit["assemble citations from<br/>real chunks only (never invented)<br/>empty → InsufficientEvidence (S10-17 / S10-18)"]
    end
    Doc["document_chunks.embedding<br/>vector(768) · nomic-embed-text"]

    %% Indexing path (writing vectors)
    Ing -->|"batched embed calls"| Embed
    Embed -->|"768-d vector"| Ing
    Ing -->|"chunk row + vector"| Repo
    Repo --> Doc

    %% Retrieval path (reading vectors + lexical index)
    Q["query"] --> Embed
    Embed -->|"768-d query vector"| Ret
    Ret --> Repo
    Lex -->|"ts_rank over the FTS index (no embedding)"| Repo

    %% Grounding path (feeding the model)
    Ret --> Hybrid
    Lex --> Hybrid
    Hybrid --> Cit
    Cit -->|"chunks + citations<br/>(untrusted USER content)"| Catalogue
    Planner --> Catalogue
    Planner -->|"validate-before-execute<br/>(S09-07)"| Backend["backend authorizes +<br/>executes (never the model)"]
```

## Invariants that shape retrieval

- **Tenant isolation is in the query, not a post-filter (invariant 1).** The tenant comes only from
  the verified security context. The nearest-neighbour query puts `WHERE tenant_id = ?` *first*,
  before the distance ordering and the limit, so another tenant's chunk can never surface as a
  "close" match. A cross-tenant retrieval result set is empty, exactly as a cross-tenant lookup of
  any other resource is (`[ADR-007](../adr/ADR-007-tenant-isolation.md)`).
- **The model is an untrusted planner, and retrieved content is untrusted (invariant 7).** A model
  can never change its own permissions, policies or allowlist (`ADR-006`); retrieved document
  fragments are *prompt-injection surface* and are sent to the model as untrusted `USER` content,
  never as policy. Only **real retrieved chunks** are cited; an empty retrieval reports
  *insufficient evidence*, never an invented citation.
- **PostgreSQL is the source of truth (invariant 2).** The vector lives in the owning table with
  the chunk — there is no side table and no cache standing in for a missing run.
- **No secrets in observability (invariant 8).** The injected untrusted content (retrieved
  fragments) is never logged, hashed or otherwise exposed by the prompt layer; only the prompt
  *policy* version is observable.

## Implemented: the storage + query primitive (`S10-11`)

`S10-11` owns the two things the glossary calls *retrieval* that a database can do directly:

- **Storage** — `document_chunks.embedding`, a fixed-dimension `vector(768)` on the table `S10-10`
  created (migration `V3_3_20`, owner `knowledgebase`). The dimension is pinned here so the column
  rejects any embedding of a different width at the database layer — a mismatched insert fails,
  never a post-check. The column is **nullable**: a chunk is created during ingestion (`S10-09`)
  and may be indexed only later (`S10-13`), so a row can predate its vector; `vector(768)` still
  fixes the width of anything stored. The 768 dimensions belong to the `nomic-embed-text` model,
  whose id is pinned on `knowledge_bases.embedding_model` (`S10-05`).
- **The query primitive** — `knowledgebase.persistence.DocumentChunkRepository` (plain
  `JdbcTemplate` on Spring Data JDBC, `ADR-009`; registered only when a `DataSource` exists, so the
  app boots without a database). It does two things:
  - `insert(...)` persists a chunk row together with its 768-d embedding, bound as a pgvector text
    literal (`[v1,v2,…,vN]`, the PostgreSQL-array syntax pgvector 0.8.x parses) cast `::vector`.
  - `findNearest(tenantId, knowledgeBaseId, query, limit)` returns the closest chunks for one
    tenant inside one knowledge base in ascending distance order — `WHERE c.tenant_id = ? AND
    d.knowledge_base_id = ? AND c.embedding IS NOT NULL ORDER BY c.embedding <=> ?::vector LIMIT ?`
    (the collection scope and the NULL-embedding exclusion are the `S10-14` evolution of the
    primitive). The cosine distance is selected as a plain `float8` column; the stored vector is
    **not** selected, so no `vector` column needs a driver type adapter.

This adapter is the *primitive*; it persists and reads rows. The retrieval service that drives it
(`S10-14`, below) adds the collection scope, the top-k bound and the relevance threshold on top.
The service also exposes the lexical half — `RetrievalService.lexicalSearch` over the S10-15 FTS
index, mirroring `findNearest` with `c.tenant_id = ?` and `d.knowledge_base_id = ?` before the
`@@` match and the limit — but the two halves still return independently. Only the fusion (`S10-16`)
and citations (`S10-17`) remain planned. The indexing path that writes through it is `S10-13`, implemented.

## Embedding production (`S10-12` / `S10-13`)

A chunk's vector is produced through the neutral outbound port `ai.application.EmbeddingPort`
(embeddings are one `embed(EmbeddingRequest)` returning `float[]` per input; `ADR-014`). The
`ingestion` module produces the chunk set (`S10-09`), and the embedding generation service
(`S10-12`, `ingestion.application.embed.EmbeddingGenerationService`) batches the chunk texts to the
port (`agentflow.ingestion.embedding.batch-size`), preserves input order across batches and
validates every response (one vector per text, uniform width) before returning the aggregated
`EmbeddingBatchResult`. Provider rate limits and transient faults are retried by the `ai`
resilience layer (`ai.provider.ResilientEmbeddingModel` over `ProviderResiliencePolicy`), never by
the caller. The indexing path (`S10-13`) drives this service and persists each chunk row with its
vector through `knowledgebase.application.ChunkIndexingService` — an `ON CONFLICT` upsert on the
`(document_id, chunk_index, pipeline_version)` unique key, so a redelivered event never duplicates
a chunk. The provider adapter is the only code that knows the embedding SDK.

## Implemented: the vector retrieval service (`S10-14`)

`knowledgebase.application.RetrievalService` is the read entry point: `search(tenantId,
knowledgeBaseId, SearchRequest)` resolves the collection's pinned `embedding_model`, embeds the
query through the `ai` `EmbeddingPort`, and returns the closest chunks as `ChunkDto`s inside a
`SearchResult` — with the access predicate inside the query and both bounds applied on top:

- **Tenant *and* collection scope, in the query.** `DocumentChunkRepository.findNearest` runs
  `WHERE c.tenant_id = ? AND d.knowledge_base_id = ?` (a `documents` join) before the distance
  ordering and the limit — a post-filter is never permitted, so another tenant's or another
  collection's chunk can never surface, no matter how close its embedding; a cross-tenant
  knowledge base is indistinguishable from a missing one and simply retrieves nothing. Rows with a
  `NULL` embedding (created, not yet indexed) are excluded, so an unindexed chunk never appears
  with a bogus zero distance.
- **Bounded.** A missing `topK` falls back to `agentflow.retrieval.default-top-k` (10), a caller
  `topK` is clamped to `max-top-k` (50), and candidates whose cosine distance exceeds `threshold`
  (0.5) are dropped. The distance arrives as a plain `float8` column computed in SQL — the stored
  `vector` is still never read back.
- **Opt-in seams.** With no database or no embedding provider the service still constructs (S01-02
  / `ADR-014`) and fails loudly at call time; the wiring lives in
  `knowledgebase.application.config.RetrievalConfiguration`.

The public vocabulary (`SearchRequest`, `SearchResult`, `ChunkDto`) is what the citation layer
extends, and the fragment text is untrusted content — never logged (invariants 7/8).

## Planned: hybrid fusion, citations and grounding (`S10-16` … `S10-18`)

The following is the target the glossary §7 and `module-api.md` name. The fusion, citations and
no-evidence layers are **not yet in the code** (no `InsufficientEvidence`, no `CitationDto`). The
vector half (`S10-14`) and the lexical half (`S10-15`) are implemented and described above; this
section describes the remaining fusion steps so the RAG guide is complete, not because they run
today.

- **Full-text search (lexical half, `S10-15`).** A `tsvector`/`ts_rank` lexical half over a
  PostgreSQL `english` FTS index, driven by `RetrievalService.lexicalSearch` — the complement of
  the vector half, returning the closest chunks by `ts_rank` relevance. Implemented.
- **Hybrid fusion (`S10-16`).** Fuses the two independent halves by a simple rank fusion over the
  `ts_rank` and cosine scores the two halves return, deduplicated and capped — still **planned**.
- **Citations (`S10-17`).** Builds each citation from a *real* retrieved chunk — the response-field
  set the glossary names (`documentId`, `chunkId`, page/section, `score`).
- **No-evidence policy (`S10-18`).** An empty retrieval returns `InsufficientEvidence`; the model
  never invents a citation.
- **Grounding the model.** Feeds the retrieved chunks and their citations to the planner as the
  untrusted `USER` content. The planner (`S09-06`) already separates a fixed `SYSTEM` policy from a
  single untrusted `USER` message (`ai.prompt.PromptCatalogue`); the retrieval layer is what fills
  that `USER` message with retrieved fragments. A model reference is never execution and never
  permission — the backend re-checks the tool registry and the caller's scopes before any step runs
  (`S09-07`).

## Verification

- **Implemented (`S10-11`).** The storage + query primitive is covered by the integration test
  `apps/agentflow-app/src/integrationTest/java/com/example/agentflow/knowledgebase/persistence/DocumentChunkRepositoryIT.java`
  (4 tests) and by `MigrationsTest` (the pgvector extension and the `embedding vector(768)` column)
  on `pgvector/pgvector:0.8.6-pg18` — the exact image Compose and Testcontainers use, never `H2`
  (`AGENTS.md`). `:apps:agentflow-app:check` is green (unit + architecture + formatting + OpenAPI).
- **Implemented (`S10-13`).** The indexing path is covered by
  `apps/agentflow-app/src/integrationTest/java/com/example/agentflow/ingestion/IngestionIndexingIT.java`
  (8 tests) on the same `pgvector/pgvector:0.8.6-pg18` image: a redelivered ingestion event
  creates no duplicate logical chunks, the SKIP/UPDATE redelivery policy is honored, and a
  transient provider fault leaves the document requeued with the event held.
- **Implemented (`S10-14`).** The vector retrieval layer is covered by
  `apps/agentflow-app/src/integrationTest/java/com/example/agentflow/knowledgebase/application/RetrievalServiceIT.java`
  (7 tests) and the reworked
  `apps/agentflow-app/src/integrationTest/java/com/example/agentflow/knowledgebase/persistence/DocumentChunkRepositoryIT.java`
  (10 tests: the S10-14 collection/scope/threshold/top-k/NULL-embedding probes plus the S10-15
  lexical probes) on the same image, driving the real service, repositories and Flyway-migrated
  schema with a deterministic in-memory embedding port: the S10-14 acceptance — a cross-tenant query
  returns empty even when the foreign chunks carry *identical* embeddings — plus the knowledge-base
  scope, the relevance threshold, the top-k cap and the NULL-embedding exclusion.
- **Implemented (`S10-15`).** The lexical half is covered by
  `apps/agentflow-app/src/integrationTest/java/com/example/agentflow/knowledgebase/application/RetrievalServiceTest.java`
  (lexical probes — the unit suite is now 16) and by the same
  `DocumentChunkRepositoryIT.java` lexical probes (the full suite is now 10): a `english` FTS query
  finds the exact term `retention` and an ID-like token `555566667777` in the corpus, and is
  tenant- and collection-scoped; `MigrationsTest` asserts the `document_chunks_search_idx` index and
  the generated `search_vector` `tsvector` (`is_generated = 'ALWAYS'`) with its `S10-15` COMMENT.
- **Planned (`S10-16` … `S10-18`).** The hybrid fusion, citation and no-evidence layers must add
  their own integration tests on the same real-PostgreSQL image, covering the mandatory negative
  scenarios from `AGENTS.md` that are still open: prompt injection in a retrieved document, and
  **empty retrieval without invented citations**. These tests are a requirement of those steps, not
  a claim about `S10-14` / `S10-15`.

## Ownership and references

| Step | Owns | See |
|---|---|---|
| `embedding` column + nearest-neighbour primitive | `knowledgebase` | `DocumentChunkRepository`, migration `V3_3_20` |
| FTS lexical index + lexical retrieval half | `knowledgebase` | `DocumentChunkRepository`, `RetrievalService.lexicalSearch`, migration `V3_3_21` |
| Embedding production (`float[]`) | `ai` | `EmbeddingPort` (`ADR-014`) |
| Indexing pipeline (claim → embed → idempotent upsert) | `ingestion` | `IngestionService`, `EmbeddingGenerationService`, `knowledgebase.application.ChunkIndexingService` (`S10-12` / `S10-13`) |
| Vector + lexical retrieval (tenant/ACL-filtered, bounded) | `knowledgebase` | `RetrievalService` (`S10-14` / `S10-15`) |
| Planner (propose; never execute), prompt separation | `ai` | `PlannerService`, `PromptCatalogue` (`ADR-006`) |
| Citation value object | `toolexecution` | `Citation` (`S08-06`) |
| Planned hybrid fusion, citations, no-evidence policy | `knowledgebase` | `module-api.md`, glossary §7 |

- [`domain-glossary.md`](../domain-glossary.md) §7 — *Retrieval*, *Citation*, *Embedding* (shared
  terms, one concept — one name).
- [`architecture/module-map.md`](../architecture/module-map.md) — bounded contexts and owners.
- [`architecture/module-api.md`](../architecture/module-api.md) — the public API per module,
  including the planned retrieval types.
- [`architecture/document-lifecycle.md`](../architecture/document-lifecycle.md) — the
  `UPLOADED → QUEUED → PROCESSING → INDEXED` lifecycle a chunk and its embedding ride on.
- [`adr/ADR-006-untrusted-planner.md`](../adr/ADR-006-untrusted-planner.md),
  [`adr/ADR-007-tenant-isolation.md`](../adr/ADR-007-tenant-isolation.md),
  [`adr/ADR-009-…`](../adr/ADR-009-single-persistence-spring-data-jdbc.md) — the planner, tenant
  isolation and persistence invariants.
