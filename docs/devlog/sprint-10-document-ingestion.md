# Changelog


# Sprint 10 — Document ingestion, pgvector, hybrid retrieval and citations


## S10-11 — Enable pgvector and the tenant-scoped nearest-neighbour search (768-d, `nomic-embed-text`).

### What changed
- Migration `V3_3_20` (`classpath:migrations`, step 21 of the ordered Flyway line, owner `knowledgebase`), applied cleanly against `pgvector/pgvector:0.8.6-pg18` (the exact image Compose and Testcontainers use — never `H2` per `AGENTS.md`): `CREATE EXTENSION IF NOT EXISTS vector` (idempotent, so a fresh Testcontainers image and the controlled apply stay quiet — `ADR-008` forward-only, changes nothing already applied) then `ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS embedding vector(768)` — the embedding column the S10-10 schema left for S10-11.
- `document_chunks.embedding` is **nullable** (`ADD COLUMN ... vector(768)`, no `NOT NULL`): a chunk is created by ingestion (`S10-09`) and indexed only later (`S10-13`), so a row may predate its vector and a failed or pending embedding leaves it without one — but the `vector(768)` type still pins the width, so anything stored must be 768-d and a mismatched-width insert fails at the database layer (never a post-check; `S10-13` / `S10-14` rely on this guard); no `DEFAULT` is needed, and the dimension 768 is the `nomic-embed-text` model the S10-05 / `knowledge_bases.embedding_model` fixes. The migration carries an `S10-11` / `knowledgebase` COMMENT.
- The persistence adapter — `knowledgebase.persistence.DocumentChunkRepository` (`@ConditionalOnBean(DataSource.class)`, so it boots without a database) on plain `JdbcTemplate` / Spring Data JDBC (`ADR-009`) — does **not** add pgvector-jdbc: the plain driver cannot map a `vector` column to `float[]`, and retrieval never reads the stored vector back (rows already arrive relevance-ordered by the ordering clause). The adapter is the one owner of `document_chunks` write/read outside the migration; it does not touch `documents`, `knowledge_bases` or the ingestion worker.
- **`insert(...)`** persists one chunk with its embedding: the vector is bound as a pgvector 0.8.x literal (`[v1,v2,...,vN]`, the PostgreSQL-array syntax 0.8.x parses) cast `::vector`, and the 768 width is refused in Java first (`IllegalArgumentException`) so the failure is explicit and dependency-free.
- **`findNearest(tenantId, query, limit)`** runs the tenant-scoped nearest-neighbour search: the `tenant_id` predicate is the first clause (`WHERE tenant_id = ?`), so the query never sees another tenant's rows (invariant 1 — a foreign chunk is indistinguishable from a missing one, so a cross-tenant search returns empty), the rows are ordered by distance to the query vector and `limit` caps them, and no `vector` column is mapped. The projection is `DocumentChunkRow` — the tenant/document/chunk binding for a citation plus text and pages, no embedding.

### Why
The glossary "Retrieval" names tenant/ACL-filtered nearest-neighbour search as the retrieval primitive, and the S10-11 plan pins the engine (pgvector) and the model/dimension (`nomic-embed-text`, 768). Postgres is the source of truth (invariant 2): the vector lives in the owning table with the chunk, not a side table, so a chunk and its embedding are one row and one `(tenant_id, document_id)` binding. A single transition / single owner keeps the boundary acyclic (`workflowexecution` never imports the `knowledgebase` persistence layer). The nullable column keeps the two-phase lifecycle (chunk at ingestion, index at `S10-13`) working: a chunk never fails to be created while its embedding is still pending. The tenant predicate in the `WHERE` (not a post-filter) is invariant 1 in the retrieval path — the ACL filter runs before content is sent to the model.

### Verification
`pgvector/pgvector:0.8.6-pg18` — the exact image Compose and Testcontainers use, never `H2`:
- The migration-line suite (`MigrationsTest`) asserts the exact ordered line now at `v3.3.20`, that the `vector` extension is enabled, that `document_chunks.embedding` is `vector(768)` (nullable) with an `S10-11` / `knowledgebase` COMMENT, and that the S10-10 tenant-scoped chunk test still lands.
- The new [`DocumentChunkRepositoryIT`](apps/agentflow-app/src/integrationTest/java/com/example/agentflow/knowledgebase/persistence/DocumentChunkRepositoryIT.java) (4 tests) proves the adapter end-to-end against that image: a nearest-neighbour query executes and returns the closest chunk first (a `+x` query ranks a `+x` chunk over a `+y` one), the search is tenant-scoped (a `+y` chunk in another tenant never surfaces for a `+x` query), the fixed 768 dimension rejects a 769-width vector at the database layer (bypassing the Java guard), and a wrong-width query vector is refused in Java.

`:apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest`, `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + formatting (`spotless`) + OpenAPI gates; `git diff --check` clean; the migration-line integration suite (`:apps:agentflow-app:integrationTest --tests '*MigrationsTest|DocumentChunkRepositoryIT*' --rerun-tasks`) is green, JUnit XML checked: `MigrationsTest` `tests="18"` + `DocumentChunkRepositoryIT` `tests="4"`, all `failures="0"` `errors="0"` `skipped="0"`.

### Not changed
No `documents`, `knowledge_bases` or ingestion change; no contract, dependency, or module-boundary change — only the `document_chunks` column and the `knowledgebase` adapter that reads it. The `MigrationsTest` `document_chunks_are_tenant_scoped_and_a_duplicate_is_forbidden_by_constraint` test is unchanged (it inserts a chunk without an embedding, which the nullable column now allows).

## S10-10 — Create the document_chunks schema.

### What changed

- [`apps/agentflow-app/src/main/resources/migrations/V3_3_19__create_document_chunks.sql`](../../apps/agentflow-app/src/main/resources/migrations/V3_3_19__create_document_chunks.sql) — the migration. It is step 20 of the ordered Flyway line (`V3_3_18` … `V3_3_19`), owner `knowledgebase`, and applies cleanly against PostgreSQL 18 (pgvector `pgvector/pgvector:0.8.6-pg18`) — the exact image Compose and Testcontainers use.

`document_chunks` (one row per deterministic fragment of a document; glossary "Chunk"):

| column | type | constraint / default | meaning |
|---|---|---|---|
| `id` | `UUID` | PK | chunk id (assigned by the persistence layer) |
| `tenant_id` | `UUID` | FK `tenants.id`, `NOT NULL` | owning tenant (isolation by `(tenant_id, document_id)` read) |
| `document_id` | `UUID` | FK `documents.id`, `NOT NULL`, `ON DELETE CASCADE` | parent document; fragments leave with the document |
| `pipeline_version` | `TEXT` | `NOT NULL DEFAULT 'v1'` | chunking pipeline version; part of the `(document_id, chunk_index, pipeline_version)` unique + the `S10-13` / `S10-19` idempotency key |
| `chunk_index` | `INTEGER` | `NOT NULL` | zero-based position within the document's chunk set |
| `page_from` | `INTEGER` | `NOT NULL` | first page the fragment spans (≥ 1) |
| `page_to` | `INTEGER` | `NOT NULL` | last page the fragment spans (≥ `page_from`) |
| `content` | `TEXT` | `NOT NULL` | the fragment text (whitespace-normalised) |
| `content_hash` | `TEXT` | `NOT NULL` | SHA-256 hex fingerprint; the idempotency seed `S10-13` keys on |
| `metadata_json` | `JSONB` | nullable | optional chunk metadata (e.g. embedding dimensionality / model) |
| `created_by` | `TEXT` | nullable | audit |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` | audit (immutable row — no `version`, no `updated_at`) |

`document_chunks` is created after `documents` (`V3_3_18`) so the `document_id` foreign key resolves. One business unique — `document_chunks_document_index_version` on `(document_id, chunk_index, pipeline_version)` — forbids a duplicate chunk within a version (the `S10-10` acceptance). One lookup index `document_chunks_tenant_document_idx` on `(tenant_id, document_id, chunk_index)` backs the tenant-scoped chunk list in creation order (the tenant filter is mandatory on chunk search, invariant 1). No `version` column: a chunk is append-only, created once per (document, position, pipeline), guarded by the unique constraint rather than an optimistic-lock counter — the mirror of the immutable `knowledge_bases` row.

### Why

- **Uniqueness is the acceptance, and it is a DB guard.** One document has exactly one chunk per (position, pipeline), so the composite unique makes a repeated chunk set (a redelivery, `S10-13`) impossible to persist twice — the database rejects the duplicate, not a post-insert check. `pipeline_version` is in the key so a retuned chunking config reindexes the document (`S10-19`) instead of colliding with the old chunks.
- **Tenant scope and cascade by construction.** `tenant_id` is the isolation key (invariant 1 — another tenant's chunk is indistinguishable from a missing one through the `(tenant_id, document_id)` read), and `document_id` cascades so the two-phase document lifecycle removes the fragments with the document (`document-lifecycle.md`).
- **Embedding deferred to `S10-11`.** The glossary names the `embedding` vector column too, but pgvector is not enabled until `S10-11`, so the migration declares `metadata_json` (embedding dimensionality / model) and defers the vector — the same pattern `S10-05` used for `embedding_model` (declared, dimensionality fixed by `S10-11`).

### Verification

All of it ran against a live PostgreSQL 18 container (`pgvector/pgvector:0.8.6-pg18`), the same image Compose and Testcontainers use. The migration line applied cleanly as step 20.

- **Schema.** The `MigrationsTest` integration suite (JUnit XML checked: `tests="17"` `failures="0"` `errors="0"` `skipped="0"`) asserts the exact ordered line now at `v3.3.19`, that `document_chunks` is an owning table, that the `(document_id, chunk_index, pipeline_version)` unique (`document_chunks_document_index_version`), the `(tenant_id, document_id)` + `(tenant_id)` foreign keys, the `(tenant_id, document_id, chunk_index)` index (`document_chunks_tenant_document_idx`) and the `S10-10`·`knowledgebase` table COMMENT all exist.
- **Data (the `S10-10` acceptance).** `document_chunks_are_tenant_scoped_and_a_duplicate_is_forbidden_by_constraint` builds a tenant → knowledge base → document chain and a chunk that lands, then: a second chunk with a different `chunk_index` lands; a `(document_id, chunk_index, pipeline_version)` duplicate with different content is rejected by the unique constraint; the same fragment is allowed again under a different `pipeline_version`; and a chunk with a missing parent document is rejected by the foreign key. Every probe rolls back.

`git diff --check` clean. The `knowledgebase` module boundary, the OpenAPI contract and the Java main sources are unchanged — this is a schema + documentation change (the persistence layer that writes chunks is `S10-13`), so `:apps:agentflow-app:check` is green — unit + architecture (`ArchitectureBoundaryTest`, `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + `spotless` + OpenAPI gates. The migration-line integration suite `:apps:agentflow-app:integrationTest --tests '*MigrationsTest*' --rerun-tasks` is green.

### Not changed

- The persistence layer that binds `(tenant_id, document_id)` and writes chunks (the `DocumentChunkRepository` / mapper), the embedding vector (`S10-11`), the ingestion indexing worker and ingestion idempotency — `S10-11` … `S10-13`. The domain glossary §7 already names the `document_chunks` columns verbatim, so it needs no edit; `docs/architecture/module-map.md` already lists `document_chunks → knowledgebase` with the unique constraint.

---
## S10-09 — Deterministic chunking (P0).

### What changed

- [`ingestion/domain/ChunkingConfig.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/domain/ChunkingConfig.java): the immutable, validated chunking descriptor — `strategy`, `maxChunkChars` (`> 0`), `overlapChars` (`>= 0` and `< maxChunkChars`). The canonical constructor validates (a nonsensical combination fails construction, so the wiring fails startup) and `pipelineVersion()` derives a **deterministic** self-describing label (`chunking:character-boundary:cs=<n>:ov=<m>`) from the parameters &mdash; it changes only when a chunk-affecting parameter changes, so a retuned configuration invalidates the old chunks on reindex (the seed `S10-13` / `S10-19`). `equals` / `hashCode` / `toString` cover all parameters.
- [`ingestion/domain/Strategy.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/domain/Strategy.java): the single active strategy, `CHARACTER_BOUNDARY` &mdash; the base strategy `S10-09` chose. Chosen over paragraph / pure-token because it produces natural, size-bounded chunks for both prose and token streams and is fully deterministic.
- [`ingestion/domain/DocumentChunk.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/domain/DocumentChunk.java): the immutable domain mirror of the later `document_chunks` row &mdash; `chunk_index`, `page_from`, `page_to`, `content`, `content_hash`, `pipeline_version`. Tenant-free (the `(tenant_id, document_id)` binding is the persistence layer, `S10-10`); validated (index `>= 0`, `page_from >= 1`, `page_to >= page_from`, non-blank content / hash / version). `content_hash` is a SHA-256 hex fingerprint, the idempotency seed `S10-13` keys on.
- [`ingestion/application/chunk/Chunker.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/application/chunk/Chunker.java): the pure, DB-/Kafka-free algorithm. It recovers pages from the PDFBox form-feed separator (text payloads are a single page), tokenises each page on whitespace, and greedily packs the tokens into the fewest chunks within `maxChunkChars` (never breaking a word; a word longer than the budget stays whole as its own chunk), carries `overlapChars` of context forward, and records per chunk the index, page range, the SHA-256 content hash (over the whitespace-normalised content) and the `pipeline_version`. **One input plus one configuration always yield the same chunk set** — no clock, no randomness, ordered lists only, fixed separators. `chunk(null)` / blank text / `EMPTY` result return an empty list (never throws).
- [`ingestion/application/config/DocumentChunkingConfiguration.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/application/config/DocumentChunkingConfiguration.java): wires the `Chunker` bean from `agentflow.ingestion.chunking.*` at startup (a `@Component` would be wrong here, exactly as with the extraction parser). Homed in `ingestion` (not the global `config` package) so `config` never imports the `ingestion.domain` `Strategy` enum &mdash; that edge would cycle back through `ingestion`; the config strategy string is mapped there to `Strategy.CHARACTER_BOUNDARY`.
- [`config/AgentFlowProperties.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/config/AgentFlowProperties.java): the typed, validated `agentflow.ingestion.chunking.*` group (`max-chunk-chars` default `500`, `overlap-chars` default `50`, `strategy` default `"character-boundary"`), bound with the same `@ConfigurationProperties` / `@Validated` path as every other `agentflow.*` group. The strategy is a `String` on purpose (the `config` package must not import the `ingestion.domain` enum).

### Why

- **Determinism is the whole point.** A chunk set is the input to embedding and indexing; the same document and the same pipeline must always yield the same chunks so that re-running ingestion, diffing two runs, or keying idempotency on `pipeline_version` is predictable. A character-budget, word-boundary-aware strategy with a derived version label delivers that without a model, randomness or map iteration.
- **Config, not magic numbers.** The chunk size and overlap are tunable (the `ingestion` AGENTS.md: lease / retry / limits live in configuration), validated at startup so a nonsensical combination fails boot rather than silently accepting everything.
- **The chunk is a pure domain value.** It carries only what `S10-09` produces; the tenant / document binding and the embedding are later concerns, owned by `knowledgebase` / `ingestion` persistence, so the module boundary and the tenant-isolation invariant are untouched.

### Verification

Two DB-free suites, **18 tests** (JUnit XML checked: `ChunkerTest` `tests="11"` + `ChunkingConfigTest` `tests="7"`; all `failures="0"` `errors="0"` `skipped="0"`). **Determinism** (the `S10-09` acceptance) is pinned first: two independently constructed `Chunker`s over the same `ExtractionResult` return equal chunk lists (field-everywhere `equals`, so index / pages / hash / version all match) with a byte-stable content hash and the `pipeline_version` recorded on every chunk. The rest pin the character budget with word boundaries, a single word longer than the budget kept whole as its own chunk, the overlap tail (`"fox "`) prepended to the next chunk, the page range spanning the pages a fragment reaches (a `\f`-separated three-page text → `pageFrom=1`, `pageTo=3`), the SHA-256 content hash against a known vector, zero-based ordered indices, and the empty / blank / null-input case (no chunks, no throw). `ChunkingConfigTest` pins the size / overlap validation and the deterministic `pipeline_version` (equal params ⇒ equal version; a resize changes it). `./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ingestion*' --rerun-tasks` and the full `./gradlew check` are green &mdash; unit + architecture (`ArchitectureBoundaryTest`, `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + `spotless` + OpenAPI gates; `git diff --check` clean.

### Not changed

- No schema: the `document_chunks` table is `S10-10`. No Java/Kafka/OpenAPI change beyond the config group and the bean; no new module edge (the `config` → `ingestion` edge already existed via `DocumentExtractionConfiguration`, and this adds only `ingestion` → `config` + intra-module edges, so the graph stays acyclic). The domain glossary §7 already names the `document_chunks` columns verbatim, so it needs no edit.

---
## S10-08 — Persist safe parsing diagnostics (P0)

### What changed

- [`apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/domain/ExtractionResult.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/domain/ExtractionResult.java): the immutable result is now a full extraction-diagnostic record. Every state carries `parserVersion()` (the Tika library version, e.g. `tika:2.9.2`, never null), the stable `ExtractionError.Code` of a failure, the `pageCount()` read and the `charCount()` actually extracted; a new `isPartialSuccess()` distinguishes a page/character-limited `EXTRACTED` (partial success, `truncated()`) from a real `FAILED`. The constructor now requires `parserVersion` (never null), and it participates in `equals`, `hashCode` and `toString`.
- [`apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/application/parser/TikaDocumentParser.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/application/parser/TikaDocumentParser.java): the parser computes `parserVersion` once (the Tika implementation version read from its jar manifest via `Package#getImplementationVersion`, with an `unknown` fallback) and injects it into every result it returns — success, empty, truncated and both failure classifications. The Tika type stays confined to this class (the only import past the `DocumentParser` port).

### Why

- The failure cause must be recoverable from the persisted record alone. `error_code` and `error_summary` (S10-07) already make the corrupt-vs-encrypted distinction queryable without a stack trace or the raw file; adding the parser version lets an operator correlate a failed or partial extraction with the exact engine version that produced it, which is the diagnostic the plan calls for (never a document fragment, a stack trace or a secret, invariant 8).
- Partial success is not a failure. A page/character limit is bounded and expected (`truncated()` / `isPartialSuccess()`), so the ingestion side keeps processing the document instead of retrying a "failure".

### Verification

The `TikaDocumentParserTest` suite (JUnit XML checked: root `tests="3"` + `Limits` `tests="2"` + `Failures` `tests="2"` + `Configuration` `tests="1"` + new `Diagnostics` `tests="4"`; all `failures="0" errors="0" skipped="0"`, 12 in total) asserts the parser version on a success, a partial extraction, a failure and an empty payload, that the corrupt-failure summary carries no line break / carriage return / raw `%PDF` fragment and is length-capped, and that a page limit is a partial success, not a failure. The exact value `tika:2.9.2` was confirmed with a throwaway probe test that asserted it explicitly (removed after verification). `./gradlew :apps:agentflow-app:test --tests '*TikaDocumentParserTest*' --rerun-tasks` and the full `./gradlew check` are green — unit + architecture (`ArchitectureBoundaryTest`, `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + `spotless` + OpenAPI gates; `git diff --check` clean.

### Not changed

- The `documents` parser-metadata persistence (`page_count`, `error_code`, `error_summary`) is written by the ingestion worker's persistence layer when it lands (`S10-13`); this task enriches the diagnostic the worker will persist. A `parser_version` / `char_count` column is not added now: the DB layer is not yet built, and `char_count` already lives on the result.

---

## S10-07 — The PDF and text extraction adapter.

### What changed

- [`apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/application/DocumentParser.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/application/DocumentParser.java) — the neutral outbound port (`ingestion.application`). `parse(originalName, contentType, payload)` returns an `ExtractionResult` for **every** input (success, empty or a classified failure) and never throws for a parseable-but-bad document: the ingestion worker depends on the contract, never on a concrete parser, so the extraction engine (Tika / PDFBox) can be replaced without touching the worker. The javadoc records that a payload reaching the parser has already passed upload validation (`knowledgebase.domain.DocumentContentType`), so the parser only reads it, not re-checks it.
- [`apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/application/parser/TikaDocumentParser.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/application/parser/TikaDocumentParser.java) — the sole class in the codebase that imports a Tika / PDFBox API. Magic-byte content-type detection via `Tika#detect` (the client filename / content type is a hint, never trusted for the decision — invariant 1); `application/pdf` is extracted with PDFBox (`PDFTextStripper`, page-limited) and its page count; everything else is decoded directly as UTF-8 text. Two bounded, configurable limits: `maxPages` caps the pages PDFBox reads (pages beyond it are not read — the multi-page result is flagged `truncated()`), `maxChars` caps the retained characters for any document. Every fault is classified into a result, never thrown: corrupt / malformed PDF → `FAILED` + `ExtractionError.Code.CORRUPT` (PDFBox's non-repairing `load` makes corruption observable), password-protected PDF → `FAILED` + `ExtractionError.Code.ENCRYPTED` (`isEncrypted()` plus an encryption-exception heuristic across PDFBox exception types), empty payload → `EMPTY` (not an error). The `ExtractionError.summary` / `shortMessage` are redacted one-liners (line breaks and NUL bytes stripped, length-capped) so no binary fragment, stack trace or secret reaches the persisted `error_summary`.
- [`apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/domain/ExtractionResult.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/domain/ExtractionResult.java) — the immutable, total result: `Status.EXTRACTED` / `EMPTY` / `FAILED`, page count, char count, text, a safe unmodifiable metadata subset (title / author / creation date), a `truncated` flag (page or character limit hit) and the classified `ExtractionError` (non-null only when `FAILED`).
- [`apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/domain/ExtractionError.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/domain/ExtractionError.java) — the classified failure: a stable machine-readable `Code` (`ENCRYPTED` / `CORRUPT`, persisted verbatim as the document `error_code`) and a redacted `summary` (`error_summary`).
- [`apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/application/config/DocumentExtractionConfiguration.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ingestion/application/config/DocumentExtractionConfiguration.java) — wires the `DocumentParser` bean from the `agentflow.ingestion.*` group; a misconfigured limit fails construction (via the `TikaDocumentParser` constructor) at context startup. It is homed in the `ingestion` module, not the global `config` package, so the module graph stays acyclic.
- [`apps/agentflow-app/src/main/java/com/example/agentflow/config/AgentFlowProperties.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/config/AgentFlowProperties.java) — the validated `agentflow.ingestion.*` group (`Ingestion.maxPages` default 100, `maxChars` default 500000, both `@Min(1)`, fail-fast) — the limits are configuration, not magic numbers.
- [`apps/agentflow-app/src/test/java/com/example/agentflow/ingestion/application/parser/TikaDocumentParserTest.java`](../../apps/agentflow-app/src/test/java/com/example/agentflow/ingestion/application/parser/TikaDocumentParserTest.java) — the DB-free unit suite (8 tests) generating its PDF fixtures on the fly with PDFBox (normal single-page, multi-page, password-protected) and a `%PDF` + garbage corrupt header.
- `apps/agentflow-app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.lockfile` — the `tika-core` and `tika-parser-pdf` dependencies (from the version catalog; PDFBox and their transitive tree are pulled in by Tika), nothing pinned inline (S01-06).

### Why

- **A port, not a concrete parser.** The worker must survive the extraction engine being swapped (Tika → PDFBox → …), so the dependency is the neutral `DocumentParser` port; the single PDFBox / Tika import stays confined to one implementation class behind it.
- **Bounded by design, not magic numbers.** A pathological or huge document must never exhaust a worker, so the page and character limits are enforced in the parser and exposed as `agentflow.ingestion.*` configuration (validated, fail-fast) rather than hard-coded.
- **Classify, do not throw.** The ingest side keeps processing a corrupt or encrypted document (at-least-once, never exactly-once — invariant 3); a failure is a *result* (`FAILED` + classified `Code`), not an interruption, and the persisted `error_code` lets a query distinguish corrupt from encrypted without a stack trace or the raw file (the S10-08 diagnostics intent carried into S10-07).
- **The error payload is safe.** `error_summary` and metadata are redacted and never carry the document body, binary bytes, a stack trace or a secret (invariant 8 / S10-08).

### Verification

The suite (8 tests, JUnit XML checked: root `tests="3"` + `Limits` `tests="2"` + `Failures` `tests="2"` + `Configuration` `tests="1"`, all `failures="0" errors="0"`) pins, DB-free (no `DataSource` / Kafka — the parser is pure), the four states the S10-07 acceptance calls for: a normal single-page PDF → `EXTRACTED` with the text + page count and no error; a blank payload → `EMPTY`; a corrupt PDF (`%PDF` + garbage, rejected deterministically by PDFBox's non-repairing `load`) → `FAILED` + `CORRUPT`; a password-protected PDF → `FAILED` + `ENCRYPTED`; plus the page limit (a 10-page PDF through a `maxPages=5` parser → `EXTRACTED`, `pageCount()=5`, `truncated()` true), the within-limits case (not truncated) and the constructor rejecting non-positive limits.

`./gradlew :apps:agentflow-app:check --rerun-tasks` green — unit + architecture (`ArchitectureBoundaryTest` — the `ingestion` boundary is `{ingestion, knowledgebase, messaging}`, `PackageDependencyGraphTest` — the same edge, `NoCyclicModuleDependenciesTest`) + no-cycles + `spotless` + OpenAPI gates; the Tika / PDFBox dependencies add no new module edge (only `TikaDocumentParser` imports the PDFBox / Tika APIs, so the boundary holds). `git diff --check` clean.

### Not changed

- The ingestion worker, chunking, embeddings, indexing and the `document.indexed` event — `S10-09` … `S10-13`.
- The `documents` parser-metadata columns (`page_count`, `error_code`, `error_summary`) — the `S10-05` migration already declares them, so this classified `error_code` / `error_summary` has a home to persist into.

---

## S10-06 — Commit the upload and enqueue ingestion through the transactional outbox (async, non-blocking).

### What changed

- [`apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentService.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentService.java) — the `S10-06` upload / ingestion-enqueue use case (`knowledgebase.application`). `#upload` is the single entry point that turns a `S10-03` `ValidatedUpload` (the accept/bound-checked payload) into a durable, queued document: it generates a server-issued `documentId` up front (stable across a retry) and its `object_key` (`DocumentObjectKey#of`); inserts the `documents` metadata row in status `UPLOADED` (the state `S10-03` returns) through `DocumentRepository`; and appends the `agentflow.document.ingestion-requested.v1` event to the transactional outbox through `OutboxService`. Both writes run inside one `@Transactional` (`REQUIRED`) method, so a document is never committed without its event and the event is never committed without durable state (invariant 3, `ADR-004`). The `tenantId` comes from the verified security context (invariant 1); the event carries only correlation metadata (opaque ids + `CorrelationHeaders`, never a secret / full prompt / full document); `correlationId = documentId`, `causationId = null` (the request event has no predecessor).
- [`apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentDto.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentDto.java) — the API view of the persisted document returned by `upload` (`documentId`, `knowledgeBaseId`, `status = UPLOADED`).
- [`apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/persistence/DocumentRepository.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/persistence/DocumentRepository.java) — the single write path for a `documents` metadata row. Backed by a plain `JdbcTemplate` (`ADR-009`), the row is written under the tenant passed in (the `tenant_id` column is the isolation key), and the bean is registered only with a `DataSource` (`@ConditionalOnBean(DataSource.class)`) — the application boots without a database (`S01-02`) and `upload` fails loudly with `503` when the repository is absent, exactly as `workflowexecution` does for its repositories (`@Autowired(required = false)` seam on the constructor parameter).
- [`apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/application/DocumentServiceTest.java`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/application/DocumentServiceTest.java) — the DB-free unit suite (5 tests) pinning the three `S10-06` facts that must hold before any storage backend or worker exists.
- `docs/adr/ADR-015-knowledgebase-outbox-edge.md` — the new `knowledgebase → messaging` edge: the upload enqueues `ingestion-requested` through the transactional-outbox port.
- `docs/architecture/module-map.md`, `apps/.../ArchitectureBoundaryTest.java`, `docs/adr/README.md` — edge 18 (`knowledgebase → messaging`) added to the diagram, the boundary ALLOW map and the ADR register.

### Why

- **Row + event in one transaction.** The two writes run inside one `@Transactional` method with the default `REQUIRED` propagation, so neither commits without the other (invariant 3, `ADR-004`). This is precisely what makes the upload <b>non-blocking on parsing/embedding</b>: the HTTP response returns `UPLOADED` as soon as the row + event are durably written, and the `QUEUED → PROCESSING → INDEXED` work is done later by the ingestion worker on the event. The parser (`S10-07`) and the embedding pipeline (`S10-11`) are <em>never</em> invoked here, directly or indirectly, so an upload never waits for them (the `S10-06` acceptance criterion).
- **The edge needed an ADR.** `knowledgebase` did not previously depend on `messaging`, so `ADR-015` records the trade-off: the upload enqueues `ingestion-requested` through the same transactional-outbox port `workflowexecution` uses (`OutboxService`), keyed `tenantId:documentId` (events §7), delivered at-least-once and idempotent by `tenant_id + sha256 + pipeline_version` at the consumer (`S10-13`).

### Verification

The suite (5 tests, JUnit XML checked: `tests="5" skipped="0" failures="0" errors="0"`) pins, DB-free (`DocumentRepository` / `OutboxService` mocked, no `DataSource`): the row is committed tenant-scoped under the exact `tenantId` in `UPLOADED` with the server-generated `object_key`; the `ingestion-requested` event is appended in the same call (`aggregateType = document`, keyed to the document, tenant-scoped, `correlationId = documentId`, `causationId = null`) and `topic = agentflow.document.ingestion-requested.v1`; the flow returns the async `UPLOADED` and never transitions to `QUEUED`; and the row insert + outbox append happen together (each exactly once) in one flow.

`./gradlew :apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + `spotless` + OpenAPI gates. The new `knowledgebase → messaging` edge does **not** create a module cycle: the document-upload wiring (`DocumentUploadConfiguration`, `S10-03`) lives in the `knowledgebase` module rather than the global `config` package, so `config` does not depend upward on `knowledgebase` — that edge, together with `knowledgebase → messaging` and the existing `messaging → config` (`AgentFlowProperties`) edge, would otherwise have formed `config → knowledgebase → messaging → config`.

### Not changed

- The upload controller / OpenAPI (`POST …/documents`, `202`) — a follow-up wires `DocumentService#upload` to HTTP.
- The ingestion worker (`S10-07` … `S10-13`), the `(tenant_id, id)` read, the object storage client (`S10-03`), the append-only audit event the upload writes (needs the `knowledgebase → audit` edge, an ADR) and the no-public-URL enforcement are the rest of the secure upload flow; they complete when their schema / persistence / wiring land (`S10-03` … `S10-08`).

---

## S10-05 — The knowledge base and document schema (migration + duplicate-upload policy).

### What changed

- `apps/agentflow-app/src/main/resources/migrations/V3_3_18__create_knowledge_bases_documents.sql` — the migration. It is step 19 of the ordered Flyway line (`V3_3_17` … `V3_3_18`), owner `knowledgebase`, and applies cleanly against PostgreSQL 18 (pgvector `pgvector/pgvector:0.8.6-pg18`) — the exact image Compose and Testcontainers use.
- `CHANGELOG.md` — an `Unreleased → Added` entry (S10-05) describing the schema and the duplicate-upload policy.

The `knowledgebase` module boundary, the OpenAPI contract and the Java code are unchanged: this is a schema + documentation change (no controller, no repository, no listener).

`knowledge_bases` (one tenant-scoped collection per `unique (tenant_id, name)`):

| column | type | constraint / default | meaning |
|---|---|---|---|
| `id` | `UUID` | PK | collection id (assigned by the domain factory) |
| `tenant_id` | `UUID` | FK `tenants.id`, `NOT NULL` | owning tenant (isolation by `(tenant_id, name)` + `(tenant_id, id)` read) |
| `name` | `TEXT` | `NOT NULL`, part of unique | fixed after creation — collection is immutable |
| `embedding_model` | `TEXT` | `NOT NULL` | pinned model; dimensionality is fixed by `S10-11` |
| `chunking_config` | `JSONB` | nullable | optional JSON chunker configuration |
| `status` | `TEXT` | `NOT NULL DEFAULT 'ACTIVE'` | application-managed collection status |
| `created_by` | `TEXT` | nullable | audit |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` | audit (immutable-row set — no `version`, no `updated_at`) |

`documents` (one row per uploaded object; created after `knowledge_bases` so the FK resolves):

| column | type | constraint / default | meaning |
|---|---|---|---|
| `id` | `UUID` | PK | document id (canonical id on `GET /api/v1/documents/{documentId}`) |
| `tenant_id` | `UUID` | FK `tenants.id`, `NOT NULL` | owning tenant |
| `knowledge_base_id` | `UUID` | FK `knowledge_bases.id`, `NOT NULL` | parent collection |
| `object_key` | `TEXT` | `NOT NULL` | server-generated, tenant-scoped object path (`documents/<tenantId>/<id>[.<ext>]`), generated once at upload, immutable |
| `filename` | `TEXT` | `NOT NULL` | safe, sanitized display name (never a path) |
| `mime_type` | `TEXT` | `NOT NULL` | canonical content type resolved by the validator |
| `size_bytes` | `BIGINT` | `NOT NULL` | payload length (the `S10-03` size bound is applied before commit) |
| `sha256` | `TEXT` | `NOT NULL` | payload SHA-256; content fingerprint for duplicate detection + ingestion idempotency |
| `version` | `INTEGER` | `DEFAULT 0` | optimistic-lock counter → `409` on conflict |
| `status` | `TEXT` | `DEFAULT 'UPLOADED'`, CHECK over 7 states | lifecycle (`document-lifecycle.md`) |
| `pipeline_version` | `TEXT` | `NOT NULL DEFAULT 'v1'` | chunking/embedding pipeline version; part of the idempotency key + reindex trigger (`S10-19`) |
| `page_count` | `INTEGER` | nullable | parser metadata (extractor output; NULL at `UPLOADED`) |
| `error_code` | `TEXT` | nullable | safe machine-readable failure token (`S10-08`) |
| `error_summary` | `TEXT` | nullable | short redacted failure summary (`S10-08`) |
| `retained_until` | `TIMESTAMPTZ` | nullable | retention deadline for object + chunks |
| `created_by` / `created_at` / `updated_at` | `TEXT` / `TIMESTAMPTZ` / `TIMESTAMPTZ` | `created_at` default `now()` | unified audit set on the mutable row |

Indexes: `(tenant_id, knowledge_base_id, status)` — a knowledge base's document list, tenant-scoped, status-filterable (plan §8.1); `(tenant_id, sha256)` — duplicate detection / idempotent lookup.

### Why

- **Retention + lifecycle as constraints, not post-filters.** The status `CHECK` pins the seven canonical states (`UPLOADED → QUEUED → PROCESSING → INDEXED`, `FAILED`, `DELETE_REQUESTED`, `DELETED`); a transition is a conditional update + optimistic `version` bump + append-only audit record in one transaction, never set directly from a controller/listener (invariant 4). `retained_until` carries the retention deadline at the row, so retention is enforced at query time, not after the fact.
- **Duplicate upload policy (determined here, implemented by `S10-19`).** A re-upload whose `(tenant_id, sha256)` matches an existing document is *content-identical*, so it is treated as a duplicate — the upload API returns the existing document and creates no new row. This keeps ingestion idempotent by content ("a repeated upload does not create duplicate logical chunks", `S10-13`) and never treats delivery as exactly-once (invariant 3). The `sha256` column is **indexed, not made unique**: a change in `pipeline_version` reindexes as a new document (`S10-19`), which a `unique (tenant_id, sha256)` would wrongly forbid.
- **`documents` is a mutable row, `knowledge_bases` is immutable.** `documents` gets an optimistic `version` counter + `updated_at` (the row changes status); `knowledge_bases` is fixed after creation, so it gets no counter — mirroring the run-state-machine separation in `S05-01`.

### Verification

All of it ran against a live PostgreSQL 18 container (`pgvector/pgvector:0.8.6-pg18`), the same image Compose and Testcontainers use. The migration line applied cleanly as step 19 (`./gradlew flywayMigrate`, now at `v3.3.18`).

- **Schema.** `information_schema` + `pg_constraint` confirm: FKs `documents.knowledge_base_id → knowledge_bases`, `documents.tenant_id → tenants`, `knowledge_bases.tenant_id → tenants`; PKs; the `unique (tenant_id, name)` on `knowledge_bases`; the `documents_status_check` over the seven states; and `NOT NULL` on every required column. `information_schema` confirms `object_key`, `status`, `version`, `pipeline_version`, `page_count`, `error_code`, `error_summary`, `retained_until` exist on `documents`.
- **Positive path.** A tenant → knowledge base → document chain inserts (`INSERT 0 1` ×3); the persisted document reads back `status = UPLOADED`, `page_count = 7`, the right `sha256`/`object_key`, tenant-scoped.
- **Negative paths.** A bogus `tenant_id` is rejected by the FK ("Key … is not present in table tenants"); `status = 'PROCESSED'` is rejected by the CHECK; a duplicate `(tenant_id, name)` is rejected by the unique constraint; a `tenant-b` lookup returns 0 rows for a `tenant-a` document (isolation); `EXPLAIN` shows the `(tenant_id, sha256)` index backing the duplicate-detection lookup ("Index Scan using documents_tenant_sha256_idx").

`git diff --check` clean. The `knowledgebase` module boundary and the OpenAPI contract are unchanged (no Java, no controller) — this is a schema + documentation change, so the Java `check` gate (unit + architecture + no-cycles + formatting + OpenAPI) does not need to re-validate anything here, though the broader `:apps:agentflow-app:check` run is green.

### Not changed

- The row binding of `documents` to the verified `TenantContext`, the `(tenant_id, id)` row lookup, the object storage client (`S10-03`), the append-only audit event the upload writes (needs the `knowledgebase → audit` edge, an ADR) and the no-public-URL enforcement are the rest of the secure upload flow; they complete when their schema / persistence / wiring land (`S10-03` … `S10-06`, `S10-08`).
- The ingestion worker, the pgvector/FTS chunk indexing and the live duplicate-detection enforcement are `S10-11` / `S10-13` / `S10-19`.

---
## S10-04 — Tenant-safe file upload (negative tests for path traversal and cross-tenant access).


### What changed
The part of `S10-04` that is verifiable before the `documents` schema (S10-05), the object storage
client and the `(tenant_id, id)` row lookup exist — the two invariants the future upload flow builds
on (invariant 1), captured as a DB-free negative suite in `knowledgebase.domain`, exactly as `S10-02`
gave a usable value type and `S10-03` gave the accept/bound layer before their real dependencies:
[`DocumentUploadTenantSafetyTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/DocumentUploadTenantSafetyTest.java).

- **Path traversal.** The object key is generated <b>server-side</b> from the server-issued UUIDs,
  never from the client `filename` — see `S10-02` [`DocumentObjectKey`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/DocumentObjectKey.java); two uploads of
  the same document with two different (even hostile) filenames map to the exact same key, whose shape
  is exactly `documents/<tenantId>/<documentId>[.<ext>]` (3 segments, no `..`, no extra segment), so a
  traversal or extra path a malicious filename would inject is impossible; a prompt-injected extension
  (`exe` / `jsp` / `php` / `..` / `a/b` / …) is rejected as a non-whitelisted content-type hint; and
  [`FileNameSanitizer`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/FileNameSanitizer.java)
  reduces any path/traversal to a bare base name (defense-in-depth: the display filename the row carries
  can never be a path).
- **Cross-tenant access.** An object key is <b>bound</b> to exactly the tenant that issued it (a key
  parsed from its string still resolves to that tenant, it cannot be re-issued under another); the same
  document maps to <b>distinct</b> keys across tenants (`of(TenantA, doc) != of(TenantB, doc)`); another
  tenant's key, read in tenant A's context, still resolves to tenant B, so a tenant-scoped lookup keyed
  by `(tenant_id, document_id)` treats it as a missing one (the S10-04 "indistinguishable from a missing
  one" policy); a tenant cannot authorize another tenant's key string, and a forged key for a different
  document under the same tenant is a distinct, non-authorizable key.

### Why
`S10-02` proved the key is generated server-side and tenant-scoped; `S10-03` proved the accept/bound
layer rejects a hostile filename and content type. `S10-04`'s acceptance — *path traversal and access to
another tenant's document covered by negative tests* — is the isolation guarantee those two layers make
usable: without it, a tenant could read another tenant's object, or a hostile filename could become an
object path. Those are exactly the negative scenarios `AGENTS.md` lists as mandatory, and the object-key
shape plus the filename sanitizer already encode them, so a DB-free suite pins them today. No behaviour,
interface or design choice is left open beyond the closed `knowledgebase` value types, so
`brainstorming` is skipped.

### Verification
- **Path traversal** — the key is generated from the ids only (never the filename) and a hostile
  extension is rejected: `DocumentUploadTenantSafetyTest#PathTraversal` (3 tests, JUnit XML
  `tests="3"` `failures="0"`).
- **Cross-tenant access** — the key is bound to its issuing tenant, the same document yields distinct
  keys across tenants, and another tenant's key resolves to that tenant (missing here):
  `DocumentUploadTenantSafetyTest#CrossTenantAccess` (5 tests, JUnit XML `tests="5"` `failures="0"`).
- `:apps:agentflow-app:check` green (unit + architecture + no-cycles + spotless + OpenAPI gates);
  `git diff --check` clean; the `knowledgebase` module boundary is unchanged — the suite compiles only
  against the closed `knowledgebase` value types plus `java.util.*` (no new edge, no ADR).

### Not changed
The row binding of `documents` to the verified `TenantContext`, the real `(tenant_id, id)` read that
enforces cross-tenant isolation, the object storage client (`S10-03`), the append-only **audit event**
the upload must write (needs the `knowledgebase → audit` edge, an ADR) and the no-public-URL enforcement
(the private-by-bucket policy from `S10-02`) are the rest of the `secure upload flow`; they complete when
their schema / persistence / wiring land (`S10-05` … `S10-06`) and are not on disk yet, so they are not
tested here. Related changelog entry: **Tenant-safe file upload (S10-04)** in [`CHANGELOG.md`](../../CHANGELOG.md) (Unreleased → Added).
## S10-01 — Define the document lifecycle (status state machine of `knowledgebase`).


### What changed
One architecture document, [`docs/architecture/document-lifecycle.md`](../architecture/document-lifecycle.md), is the single source of truth for `documents.status`: the set of states, which are terminal, the state diagram (Mermaid `stateDiagram-v2`), the per-transition event/command + guard table, and the wider-system interaction. It transcribes the already-fixed state set from the domain glossary (`docs/domain-glossary.md` §7 — `UPLOADED → QUEUED → PROCESSING → INDEXED`, with `FAILED`, `DELETE_REQUESTED`, `DELETED`) into the same state-machine form the run machine uses (`docs/architecture/run-state-machine.md`, `S05-01`), so the two documents read the same way. The design:

- **States.** `UPLOADED` (object stored + metadata row committed; the upload API returns `202`), `QUEUED` (the `ingestion-requested` event is enqueued via the outbox, the document awaits a worker), `PROCESSING` (an ingestion worker holds the lease and is extracting → chunking → embedding → indexing), `INDEXED` (all chunks indexed — pgvector + FTS), `FAILED` (permanent failure or retry budget exhausted), `DELETE_REQUESTED` (deletion requested, the lease is drained), `DELETED` (object + chunks removed). **Terminal:** `INDEXED`, `FAILED`, `DELETED` — all absorbing.
- **Transitions (7).** (1) `UPLOADED → QUEUED` on the enqueued `ingestion-requested`; (2) `QUEUED → PROCESSING` on `worker.claim` (a lease, tenant re-checked against PostgreSQL); (3) `PROCESSING → INDEXED` on the `document.indexed` event; (4) `PROCESSING → QUEUED` — the only **retry** edge, a bounded transient re-queue; (5) `PROCESSING → FAILED` — permanent failure or an exhausted retry budget; (6) any non-terminal / `FAILED` state → `DELETE_REQUESTED` on the `DELETE /api/v1/documents/{documentId}` command; (7) `DELETE_REQUESTED → DELETED` once the object and chunks are gone.
- **Design points.** Deletion is two-phase and one-way (the run's `CANCEL_REQUESTED → CANCELLED` mirror); a `PROCESSING` delete drains cooperatively and never overwrites a completed result (optimistic `version` + `lease_epoch` fencing); retry is bounded (the only backward edge) and redelivery is idempotent (at-least-once, keyed by `tenant_id + sha256 + pipeline_version`); every transition goes through the single `knowledgebase` transition service (conditional update + optimistic lock + append-only audit record in one transaction), never set directly from a controller or listener; the `ingestion` worker leases + indexes + publishes `document.indexed`, but `knowledgebase` owns and applies the status.

### Why
The `S10-01` acceptance: every transition is explicitly described and the retry/terminal states are defined. The knowledge base (`knowledgebase`) and the ingestion pipeline (`ingestion`) are being built across `S10-03` … `S10-13`, but a document's lifecycle had no diagram or transition table yet — only the seven state names in the glossary. The upload enqueue, the ingestion worker, the delete command and the indexed event already exist as events (`docs/architecture/events/README.md`), so this is a documentation task that fixes the state machine before the code that implements it, exactly as `S05-01` fixed the run machine before `S05-10`. No behaviour, interface or design choice is left open beyond the standard state-machine shape the plan and glossary already dictate, so `brainstorming` is skipped.

### Verification
- **Link integrity.** Every in-document link resolves to a document that exists and is on-topic: `run-state-machine.md`, `step-state-machine.md`, `events/README.md`, `durable-execution.md`, `error-taxonomy.md`, `domain-glossary.md`, `module-map.md` — all confirmed present at the referenced path; the downstream `S10-03` … `S10-13` are referenced as planned code, not linked (a not-yet-existing file is never linked, per `docs/AGENTS.md`).
- **In sync with the source of truth.** The seven states and the `UPLOADED → QUEUED → PROCESSING → INDEXED` spine match the glossary §7 verbatim; the `document.indexed` payload carrying `status = INDEXED` (`docs/architecture/events/README.md`) matches transition 3; the `tenantId:documentId` partition key and the two document topics match `events/README.md` / `kafka-topics.md`; the one-transition-service / absorbing-terminal / two-phase-cancellation rules match `run-state-machine.md` and the root invariants.
- **Well-formed.** `git diff --check` clean on all touched files; the two index tables (`docs/README.md` tree, `docs/architecture/README.md` documents table) and the glossary table row were diff-reviewed to confirm the leading pipe count is unchanged (a context-aware patch had doubled a leading `|` and was corrected before finishing).
- **Not a build.** This is a documentation-only change (`S10-01`), so there is no `./gradlew check` gate to point to (the state machine enum, the transition service and the upload/delete API do not exist yet — they are `S10-03` … `S10-13`). No build or test was run; the verification is link integrity + `git diff --check` + the glossary/events cross-check above, per `docs/AGENTS.md` ("for a documentation-only change there is often no CI gate yet — verify by resolving every in-document link … `git diff --check`, and a diff review").

### Not changed
The code, the `documents` schema (S10-05), the upload/delete API, the ingestion worker and the events are untouched — this is documentation only; the enum + transition service they will implement are not on disk yet.

## S10-02 — Storage config and object-key convention.


### What changed
Two additions in the `knowledgebase` module (object storage is a `knowledgebase` concern per `docs/architecture/module-map.md`), both DB-free, both part of the S10-02 deliverable: a typed, validated `agentflow.storage.*` configuration group, [`AgentFlowProperties.Storage`](../../apps/agentflow-app/src/main/java/com/example/agentflow/AgentFlowProperties.java), and the server-generated, tenant-scoped object-key convention [`DocumentObjectKey`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/DocumentObjectKey.java) in `knowledgebase.domain`.

- **The config group** describes the S3-compatible bucket the objects live in — which bucket (`bucket`, default `agentflow`, matching the Garage bucket in `infra/compose` + `ADR-013`), where (`region`, default `garage`), the policy (`public-access`, `disabled` by default — the bucket is private, an object is never addressable through a public or anonymous URL) and the endpoint (`endpoint`, environment-specific, supplied per profile by the S3 client built in `S10-03`; a `null` endpoint fails clearly when that client is built, never silently). It binds the whole `agentflow.storage.*` tree, mirrors `AgentFlowProperties` with the same `@Validated` + `@ConfigurationProperties` binding, `@Valid` on the nested group and `@Autowired(required = false)`-style defensiveness. The S3 credentials (`AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY` / `AGENTFLOW_OBJECT_STORAGE_SECRET_KEY`) come from the environment only, never from configuration or source (invariant 8).
- **The object-key convention** is `documents/<tenantId>/<documentId>[.<ext>]` — generated **server-side** from the server-issued identifiers, never from the client `filename` and never a path the filename controls (invariant 1 / the S10-04 key-generation acceptance). The only separators are the fixed `documents/` prefix plus the two server UUIDs, and an extension (when present) is a content-type hint only, validated against a closed whitelist and never a path segment — so a hostile or prompt-injected `filename` can never become an object path or leak across tenants.


### Why
`S10-02` provides the storage configuration and the object-key convention the future ingestion / upload path (`S10-03` … `S10-13`) builds on: without a typed, validated group the endpoint / bucket / region would be magic numbers, and without a server-generated key there is no guarantee that an object's path is tenant-scoped or that a hostile `filename` cannot become a path. The private-by-bucket policy, the env-only credentials, and the server-generated key are the S10-02 acceptance — the parts that must be right before any payload is written. They are captured now as a DB-free value type and a validated config group (no `DataSource` / S3 dependency), so no Testcontainers integration test is needed at this stage; the real S3 client and ingestion round-trip are `S10-03`.


### Verification
- **The key is generated server-side from the ids, never from the filename** — [`DocumentObjectKeyTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/DocumentObjectKeyTest.java) (9 tests, JUnit XML checked: `tests="4"` + `tests="2"` + `tests="3"`): server-side generation, tenant-scoping, the exact canonical shape (3 segments, no `..`, exact UUIDs) and rejection of a hostile `filename`;
- **The config binds the safe defaults** — [`StoragePropertiesTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/StoragePropertiesTest.java) (5 tests): private-by-default, no public URL, custom `endpoint` / `bucket` / `region` bind and an invalid value fails startup through the validated binding;
- `:apps:agentflow-app:check` green (unit + architecture + no-cycles + formatting + OpenAPI gates); `git diff --check` clean; the `knowledgebase` module boundary is unchanged (it still compiles only against `ai` / `workflowexecution`).


### Not changed
The S3 client, the credentials binding and the ingestion / upload wiring are `S10-03`; they are not on disk yet. Related changelog entry: **The storage config and object-key convention (S10-02)** in [`CHANGELOG.md`](../../CHANGELOG.md) (Unreleased → Added).

## S10-03 — Document upload validation (accept/bound layer).


### What changed
The accept/bound checks the `POST /v1/documents` upload API runs before a single byte is written to
object storage and before a single `documents` metadata row is committed. This is the whole of `S10-03`
that is fully verifiable today: it needs no storage backend, no `documents` schema (S10-05) and no
ingestion worker (S10-03 … S10-13) — only the configured limits — so it is the safe core the upload API
can drive from an early API and unit-test in isolation, exactly as `S10-02` gave a usable value type and
a validated config group before the real S3 client. The pieces:

- **Config** — `agentflow.upload.*` on [`AgentFlowProperties.Upload`](../../apps/agentflow-app/src/main/java/com/example/agentflow/AgentFlowProperties.java): `max-bytes` (default `10485760`, 10 MiB), `max-filename-length` (default `255`), `max-media-duration-seconds` (default `300`, 5 minutes; media types only). Bound with the same `@Validated` + `@ConfigurationProperties` path as the other `agentflow.*` groups; a value below the minimum fails boot. Documented in [`application.yml`](../../apps/agentflow-app/src/main/resources/application.yml) and bound by [`DocumentUploadConfiguration`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/config/DocumentUploadConfiguration.java), following the `ToolExecutionConfiguration` precedent (the validator is a value-bound bean built from the config group, never a `@Component`).
- **Domain** — [`DocumentContentType`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/DocumentContentType.java): the closed allowlist (application/pdf, image/png, image/jpeg, image/gif, image/bmp, audio/wav, text/plain, text/markdown, text/csv, text/richtext, application/json, application/xml, text/xml); `from(String)` rejects anything not on it (an untrusted header cannot expand the surface), `matches(byte[])` verifies the real leading bytes — magic bytes, RIFF/WAVE or a light text check — so a payload that misrepresents itself is rejected, `isMedia()` flags the duration-bounded types, `isPermitted(String)` is the non-throwing allowlist probe; [`FileNameSanitizer`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/FileNameSanitizer.java): strips any path/traversal/control character to a bare base name, rejecting a blank, `.`-only or `..`-only input so the filename can never become an object path (invariant 1 / `S10-04`); [`MediaDuration`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/MediaDuration.java): decodes the WAV duration from the header (RIFF/WAVE + fmt/data) so it can be bounded; [`DocumentUploadLimits`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/DocumentUploadLimits.java): immutable domain view of the three limits, constructor validation; [`DocumentUploadStatus`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/DocumentUploadStatus.java): the upload lifecycle state (UPLOADED → QUEUED → PROCESSING → INDEXED, FAILED, DELETE_REQUESTED, DELETED).
- **Application** — [`DocumentUploadValidator`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentUploadValidator.java): runs the checks in reject-before-persist order — filename → content type → size → magic bytes → media duration → SHA-256 checksum — and every rejection is one [`DocumentUploadValidationException`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentUploadValidationException.java) (the HTTP layer maps it to a `400`, `common.ErrorClass`); the payload bytes are never written by this call. [`ValidatedUpload`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/ValidatedUpload.java): the safe filename, canonical type, size, checksum and (for media) duration the row carries.


### Why
`S10-02` gave the storage configuration and the object-key convention the upload path builds on; `S10-03`
gives the upload API the accept/bound layer it needs before it ever reaches storage. The limits are the
first line of defense against oversized / hostile uploads and a prompt-injected content type, and they are
the S10-03 acceptance: an oversized or unsupported upload is rejected without leaving a dangling metadata
row or object. They are captured as pure, DB-free value types and a validated config group (no `DataSource`
/ S3 dependency), so no Testcontainers integration test is needed at this stage — the real S3 client, the
`documents` schema and the ingestion worker are `S10-03` … `S10-13`. No behaviour, interface or design
choice is left open beyond the closed allowlist / three limits / reject-before-persist order the plan and
invariants already dictate, so `brainstorming` is skipped.


### Verification
- **The closed allowlist and magic-byte verification** — [`DocumentContentTypeTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/DocumentContentTypeTest.java): `from` resolves a declared type, strips parameters and rejects anything not on the allowlist; `matches` verifies the real leading bytes for a PDF, PNG and WAV (a payload that misrepresents itself is rejected), an empty payload never matches, a JSON payload needs a structural character; `isMedia()` / `isPermitted()`.
- **The filename can never carry a path or traversal** — [`FileNameSanitizerTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/FileNameSanitizerTest.java): a plain name is returned, a path is reduced to its trailing segment, a blank/null / null-byte / control-char / `..`-only input is rejected, and `../secret` / `../../etc/passwd` are sanitized to their safe base rather than trusted.
- **The WAV duration decode** — [`MediaDurationTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/MediaDurationTest.java): duration equals `data / byte_rate`, and a non-WAV payload returns empty.
- **The limits are a validated value** — [`DocumentUploadLimitsTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/DocumentUploadLimitsTest.java): value equality by the three fields, a non-positive limit is rejected.
- **The reject-before-persist contract** — [`DocumentUploadValidatorTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/application/DocumentUploadValidatorTest.java): the accept case (safe filename, canonical type, size, real SHA-256 checksum, media duration) and every reject case — oversized, unsupported content type, tampered magic bytes, over-long filename, traversal name, over-long media, undecodable media, null payload — each throwing `DocumentUploadValidationException`.
- **The config binds the safe defaults** — [`UploadPropertiesTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/UploadPropertiesTest.java): the `agentflow.upload.*` group binds the defaults, custom values override them and a negative value fails startup through the validated binding.
- `:apps:agentflow-app:check` green (unit + architecture + no-cycles + formatting + OpenAPI gates); `git diff --check` clean; the `knowledgebase` module boundary is unchanged — the accept/bound layer compiles only against the closed `knowledgebase` value types.


### Not changed
The S3 client, the credentials binding, the `documents` schema (S10-05), the ingestion worker and the upload/delete API are `S10-03` … `S10-13`; they are not on disk yet. The upload validation layer is a `knowledgebase` concern; it writes nothing and owns no row, so it changes no existing module. Related changelog entry: **The document upload validation layer (S10-03)** in [`CHANGELOG.md`](../../CHANGELOG.md) (Unreleased → Added).
