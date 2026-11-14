# Changelog


# Sprint 10 — Document ingestion, pgvector, hybrid retrieval and citations


## S10-17 — Grounded citations from retrieved evidence (P2).

### What changed
|- The `knowledgebase` layer builds one {@link CitationDto} per *real* retrieved chunk through the pure static `knowledgebase.application.CitationBuilder.cite(SearchResult)` — no Spring, no database, no I/O, the `knowledgebase` counterpart of `RankFusion`. It maps each retrieved `ChunkDto` to one `CitationDto`, in the retrieved order the retrieval halves already ranked, and **never** emits a citation for a chunk the caller cannot see: an empty `SearchResult` yields an empty list, and every citation's `chunkId()` resolves to the same tenant- and collection-scoped chunk the retrieval returned (invariant 1 — the two halves' predicates run inside the SQL, so a citation cannot point at another tenant's document).
|- `CitationDto` (S10-17, `knowledgebase.application`): a value object — `documentId`, `chunkId`, `documentName` (parent document display name), `pageFrom`, `pageTo`, `score`, `snippet`. Its canonical constructor rejects null ids / name / snippet and any score that is negative or not finite. `snippet` is untrusted document text (invariant 7 — prompt-injection surface; fed to the model only as `USER` content, never logged, invariant 8); `documentName` is the trusted parent-document display name.
|- The retrieval entry point `RetrievalService#citations(tenantId, knowledgeBaseId, SearchRequest)` wires the two together over the hybrid half (`hybridSearch`): it builds a citation per fused chunk, and for an empty retrieval returns an empty list (reporting `InsufficientEvidence` for that empty list is S10-18's no-evidence policy, not this layer); it throws `IllegalArgumentException` on a null argument and `IllegalStateException` when no database is configured (S01-02).
|- The change threads the parent document's display name through the retrieval path: `ChunkDto` gains `documentName`, and `DocumentChunkRow` / `DocumentChunkRepository` resolve it from a single `documents` join on both the vector (S10-14) and lexical (S10-15) reads, so a citation is built from each row without a second lookup.

### Why
The glossary &sect;8 "Citation" response field set names `documentId`, `chunkId`, page/section and score; the model is an untrusted planner (invariant 7) so the backend builds the citation itself — never lets it invent a `documentId` / `chunkId` — and grounds each claim in a chunk the caller can actually see. One builder, pure (no Spring / DB / I/O), keeps the citation construction testable in isolation and outside the retrieval transaction; it returns the empty `SearchResult` unchanged so S10-18 stays free to decide what to *report* for it. `documentName` is trusted metadata (the `filename`), `snippet` is untrusted content: the split is what lets the caller feed the snippet to the model as `USER` content while keeping the citation's grounding resolvable and tenant-safe.

### Verification
Two new DB-free suites, all JUnit-XML-verified (`failures="0"` `errors="0"` `skipped="0"`): `CitationDtoTest` `tests="4"` (the canonical-constructor contract: null ids / name / snippet rejected, negative / NaN / non-finite-checked score) and `CitationBuilderTest` `tests="4"` (one citation per chunk in retrieved order; an empty result yields no citation, never an invented one; the citation's `chunkId()` resolves to the same chunk it cites; a null argument is a contract `IllegalArgumentException`). The updated `ChunkDtoTest` `tests="4"` / `SearchResultTest` `tests="4"` / `RetrievalServiceTest` `tests="22"` now assert `documentName` propagates through `search` / `hybridSearch` (`failures="0"` `errors="0"` `skipped="0"`); all 38 knowledgebase unit tests, fresh `--rerun-tasks`. The acceptance — **each citation resolves to the calling tenant's own source fragment** — is pinned by one new probe in `RetrievalServiceIT` (`citations_resolve_to_the_calling_tenants_own_fragment`, 1 test: the built citation carries the parent `filename`, resolves to the calling tenant's own chunk, and a cross-tenant retrieval yields an empty list). It compiles and is ready to run but needs PostgreSQL/Testcontainers, which are not available in this environment. `:apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest` — the `knowledgebase` boundary is `{knowledgebase, ai, workflowexecution, messaging}`, unchanged; `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + formatting (`spotless`) + OpenAPI gates; `git diff --check` clean.

### Not changed
|- No migration, no REST endpoint, no new module edge: `CitationBuilder` and `CitationDto` live in `knowledgebase.application` (new public types of the existing module, exactly like `RankFusion` / `ChunkDto`); `RetrievalService` reads only its own `knowledgebase` repositories and the `ai` `EmbeddingPort` (already allowed). No ADR — `S10-17` does not change module ownership; the no-evidence policy (`S10-18`) remains planned.

## S10-18 — Grounded answers; no-evidence policy (P1).

### What changed
|- The `knowledgebase` layer now decides what to *report* for an empty (or below-bar) retrieval. The sealed {@link GroundedAnswer} `GroundedAnswer` is the no-evidence decision — either one {@link GroundedAnswer.Grounded Grounded} to the real retrieved citations or one {@link GroundedAnswer.InsufficientEvidence InsufficientEvidence} — a `sealed` hierarchy on purpose so a consumer is exhaustive without a `default` branch and cannot invent a third outcome (e.g. a fabricated citation for an empty result). {@link GroundedAnswer.Grounded Grounded(List<CitationDto>)} carries the real citations (a defensive `List.copyOf`; a null argument is rejected); {@link GroundedAnswer.InsufficientEvidence InsufficientEvidence(UUID searchId)} carries only the RAG call's `searchId` (correlation only, never the query text) so an operator can tell which query the "no evidence" answer belonged to.
|- `knowledgebase.application.GroundingPolicy` is the no-evidence policy owner (S10-18): pure (`no Spring, no database, no I/O` — the `knowledgebase` counterpart of {@link CitationBuilder} / {@link RankFusion}), unit-testable in isolation, never part of the retrieval transaction. It owns only the *reporting* decision from the citations the caller built: {@link GroundingPolicy#decide(List, UUID)} returns {@code Grounded(citations)} when the retrieval meets the bar, otherwise {@code InsufficientEvidence(searchId)}, never a fabricated citation for an empty result. The bar is `Math.max(1, minimumCitations)`: `0` means <em>any non-empty retrieval grounds</em> (an empty retrieval is an evidence gap), a value `>= 1` means <em>the retrieval must return at least that many chunks</em>; {@code GroundingPolicy.DEFAULT = new GroundingPolicy(0)} is the glossary §8 fix, and a negative `minimumCitations` is a contract failure.
|- `RetrievalService` owns the policy seam: a new {@code @Autowired(required = false)}-style constructor parameter {@code GroundingPolicy} (the S01-02 / `RunStateMachineService` idiom — `null` falls back to {@link GroundingPolicy#DEFAULT}, so a default boot boots and the policy stays `Grounded` for any non-empty retrieval) plus {@code GroundedAnswer groundedAnswer(tenantId, knowledgeBaseId, SearchRequest, searchId)} — the reporting half of the RAG loop. It runs {@code policy.decide(citations(tenantId, knowledgeBaseId, request), searchId)} over the hybrid half (`citations` ⇒ {@link CitationBuilder.cite}, one citation per real fused chunk, never a citation for a chunk the caller cannot see — invariant 1); it rejects a null argument with {@link IllegalArgumentException} and fails loudly without a database with {@link IllegalStateException} (S01-02). The {@code search} / {@code hybridSearch} / {@code #citations} entry points stay unchanged — only the new outcome entry point applies the policy.
|- `RetrievalConfiguration` wires the seam: the policy bean is injected through an {@code ObjectProvider<GroundingPolicy>} (the `EmbeddingPort` / repository `ObjectProvider` precedent) and passed through when present, so a missing policy bean falls back to {@link GroundingPolicy#DEFAULT}. The `minimum-citations` bar is a {@code GroundingPolicy} constructor argument (not an `agentflow.retrieval.*` key), keeping the no-evidence bar a small, pure policy knob rather than a wider config group.

### Why
The glossary &sect;8 "Retrieval" result names the `answer` response field, and the model is an untrusted planner (invariant 7) so the backend decides whether the answer is grounded — it must not surface a confident-but-baseless answer when it found nothing relevant, and it must not invent a `documentId` / `chunkId`. The no-evidence policy is a *separation of concerns*: {@link CitationBuilder.cite} builds one citation per real chunk (S10-17) — an empty retrieval yields an empty list — and {@link GroundingPolicy#decide} decides only whether to report that list as {@code Grounded} or an {@code InsufficientEvidence} evidence gap; an empty retrieval is reported as `INSUFFICIENT_EVIDENCE` (the glossary "Citation" field set is empty, never fabricated), never a fabricated {@code Grounded}. Keeping {@link GroundingPolicy} pure keeps it testable in isolation and outside the retrieval transaction; the S01-02 `@Autowired(required = false)` policy seam keeps a database-less boot working exactly as before (a missing policy bean ⇒ {@link GroundingPolicy#DEFAULT}, so {@code groundedAnswer} still constructs and a non-empty retrieval still grounds).

### Verification
Three new DB-free suites, all JUnit-XML-verified (`failures="0"` `errors="0"` `skipped="0"`): {@link GroundedAnswerTest} `tests="7"` — `a_grounding_carrying_real_citations_is_a_Grounded` (a grounding carries the real citations), `a_Grounded_takes_a_defensive_copy_of_the_citations`, `a_Grounded_with_an_empty_list_is_a_grounding_in_nothing` (an empty list is still a {@code Grounded}, never an {@code InsufficientEvidence} — the policy decides that), `the_grounding_constructor_rejects_null_citations`, `an_evidence_gap_reports_the_search_id_only` (the gap carries the `searchId`, nothing else), `the_evidence_gap_constructor_rejects_null_search_id`, and `the_two_variants_are_distinct_and_both_are_GroundedAnswer` (the sealed hierarchy). {@link GroundingPolicyTest} `tests="7"` — `the_default_policy_grounds_any_non_empty_retrieval`, `the_default_policy_reports_insufficient_evidence_for_an_empty_retrieval` (`INSUFFICIENT_EVIDENCE`), `a_non_minimum_bar_requires_that_many_citations`, `the_policy_barely_passes_at_the_bar_and_fails_below_it` (`max(1, minimumCitations)`), `the_policy_rejects_a_negative_minimum`, `decide_rejects_null_arguments`, and `decide_reports_the_search_id_of_the_call_that_found_nothing`. The updated {@link RetrievalServiceTest} `tests="30"` now adds 7 {@code groundedAnswer} probes over the deterministic corpus: `groundedAnswer_grounds_when_the_hybrid_returns_evidence` (a `+x` query returns {@code Grounded} with one citation resolving to the matching chunk), `groundedAnswer_reports_insufficient_evidence_for_an_empty_retrieval`, `groundedAnswer_reports_insufficient_evidence_for_a_cross_tenant_or_missing_collection` (an empty result, never an invented citation), `groundedAnswer_applies_the_minimum_citations_bar`, `groundedAnswer_grounds_when_the_bar_is_met`, `groundedAnswer_fails_loudly_without_a_database_connection` (`IllegalStateException` — S01-02), and `groundedAnswer_rejects_null_arguments`. The acceptance — **a grounded retrieval is answered with a citation per real chunk; an empty retrieval reports `INSUFFICIENT_EVIDENCE`, never fabricated citations** — is pinned by one new probe in {@link RetrievalServiceIT} (`groundedAnswer_grounds_in_the_calling_tenants_own_evidence_or_reports_a_gap`, 1 test: a matching query returns {@code Grounded} with a citation to the calling tenant's own chunk; a cross-tenant query returns {@code InsufficientEvidence}). It compiles and is ready to run but needs PostgreSQL/Testcontainers, which are not available in this environment. `:apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest` — the `knowledgebase` boundary is `{knowledgebase, ai, workflowexecution, messaging}`, unchanged; `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + formatting (`spotless`) + OpenAPI gates; `git diff --check` clean.

### Not changed
No migration, no REST endpoint, no new module edge: {@link GroundedAnswer}, {@link GroundingPolicy} and the {@code RetrievalService#groundedAnswer} entry point all live in `knowledgebase.application` (public types of the existing module, exactly like {@link SearchResult}); `RetrievalService` reads only its own `knowledgebase` repositories and the `ai` `EmbeddingPort` (already allowed). No ADR — `S10-18` does not change module ownership; it only decides what to report for an empty result that {@link CitationBuilder} (S10-17) already built citations from.

## S10-16 — Hybrid fusion of the two retrieval halves (P1).

### What changed
- `RetrievalService.hybridSearch(tenantId, knowledgeBaseId, SearchRequest)` runs the two retrieval
  halves **independently** and fuses them. The vector half runs `DocumentChunkRepository.findNearest`
  (`S10-14`) — but only when an embedding provider is configured (`ai.application.EmbeddingPort`, the
  `ADR-014` opt-in seam); the lexical half runs `RetrievalService.lexicalSearch` (`S10-15`) over the
  `english` FTS index **whenever one is present**. The two `SearchResult`s are combined by
  `RankFusion.fuse` — a *pure* utility (`no Spring, no database, no I/O`): it deduplicates by chunk
  id and ranks by reciprocal-rank fusion, score = `1 / (K + rank)` summed over the halves each chunk
  appears in (K=1), then the service caps the fused list at `maxTopK`. The result is deterministic:
  a fixed query set always yields the same ordered, deduplicated, capped `SearchResult`.
- The vector half degrades to nothing when there is no provider, so a tenant without a model is
  served by the lexical half alone — neither half is a single point of failure. The fusion operates
  on **chunk ids only**; it never touches `ChunkDto.content` (invariants 7/8).
- Three fusion meters, registered on a `MeterRegistry` the constructor accepts (opt-in — `null`
  means "no shared registry", so the meters are registered on a throwaway `SimpleMeterRegistry`):
  `agentflow.knowledgebase.retrieval.hybrid` (one increment per hybrid call), `agentflow.knowledgebase.retrieval.half-results`
  (one increment per raw result both halves produce), and `agentflow.knowledgebase.retrieval.duration`
  (a `Timer`, in seconds). None carries a `tenantId`, document id, or chunk-text label (invariant 9);
  they follow the `WorkflowRecoveryScheduler` `MeterRegistry`/`Counter`/`Timer` convention.

### Why
The two halves return two *incompatible* relevance scores (cosine distance vs `ts_rank`); the fusion
is score-based over **ranks** only, so the cosine and `ts_rank` numbers are never compared or
averaged. Each chunk gets one `1/(K+rank)` contribution per half it appears in, so a chunk in both
half-rankings outranks one in a single half, and within a half an earlier position scores higher.
`K=1` (not the web-search `K=60`) keeps the small fused set's rank ordering meaningful — at `60` the
within-half rank differences collapse to noise and only presence survives. `max-top-k` bounds each
half's candidate list, so the fused set is at most `2 · maxTop-k` before dedup, and the cap then
returns the top `maxTop-k` fused. Fusion metrics give an operator a handle on hybrid recall and
latency without leaking high-cardinality identifiers (invariant 9) or untrusted document text
(invariants 7/8).

### Verification
`RetrievalServiceTest` now `tests="22"` (the 16 from `S10-15`, plus 6 fusion probes: fuse + dedup +
cap to `maxTopK`; the empty cross-tenant/missing collection; the lexical-only degradation without an
embedding provider; the fail-loud shape with no database; and a meter assertion that the fusion
`Counter`s/`Timer` exist with **zero** tenant/document labels), over the deterministic in-memory
corpus. [`RetrievalServiceIT`](apps/agentflow-app/src/integrationTest/java/com/example/agentflow/knowledgebase/application/RetrievalServiceIT.java) (9 tests now)
adds the hybrid acceptance on the real `pgvector/pgvector:0.8.6-pg18` image, driving the real service
and the Flyway-migrated schema: `hybrid_search_fuses_the_halves_without_duplicate_chunks` — a query
matching in both halves returns a deduplicated, capped set ordered by fused rank — and
`hybrid_search_never_returns_another_tenants_chunks` — tenant isolation holds through the fusion, and
the hybrid meters record with no tenant / document tag. `:apps:agentflow-app:check` green — unit
+ architecture (`ArchitectureBoundaryTest`, `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`)
+ no-cycles + formatting (`spotless`) + OpenAPI gates; `:apps:agentflow-app:integrationTest --tests 'com.example.agentflow.knowledgebase.*'`
green.

### Not changed
- No new migration, no REST endpoint (the fused search is a follow-up controller), no contract files.
- No new module edge: `RetrievalService` only reads its own `knowledgebase` repositories and the
  `ai` `EmbeddingPort` (already allowed). The fusion lives in `knowledgebase.application`, not a new
  package, so there is no architecture-ADR — `S10-16` does not change module ownership; citations
  (`S10-17`) and the no-evidence policy (`S10-18`) remain planned.

## S10-15 — Lexical (full-text) search — the lexical half of hybrid retrieval (P1).

### What changed
- Migration `V3_3_21` (owner `knowledgebase`, forward-only, `ADR-008` / `IF NOT EXISTS`) turns the `document_chunks.content` into the lexical half of the hybrid retrieval the glossary "Retrieval" names. It adds a generated `tsvector` column `search_vector` (`GENERATED ALWAYS AS (to_tsvector('english', content)) STORED`) and a `GIN` index `document_chunks_search_idx` on it. The column is **generated**, so the index recomputes whenever `content` changes — there is never a drift between the stored text and the vector — and the `english` text-search configuration is pinned (the corpus is English notation, so the language is a fixed decision, not a runtime switch; the `english` config lexemises the identifiers the tests look for, e.g. `retention` → `retenti`, and `S10-14` itself). Both the column and the index carry a `COMMENT` attributing them to `S10-15` / `knowledgebase`; the extension stays enabled from the S10-11 `V3_3_20`.
- `DocumentChunkRow` gains a `rank` component: the two retrieval halves share the record (the nearest-neighbour half fills `distance`, the lexical half fills `rank`; a row from one half never mixes the two scores). `ChunkRowMapper` (vector) leaves `rank` at `0`; the new `KeywordRowMapper` maps the `ts_rank` relevance the lexical query computes.
- `DocumentChunkRepository.findByKeyword(tenantId, knowledgeBaseId, keyword, limit)` is the lexical read the service drives. The query is the mirror of the S10-14 nearest-neighbour query: `c.tenant_id = ?` (the tenant, invariant 1) and a `documents` join adding `d.knowledge_base_id = ?` (the collection scope) **before** the `@@` match and the `LIMIT` — a cross-tenant or cross-collection keyword query returns an empty list, never a post-filter. The keyword is tokenised with `plainto_tsquery('english', ?)` (not `to_tsquery`), so an untrusted search string is read as natural language and can never throw on a malformed operator — an odd or hostile keyword is a no-match, not an error (invariant 7).
- `knowledgebase.application.RetrievalService.lexicalSearch(tenantId, knowledgeBaseId, keyword, topK)` is the lexical half of the hybrid retrieval — the complement of `search`. It needs **no** embedding provider (it reads the tenant-scoped lexical index directly), so it also works with no provider configured (`ADR-014` opt-in shape). The candidates carry the FTS relevance as their `ChunkDto#score()` (`rank`, mapped from the repository row); the same `default-top-k` / `max-top-k` bounds apply as `search`, and the lexical half never applies the cosine threshold (that is the vector half's cut). With no database it fails loudly at call time (`IllegalStateException`), exactly like `search`.

### Why
The S10-14 vector half matches by embedding similarity; it is robust to paraphrase but weak on exact tokens, identifiers and rare proper nouns (an embedding of `retention` and an embedding of `xyzzy-4711` say little about their textual difference). The FTS half matches by exact text, so a keyword query for a term or an ID finds it regardless of embedding geometry — the two halves are genuinely complementary, and the plan's "hybrid search" (`S10-16`) is the fusion of these two independent results. The access predicate lives **inside** the lexical query for the same reason it lives inside the vector query: the search API is per collection, and another tenant's or another collection's chunk must never surface even when its text is the exact keyword — the `(tenant_id, ...)` and `(knowledge_base_id, ...)` predicates before the `@@` and the limit guarantee it (invariant 1, single 403/404 policy applied as an empty result). `english` is pinned because the corpus the local documents and the tests use is English notation, and `to_tsvector` / `plainto_tsquery` must agree on the configuration. `plainto_tsquery` (rather than a caller-supplied `tsquery`) is the safety choice: the caller is an untrusted source for the query, and `plainto_tsquery` can never turn a hostile keyword into an operator-fragile query.

### Verification
4 DB-free unit tests (`RetrievalServiceTest`, `tests="16"` now), all JUnit-XML-verified (`failures="0" errors="0" skipped="0"`), new since the S10-14 `tests="12"` baseline:
- `lexical_search_queries_the_tenant_scoped_repository` — the half maps straight to `findByKeyword(tenantId, knowledgeBaseId, keyword, 10)` with the default bound and never touches the `EmbeddingPort`.
- `lexical_search_respects_the_topk_bound` — a caller `topK=3` is honored exactly, a caller `topK=999` is clamped to `max-top-k` (50).
- `lexical_search_maps_frs_relevance_to_the_candidate_score` — the FTS relevance surfaces as the candidate `score()`, not a cosine distance.
- `lexical_search_fails_loudly_without_a_database_connection` — no database ⇒ `IllegalStateException` at call time (no provider needed).

The acceptance — **a lexical query executes on the real `english` FTS index and finds the exact term / ID in the corpus, tenant- and collection-scoped** — is proven end-to-end on `pgvector/pgvector:0.8.6-pg18`:
- The reworked [`DocumentChunkRepositoryIT`](apps/agentflow-app/src/integrationTest/java/com/example/agentflow/knowledgebase/persistence/DocumentChunkRepositoryIT.java) (`tests="10"` now) adds the lexical adapter probes: `lexical_search_returns_the_exact_term_and_id` (a query for `retention` returns the term's chunks; a query for `555566667777` returns only the chunk carrying that ID token), `lexical_search_is_tenant_scoped` (two tenants share the term; neither sees the other's chunk — invariant 1), `lexical_search_is_scoped_to_one_collection` (two collections of one tenant + a second tenant, identical term — the query only returns the searched collection; a cross-tenant id is an empty result) and `lexical_search_returns_empty_when_nothing_matches`.
- [`MigrationsTest`](apps/agentflow-app/src/integrationTest/java/com/example/agentflow/MigrationsTest.java) asserts the ordered migration line now ending at `3.3.21` and that the `document_chunks_search_idx` index exists, `search_vector` is a generated `tsvector` (`is_generated = 'ALWAYS'`) carrying its `S10-15` COMMENT.

`:apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest`, `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + formatting (`spotless`) + OpenAPI gates; `:apps:agentflow-app:integrationTest --tests 'com.example.agentflow.knowledgebase.*'` green; `git diff --check` clean.

### Not changed
- No REST endpoint: the lexical search endpoint is a follow-up (`S10-16` wires it).
- No fusion: `lexicalSearch` and `search` return independently; `S10-16` merges, ranks and deduplicates the two halves (and owns the "no evidence" `InsufficientEvidence` policy, `S10-18`).
- No new module edge and no new dependency: `findByKeyword` lives in the existing `knowledgebase.persistence` adapter; no ADR.
- No `english` runtime switch: the language is pinned to `english` at the migration (the corpus is English).
- No pgvector-jdbc, no change to the vector path: the FTS half is orthogonal to the `vector(768)` nearest-neighbour query.


## S10-14 — Tenant/ACL-filtered vector search (P0).

### What changed
- The read half of the glossary "Retrieval" lands in `knowledgebase`: `knowledgebase.application.RetrievalService` — one `search(tenantId, knowledgeBaseId, SearchRequest)` call resolving the collection's pinned `embedding_model`, embedding the query text through the neutral `ai.application.EmbeddingPort`, and matching it against the stored `vector(768)` chunks — with the access predicate **inside** the retrieval query and both bounds (top-k, relevance threshold) applied on top of the nearest-neighbour ordering.
- `DocumentChunkRepository.findNearest` evolves from the S10-11 primitive into the scoped read the service drives: the SQL gains a `documents` join adding the collection scope (`WHERE c.tenant_id = ? AND d.knowledge_base_id = ?`, before the ordering and the limit — a post-filter is never permitted), excludes rows with a `NULL` embedding (an unindexed chunk would otherwise order unpredictably and map to a bogus `0.0` distance), and selects `c.embedding <=> ?::vector AS distance` as a plain `float8` column — the stored `vector` is still never read back (no pgvector-jdbc), and the caller receives the score for the threshold and the future rank fusion (`S10-16`). `DocumentChunkRow` gains the `distance` component.
- The new `knowledgebase.persistence.KnowledgeBaseRepository` is the first read path of the `knowledge_bases` table (S10-05): `findEmbeddingModelByIdAndTenant` reads the pinned model by the `(tenant_id, id)` pair — a cross-tenant id is indistinguishable from a missing one and yields an empty `SearchResult`, so retrieval against a foreign collection simply finds nothing (invariant 1).
- The public search vocabulary the module API map already names: `SearchRequest` (query text + optional `topK`; the tenant and the knowledge base are deliberately *not* part of it — they are context, never body), `ChunkDto` (`id`, `documentId`, `pageFrom`, `pageTo`, `score`, `content` — the fragment text is untrusted content, never logged) and `SearchResult` (an unmodifiable copy; `SearchResult.empty()` is the shared "nothing relevant" answer the future `InsufficientEvidence` policy (`S10-18`) reports).
- Configuration, not code: the validated `agentflow.retrieval.*` group on `AgentFlowProperties.Retrieval` — `default-top-k` (10), `max-top-k` (50, the hard ceiling a caller `topK` is clamped to) and `threshold` (0.5, a cosine distance bounded to `[0.0, 2.0]`) — wired by `knowledgebase.application.config.RetrievalConfiguration` following the `DocumentEmbeddingConfiguration` precedent (`ObjectProvider` for the conditional repositories and the opt-in port). The S01-02 contract holds: a default boot has no database and no provider, and `search` fails loudly at call time (`ADR-014`, the `PlannerService` idiom).

### Why
The S10-11 primitive persisted and ordered rows; it deliberately did not decide *who may see* a row or *how much* comes back — those are the S10-14 acceptance, and both belong inside the query, not around it. The tenant predicate alone is not the access model: the search API the plan fixes (`POST /api/v1/knowledge-bases/{id}/search`) is per collection, so the retrieval query joins `documents` and scopes by `knowledge_base_id` too — two collections of near-identical documents under different tenants (or one tenant) can then never leak into each other's results, no matter how close the embeddings. The NULL-embedding exclusion closes a latent correctness hole in the S10-11 query: `NULL <=> vector` is NULL, orders unpredictably and would surface an unindexed chunk as a false zero-distance hit. Reading the model from the collection row (rather than a global default) keeps the query vector in the same space the stored vectors were produced in, per collection. The bounds live in configuration because a limit that ships as a magic number cannot be tuned per deployment; the threshold is a *distance* cut applied over the ordered candidates, which keeps the repository a generic primitive and the service the policy owner.

### Verification
32 new DB-free tests, all JUnit-XML-verified (`failures="0" errors="0" skipped="0"`):
- `RetrievalServiceTest` `tests="12"` — the happy path (the collection's own model embeds the query; rows map to scored, distance-ordered DTOs), the default/honored/clamped `topK`, the threshold dropping candidates beyond it (boundary kept), the empty result for a missing or cross-tenant collection without ever calling the provider or the repository, the fail-loud shapes (no database / no `EmbeddingPort`), the entry-point guards and the constructor validation.
- `SearchRequestTest` `tests="5"`, `ChunkDtoTest` `tests="4"`, `SearchResultTest` `tests="4"` — the value contracts.
- `RetrievalConfigurationTest` `tests="3"` — the service binds under the default and the custom `agentflow.retrieval.*` values, and a context with no database and no provider boots with the service present, failing loudly at `search` time.
- `RetrievalPropertiesTest` `tests="4"` — the group binds with its defaults, custom values override, and an out-of-range `threshold` / a `max-top-k` below one fails startup through the validated binding.

The acceptance — **retrieval never returns another tenant's chunks even at maximally close embeddings** — is proven end-to-end on `pgvector/pgvector:0.8.6-pg18` (the exact image Compose pins, never `H2`):
- [`RetrievalServiceIT`](apps/agentflow-app/src/integrationTest/java/com/example/agentflow/knowledgebase/application/RetrievalServiceIT.java) `tests="7"` drives the real service against the real Flyway-migrated schema with a deterministic in-memory `EmbeddingPort`: two tenants holding collections with *identical* embeddings retrieve only their own chunks; the search is scoped to one knowledge base inside a tenant; a cross-tenant knowledge-base id yields an empty result; the threshold, the requested top-k cap and the NULL-embedding exclusion hold through the real query; an empty collection is a plain empty answer.
- The reworked [`DocumentChunkRepositoryIT`](apps/agentflow-app/src/integrationTest/java/com/example/agentflow/knowledgebase/persistence/DocumentChunkRepositoryIT.java) `tests="6"` pins the adapter: ordering with the distance mapped alongside (matching the cosine math within the float literal round-trip), tenant scope, collection scope at maximal similarity, the NULL-embedding exclusion, the 768-dim database guard and the wrong-width query refusal.

`:apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest`, `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + formatting (`spotless`) + OpenAPI gates; `:apps:agentflow-app:integrationTest --tests 'com.example.agentflow.knowledgebase.*'` green; `git diff --check` clean.

### Not changed
- No REST endpoint: the search API (`POST /api/v1/knowledge-bases/{id}/search`) is a follow-up; the service is the public boundary the controller will drive. The lexical half's endpoint is the same follow-up (`S10-16`).
- The hybrid rank **fusion** and citations still build on this result (`SearchResult` / `ChunkDto` are the vocabulary they extend); the no-evidence `InsufficientEvidence` policy is `S10-18`. The lexical full-text half (`S10-15`) now exists alongside `search` — `RetrievalService.lexicalSearch` over the `english` FTS index — but the two halves return independently; `S10-16` is the fusion.
- No new module edge: `knowledgebase → ai` (arrow 5 in the module map, the embedding port) was already allowed, so no ADR; no migration (the S10-11 column and the S10-05 table already carry everything the query needs); no contract files.
- No pgvector-jdbc: the distance is computed in SQL and mapped as `float8`, so the stored `vector` is still never read back.


## S10-13 — Indexing worker and idempotent chunk indexing (P0).

### What changed
- The indexing half of the extract → chunk → embed → index pipeline (the embed half landed in `S10-12`): `ingestion.application.IngestionService` runs one pass per `document.ingestion-requested.v1` event — a guarded **claim** of the document (`UPLOADED`/`QUEUED`, plus `INDEXED` under the UPDATE redelivery policy), object-payload fetch through the new opt-in `knowledgebase` read port `DocumentPayloadStore` (no Garage client yet — the port fails loudly at call time, `ADR-014`, the client is a follow-up), Tika extraction (`S10-07`), deterministic chunking (`S10-09`), batch embedding (`S10-12`), and an **idempotent chunk write** through the new `knowledgebase.application.ChunkIndexingService` (`ON CONFLICT` on the S10-10 unique key `(document_id, chunk_index, pipeline_version)` — a database guard, never a post-check). The pass ends in one transaction: `PROCESSING -> INDEXED` via the conditional `DocumentTransitionService` and the `agentflow.document.indexed.v1` outbox record with the claim (invariant 3); a crash rolls the whole pass back, so a redelivery starts clean from a claimable state.
- The `knowledgebase` module gains its indexing-facing public API (the worker never touches its persistence): `DocumentQueryService` (the `(tenant_id, id)` read returning the `DocumentView` — status kept as a plain `String`, the consumer never imports the owner's domain enum), `DocumentTransitionService` (the **sole writer** of `documents.status` on the indexing path — conditional `UPDATE ... SET status = ..., version = version + 1 WHERE ... AND status IN (...)`, zero rows → `false`, so two workers can never both claim a document), `ChunkIndexingService` (the SKIP/UPDATE upsert policy), plus the public values `DocumentView`, `ChunkToIndex`, `ChunkIndexingResult`. `DocumentRepository` gains `findIndexRowByIdAndTenant` (a JOIN with `knowledge_bases` for the embedding model — the tenant predicate is inside the query) and the four conditional transitions; `DocumentChunkRepository` gains the conflict-tolerant inserts.
- The Kafka worker: `ingestion.worker.DocumentIndexingWorker` — a poll loop on `agentflow.document.ingestion-requested.v1` with **manual offset commits only after a durable inbox outcome** (PROCESSED/OTHER_CLAIMED ack, RETRY/DEAD_LETTER held uncommitted and skipped in-session by seeking past the record, so records after a held one are re-read, never dropped); dedup runs through the production `InboxConsumerService`/`InboxDedupService` and failures through `DlqService` — the worker contains transport only, no business logic. `IngestionEventEnvelope` parses the consumed value leniently (anything without a full `eventId`/`tenantId`/`payload.documentId` identity is committed past — an unparseable record has no dedup anchor). `IngestionWorkerLifecycle` starts the loop only with a `DataSource` and `agentflow.worker.ingestion-loop-enabled=true` (default **false** — the `ingestion-worker` role is explicit, never everywhere-by-default), with `agentflow.worker.ingestion-poll-timeout-ms` (@Min 100, default 1000) and a graceful `@PreDestroy` stop.
- Configuration: `agentflow.ingestion.indexing.redelivery-policy` (`skip` | `update`, default `skip`, an unknown value fails the wiring at startup — never silently defaulted), stored as the `AgentFlowProperties.Ingestion.Indexing` string and mapped to the `ingestion.domain.RedeliveryPolicy` enum in `IngestionConfiguration` (the config package does not import `ingestion.domain`, matching the `ChunkingConfig` precedent). SKIP absorbs a redelivery of an already-indexed document (the pass returns as a no-op); UPDATE re-claims and overwrites the chunk rows in place.

### Why
The S10-13 plan item: the consumer of `document.ingestion-requested.v1` running the full upload pass and publishing `document.indexed.v1`, idempotent under redelivery. The reliability stance follows the invariants: the whole pass lives inside the inbox transaction (`InboxDedupService`), so atomicity of claim + index + event is the transaction itself and the Kafka offset is acknowledged only after the outcome is durable — a commit/ack gap redelivers into an already-recorded inbox row, which acks as `OTHER_CLAIMED`. Deduplication is a database guard (the S10-10 unique key + `ON CONFLICT`), not a read-then-write: under SKIP a redelivered event that somehow reaches the write path still cannot create a second row. The tenant id on the event is correlation metadata re-checked against PostgreSQL in every query (`(tenant_id, id)` — a UUID is not authorization); an empty payload is not an error (`S10-07` semantics) — the document completes with zero chunks, retrieval simply finds nothing in it.

### Verification
39 new tests, all JUnit-XML-verified (`failures="0" errors="0" skipped="0"`):
- 31 DB-free unit tests — `IngestionServiceTest` `tests="10"` (the happy path claim → parse → chunk → embed → index → complete with the outbox enqueue in the same pass, SKIP/UPDATE redelivery, the claim-failed no-op, a mismatched embedding width failing the document with `EMBEDDING_DIMENSION_MISMATCH` before any write, the empty-payload zero-chunk completion), `DocumentIndexingWorkerTest` `tests="6"` (the ack/hold contract: durable outcomes commit, failed ones stay uncommitted and are re-read, an identity-less record is committed past, records after a held one are never dropped), `IngestionEventEnvelopeTest` `tests="7"`, `RedeliveryPolicyTest` `tests="3"`, `IngestionConfigurationTest` `tests="5"` (the service binds under the default and the `update` policy, an unknown policy fails the wiring, and the worker beans stay absent without the role flag — even with the flag and no `DataSource`).
- The acceptance — [`IngestionIndexingIT`](apps/agentflow-app/src/integrationTest/java/com/example/agentflow/ingestion/IngestionIndexingIT.java) `tests="8"` against `pgvector/pgvector:0.8.6-pg18` (the exact image Compose pins, never `H2`), driving the real inbox boundary, the real repositories and the Flyway-migrated schema with a deterministic in-memory embedding port and payload store: **a redelivered ingestion event creates no duplicate logical chunks** (same event id → `OTHER_CLAIMED`, chunk count and indexed-event count unchanged), a second event under SKIP is absorbed, under UPDATE it refreshes in place (still one row, a new indexed event), a wrong embedding width / a corrupt payload fail the document with the safe codes, a transient provider fault holds the event (`RETRY`, the rolled-back claim leaves the document `UPLOADED`, the inbox row exhausted at 3 attempts with a DLQ `RETRY` record), an empty payload completes with zero chunks, and a cross-tenant event is a permanent `DEAD_LETTER` that never touches the document.

`:apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest`, `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + formatting (`spotless`) + OpenAPI gates; `:apps:agentflow-app:integrationTest --tests 'com.example.agentflow.ingestion.IngestionIndexingIT'` green; `git diff --check` clean.

### Not changed
- No Garage/S3 client: `DocumentPayloadStore` is the opt-in port and fails loudly until a client lands (a follow-up, like the S10-12 provider adapter); the awssdk dependencies the compose stack anticipates stay unused.
- No contract files under `contracts/` (the repo has none for ingestion events — the envelope shape is documented in `docs/architecture/events/README.md` and parsed leniently by identity only).
- No new module edge: `ingestion → knowledgebase` / `messaging` / `ai` were already allowed, so no ADR — unlike S10-12's `ingestion → ai`, which ADR-016 had to record.
- No API surface: the worker is internal; the redelivery policy is worker-side configuration, not an operator API.

## S10-12 — Embeddings adapter and batching (P0).

### What changed
- The embed half of the extract → chunk → embed → index pipeline (the indexing half that persists the vectors is `S10-13`): `ingestion.application.embed.EmbeddingGenerationService` turns a chunk set into embeddings by fanning the chunk texts out to the neutral `ai.application.EmbeddingPort` in bounded batches and validating every provider response before it reaches the caller, returning the aggregated `EmbeddingBatchResult` (vectors in the exact input order, total tokens, uniform width).
- `ai.provider.ResilientEmbeddingModel` — the embedding twin of `ResilientChatModel`: one `EmbeddingPort` wrapped with bounded retry (exponential backoff + full jitter over `ProviderResiliencePolicy`, so a provider rate limit / 5xx / timeout / connection fault is retried while the policy allows), an overall timeout per attempt (abort → `ProviderTimeoutException`, which the production `ProviderExceptionClassifier` now matches explicitly, alongside the existing chat timeout type) and a concurrency cap (overload → a retryable `ProviderConcurrencyLimitedException` backpressure signal). Time, randomness, the sleeper and the call executor are all injected (invariant 10).
- `ProviderExceptionClassifier` matches `ResilientEmbeddingModel.ProviderTimeoutException` as `TIMEOUT` next to the existing `ResilientChatModel` one.
- Configuration: `AgentFlowProperties.Ingestion.Embedding` — `agentflow.ingestion.embedding.batch-size` (default 32, `@Min(1)`, nested in the `Ingestion` group like `Outbox.Publisher`, so the key really binds); wired by `ingestion.application.config.DocumentEmbeddingConfiguration`, following the `DocumentChunkingConfiguration` idiom (bean built from configuration, not a `@Component`).
- A new compile edge `ingestion → ai` (arrow 19), recorded in [`ADR-016`](../adr/ADR-016-ingestion-ai-embedding-edge.md): `ingestion` reaches `ai` only through the public `ai.application` port contract types (`EmbeddingPort`, `EmbeddingRequest`, `EmbeddingResponse`, `EmbeddingUsage`), never `ai.provider`. The architecture gate (`ArchitectureBoundaryTest` ALLOW map), the declared module graph (`PackageDependencyGraphTest`), the module map (mermaid arrow 19, the dependency table, the module-responsibilities table) and the module API map (the `ingestion` and `ai` rows) are updated; the graph stays acyclic.
- The port stays opt-in (`ADR-014`): a default boot has no `EmbeddingPort` bean, so the service is constructed without a port and `embed(...)` fails loudly at call time (`IllegalStateException`, naming the missing provider) — the app boots without a provider or an AI key exactly as before (S01-02), the `PlannerService` / `ChatModelPort` optional-port idiom. `DocumentEmbeddingConfiguration` passes `ObjectProvider.getIfAvailable()` straight through.
- Fake embeddings for CI: `ai.provider.FakeEmbeddingProvider` (test sources, the `S09-11` pattern — compiled into no production jar), wired by the same opt-in `agentflow.ai.provider=fake` gate through `FakeModelConfiguration` (`agentflow.ai.provider.fake.embedding-mode`, default `deterministic`). `DETERMINISTIC` answers one 768-wide vector per text, every vector derived from the SHA-256 of the text (the `vector(768)` width `V3_3_20` pins), order-preserving, with a deterministic token estimate; `RATE_LIMIT` / `TIMEOUT` throw the production fault types (a 429 `OpenAIServiceException`, a `TimeoutException` cause) so the fixtures flow through the real `ProviderExceptionClassifier`.

### Why
The S10-12 plan item: define the embeddings port (done in `S09-01`), batch chunks, handle provider rate limits and retries, prepare fake embeddings for CI. The embed step belongs to the pipeline owner (`ingestion`, module-map business-rule table), while everything provider-specific — the SDK, the failure classification, the retry/timeout/concurrency bounds — belongs to the single provider owner (`ai`, `ADR-014`): batching and ordering are pipeline semantics, rate limiting is provider semantics. The module-API map (`S02-04`) and the `EmbeddingPort` javadoc (`S09-01`) already named `ingestion` as the embedding caller, but the dependency table lacked the edge — `ADR-016` records it instead of smuggling it past the gate. The response validation (one vector per text, uniform width, order) is what lets `S10-13` persist vectors against the fixed `vector(768)` column without re-checking each one; a provider that breaks the contract fails the run loudly instead of leaving a silently mis-aligned result in the index.

### Verification
Four new DB-free suites plus the extended fake-wiring suite, 35 tests total (JUnit XML checked: `ResilientEmbeddingModelTest` `tests="7"` + `FakeEmbeddingProviderTest` `tests="9"` + `EmbeddingGenerationServiceTest` `tests="11"` + `DocumentEmbeddingConfigurationTest` `tests="3"` + `FakeModelConfigurationTest` `tests="5"`; all `failures="0"` `errors="0"` `skipped="0"`) — the S10-12 acceptance, the embeddings pipeline verified without a real model:
- `ResilientEmbeddingModelTest` — the S10-12 rate-limit requirement: a 429-classified fault is retried until the call succeeds (exact backoff 1 s / 2 s in the sleep log, injected seams), a permanent fault fails on the first attempt, the attempt budget is bounded (3 faults → 3 calls, last fault thrown), a single attempt is aborted by the overall timeout and classified `TIMEOUT` by the production classifier, the concurrency cap rejects overload with the retryable backpressure fault while an in-flight call holds the only permit.
- `EmbeddingGenerationServiceTest` — batching (5 texts / batch size 2 → exactly 3 port calls with the exact batch contents, order preserved across boundaries), single-batch passthrough, defensive copies, contract violations failing loudly (short vector count, empty vector, non-uniform width across batches), provider fault propagation failing the whole run, entry guards, the opt-in shape (service constructs with no port, `embed` fails loudly).
- `FakeEmbeddingProviderTest` — determinism (same text → same vector, order preserved, distinct texts → distinct vectors), the 768 width and token accounting, fault fixtures classified `RATE_LIMIT` / `TIMEOUT` by the real classifier, config resolution.
- `DocumentEmbeddingConfigurationTest` — the service binds `batch-size=7` from `agentflow.ingestion.embedding.*`, defaults to 32, and a context with no provider boots with the service present and failing loudly at call time.

`:apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest` — the `ingestion` boundary is now `{ingestion, knowledgebase, messaging, ai}`, `tests="1"`; `PackageDependencyGraphTest` `tests="4"`; `NoCyclicModuleDependenciesTest` `tests="1"` — each verified running in the JUnit XML, `failures="0"` `skipped="0"`) + no-cycles + formatting (`spotless`) + OpenAPI gates; `git diff --check` clean.

### Not changed
- No concrete provider embedding adapter (Spring AI `EmbeddingModel` behind `EmbeddingPort`, wired under `agentflow.ai.provider=openai` / a local Ollama profile reusing `agentflow.ai.retry.*`) — a follow-up; the port and its resilient wrapper are in place (`ADR-016` follow-ups).
- No persistence change: `S10-13` drives the service and writes the vectors through `DocumentChunkRepository.insert`; the `vector(768)` column from `S10-11` is untouched.
- The plan's §4.2 `ingestion` outgoing-deps cell still reads `knowledgebase, messaging`; the edge is recorded in `ADR-016` and the module map (arrow 19), matching the `ADR-015` precedent, without rewriting the plan file.

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

## S10-21 — Document the RAG pipeline and the quality check (P0).


### What changed
A new [`docs/rag/pipeline.md`](docs/rag/pipeline.md) — the end-to-end RAG pipeline **as it runs today** (anchored to the runtime, not the plan's target state): upload → chunk → embed → index → retrieve → ground. The existing [`docs/rag/README.md`](docs/rag/README.md) is retrieval/grounding-focused (`S10-11` … `S10-18`); this closes the gap the plan fixes by documenting the write direction and the eval in the same anchored place. Anchored to the current code (`RetrievalService`, `CitationBuilder`, `GroundingPolicy`, the `ingestion` worker):

- **Two directions, one page.** A Mermaid diagram separates the *write* path (upload → chunk → embed → index, async, off the request path) from the *read* path (retrieve → ground, synchronous). The write side rides the `documents` lifecycle (`UPLOADED → QUEUED → PROCESSING → INDEXED`, `FAILED`, `DELETE_REQUESTED → DELETED`, `document-lifecycle.md`); the read side is one tenant-scoped `RetrievalService` call.
- **The write path (`S10-03` … `S10-13` + `S10-19`).** Upload + the accept/bound layer (`DocumentUploadValidator` — size, MIME + magic bytes, `sha256`), the server-owned key `documents/<tenantId>/<documentId>[.<ext>]` with a sanitised extension (`DocumentObjectKey`, `S10-04`), the `UPLOADED → QUEUED` outbox enqueue (`S10-06`), the `DocumentIndexingWorker` pass over the `agentflow.document.ingestion-requested.v1` topic (extract → chunk with `pipelineVersion() = 'rag-ingest-pipeline-v1'` → batched embed → `ON CONFLICT` upsert on `(document_id, chunk_index, pipeline_version)` → `document.indexed.v1` in the same transaction, `S10-13`), and the `S10-19` idempotency: duplicate upload by `(tenant_id, sha256)`, manual `FAILED → QUEUED` retry, and reindex on pipeline-version change.
- **The read path (`S10-14` … `S10-18`).** The two independent halves — `findNearest` (vector, S10-14) and `lexicalSearch` (English FTS, S10-15) — fused by `RankFusion` (reciprocal-rank, dedup by id, cap) in `hybridSearch` (S10-16); one `CitationDto` per real chunk (`S10-17`); the sealed `GroundedAnswer` / `GroundingPolicy` no-evidence decision (`S10-18`). The top-k / threshold bounds and the no-label meters (`agentflow.knowledgebase.retrieval.*`) are noted.
- **Tenant isolation and the invariants, in both directions** (invariant 1 — the `tenant_id` predicate is inside the query, never a post-filter; invariant 2 — PostgreSQL is the source of truth; invariant 3 — at-least-once + idempotent redelivery; invariants 7/8 — retrieved content and the model are untrusted; invariant 9 — no high-cardinality metric labels).
- **The eval command (`S10-20`).** How `RagEvalRunner` drives the production `RetrievalService` over the fixed, model-free `RagEvalEmbedding` corpus, what it measures (recall@1/3/5/10, citation correctness, the evidence verdict), the run gate, and the recorded baseline rendering (`15/15 cases passed`), diffed against any change.
- **How the pieces fit** — a module-ownership table (`knowledgebase` / `ingestion` / `ai` per step) — and **the documented gap**: the retrieval, citation and grounding methods are `knowledgebase.application` application services (driven by the eval runner and the unit / integration suites) but there is **no `knowledgebase.web` controller yet** — the end-to-end `POST …/answers` endpoint that wires `groundedAnswer`'s output into the model is a follow-up, not part of the `S10` deliverable.

### Why
The `S10-20` acceptance is "document the RAG pipeline and the quality check," and the existing docs split the two halves: `docs/rag/README.md` (S10-11 follow-up) documents retrieval/grounding; the write path and the eval were documented only per-task (`S10-03` … `S10-13` in the devlog, `S10-20` in `docs/evaluation/rag/`). A reader had to assemble the full pipeline from three places, and could mistake a planned step for an implemented one. This is a documentation-only follow-up to `S10-11` with no code change: it says *what the current code does*, anchored to `RetrievalService` / `CitationBuilder` / `GroundingPolicy` / the `ingestion` worker, and links to the owning documents (`domain-glossary.md` §7/§8, `module-map.md`, `module-api.md`, `document-lifecycle.md`, the ADRs) and the eval (`docs/evaluation/rag/`).

### Verification
The doc is anchored to the runtime, verified against the code rather than the plan:

- `RetrievalService` public API read — `search` / `lexicalSearch` / `hybridSearch` / `citations` / `groundedAnswer` (the five entry points the doc names); `CitationBuilder` / `GroundingPolicy` / `GroundedAnswer` / `RankFusion` read; the ingest worker's `DocumentIndexingWorker.TOPIC = "agentflow.document.ingestion-requested.v1"` and the `document.indexed.v1` outbox topic confirmed; `DocumentObjectKey` canonical shape `documents/<tenantId>/<documentId>[.<ext>]` with the sanitised-extension set confirmed; `Chunker.pipelineVersion() = "rag-ingest-pipeline-v1"` and the `(document_id, chunk_index, pipeline_version)` `ON CONFLICT` upsert confirmed.
- The **eval command runs green** in this environment — `./gradlew :apps:agentflow-app:test --tests '*RagEvalRunnerTest*' --rerun-tasks` → `BUILD SUCCESSFUL`; the baseline rendering quoted in the doc (`15/15 cases passed`, the per-case `recall@1=1.00 … citations=correct evidence=as-expected` lines) matches the recorded baseline in [`docs/evaluation/rag/corpus.md`](docs/evaluation/rag/corpus.md) exactly.
- `docs/README.md` index updated (the tree-diagram line and the AI/RAG/evaluation table row both now reference `docs/rag/pipeline.md`).

`./gradlew :apps:agentflow-app:check` is already green (this task adds no code), so the architecture, formatting (`spotless`) and OpenAPI gates are unchanged.

### Not changed
No code, no migration, no REST endpoint, no new module edge, no ADR — `S10-21` is documentation only and changes no module ownership, persistence approach or invariant. The only touched files are `docs/rag/pipeline.md` (new) and the `docs/README.md` index. Related changelog entry: **The RAG pipeline and the quality check (S10-21)** in [`CHANGELOG.md`](../../CHANGELOG.md) (Unreleased → Added).
## S10-20 — RAG evaluation corpus (P1).


### What changed
The `knowledgebase` module gains a fixed RAG evaluation corpus — two–three permitted sample documents (three, plus one foreign decoy) and fifteen questions with reference pages/chunks, scored on recall@k and citation correctness — so every retrieval change is compared on a fixed set (the S10-20 acceptance). It lives in a new internal package `com.example.agentflow.knowledgebase.evaluation`:

- **`RagEvalCorpus`** (the fixed set): three synthetic, eval-authored sample documents — `agentflow-retrieval-runbook.md` (3 pages, 6 chunks), `warehouse-inventory-policy.md` (2 pages, 4 chunks), `q3-platform-uptime-report.md` (2 pages, 3 chunks) — thirteen owned chunks with page references, one **foreign decoy chunk** owned by a second fixture tenant and worded to vectorise *maximally close* to the `a-tenant-predicate-placement` question, and fourteen evidence questions plus one off-topic question naming the reference chunks the answer must be found in. All documents are synthetic (no third-party text, no personal data), every id a labelled fixture constant. The tenant / knowledge-base ids are data, never an authorization source.
- **`RagEvalEmbedding`**: the deterministic eval embedding — a hashed bag of words (lowercased alphanumeric tokens, a small English stopword set removed, length ≥ 3) into the same 768-dimensional space the production `document_chunks.embedding` stores, L2-normalised. Chunk and query vectorise through one pure function: no model, no API key, no network (the S10-12 fake-provider idea applied to the retrieval eval), and unrelated texts sit at the maximum cosine distance 1.0 — the separation the recall metric and the no-evidence case rely on.
- **`RagEvalRunner` / `RagEvalReport` / `RagEvalCaseResult`**: the runner drives the **production** `RetrievalService` — `hybridSearch` (vector half S10-14 + lexical half S10-15 + reciprocal-rank fusion S10-16), `citations` (the S10-17 layer) and the production `GroundingPolicy` (S10-18) — through the fixed questions and scores each case on `recall@1/3/5/10` against the reference chunks, citation correctness (one citation per retrieved chunk, every citation resolving to an **owned** corpus chunk with the exact document / page metadata — never a chunk the retrieval did not return or another tenant owns) and the expected evidence verdict. `RagEvalReport.render()` is byte-stable (fixed `\n`, no timestamps, corpus order) and carries only case ids, counts and verdicts — no query text, no snippet (invariants 7/8/9) — so two retrieval configurations diff directly and the regression names the case and the metric.


### Why
Retrieval quality — recall, citation grounding, the no-evidence behaviour — is exactly what the platform promises (glossary §7/§8), and it regresses silently: a retuned `threshold`, a `RankFusion` change or a new chunking pipeline can each look reasonable in review and still break grounding. The plan fixes the defence: a small, stable corpus of permitted sample documents with reference pages/chunks, scored on recall@k and citation correctness, so a retrieval change is a diff against a known-good answer, not an eyeball. The runner measures the real path, not a parallel one — the same `hybridSearch` / `citations` / `GroundingPolicy` production calls a tenant-scoped RAG call runs — and the corpus pins two behaviours no recall number shows: tenant isolation (the decoy question only passes while the tenant predicate keeps the closer foreign chunk out of the result) and the no-evidence policy (the off-topic question must report `InsufficientEvidence`, never an invented citation). The eval embedding is deliberately model-free: the corpus must run in `./gradlew check` with no keys and no network, and a change to it is a fixture change, visible in the diff.


### Verification
One new DB-free suite, JUnit-XML-verified (`failures="0"` `errors="0"` `skipped="0"`): [`RagEvalRunnerTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/evaluation/RagEvalRunnerTest.java) (7 tests). It wires the production `RetrievalService` to an in-memory fixture store — a scripted `EmbeddingPort` answering with `RagEvalEmbedding`, and `DocumentChunkRepository` / `KnowledgeBaseRepository` subclasses emulating the production query semantics (the `tenant_id` predicate applied inside the query, cosine ordering with the 768-width guard, the lexical token match) over the corpus. The tests pin: the baseline passes end to end — **15/15 cases**, every evidence question at full `recall@1` through `recall@10`, `citations=correct`, the expected evidence verdict; the foreign decoy is never retrieved even though it is the closest chunk in the store (`a-tenant-predicate-placement` still passes with `recall@1=1.00`); the off-topic question reports `InsufficientEvidence` with zero retrieved chunks; and the rendered report is byte-stable across runs. The exact baseline rendering is recorded in [`docs/evaluation/rag/corpus.md`](../../docs/evaluation/rag/corpus.md). Run gate: `./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.knowledgebase.evaluation.*'`.

`./gradlew :apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest` — the `knowledgebase` boundary is unchanged, `evaluation` is an internal package of the same module; `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + formatting (`spotless`) + OpenAPI gates; `git diff --check` clean.


### Not changed
No migration, no REST endpoint, no new module edge — the whole corpus lives inside `knowledgebase.evaluation` (internal) and the runner reads only the module's own public `application` services; the `ai` `EmbeddingPort` it is wired against is the existing opt-in seam (`ADR-014`). No ADR — `S10-20` changes no module ownership, persistence approach or invariant. Docs: [`docs/evaluation/rag/README.md`](../../docs/evaluation/rag/README.md) + [`corpus.md`](../../docs/evaluation/rag/corpus.md), the [`docs/README.md`](../../docs/README.md) index, the `knowledgebase` package layout in its `AGENTS.md`, and the module API map (`docs/architecture/module-api.md`). Related changelog entry: **RAG evaluation corpus (S10-20)** in [`CHANGELOG.md`](../../CHANGELOG.md) (Unreleased → Added).

## S10-19 — Duplicate upload and manual retry of a failed document (P0).


### What changed
The document lifecycle (`UPLOADED → QUEUED → PROCESSING → INDEXED`, `FAILED`, `DELETE_REQUESTED`, `DELETED`; `document-lifecycle.md`) gains predictable duplicate-upload and failed-document-retry behaviour, plus a reindex trigger on pipeline-version change — the two halves of `S10-19`.

- **Duplicate upload by content.** `knowledgebase.application.DocumentService#upload(tenantId, knowledgeBaseId, ValidatedUpload, createdBy)` now resolves the idempotency key `(tenant_id, sha256)` (plan §8.2) before writing anything: `documentRepository.findByTenantAndSha256(tenantId, sha256)` runs a tenant-scoped lookup (invariant 1 — the `tenant_id` predicate is part of the query itself, so another tenant's content-identical payload is indistinguishable from a missing one and yields a fresh upload, never a false hit). When the owning document exists it returns `new DocumentDto(existing.documentId(), existing.knowledgeBaseId(), existing.status())` with no new row and no new event — a re-upload of identical content is absorbed idempotently, never treated as exactly-once (invariant 3). The `sha256` column is indexed, not made unique (determined in S10-05, `V3_3_18`), so a `pipeline_version` change still reindexes as a new document. `DocumentLookupRow` (new `knowledgebase.persistence` value type) is the minimal projection (`id`, `knowledge_base_id`, `status`) the two lookups need.
- **Manual retry of `FAILED`。** `DocumentService#retryFailed(tenantId, documentId, createdBy)` is the retry edge the lifecycle transition table leaves open. It is `@Transactional`: it re-checks the document is tenant-owned (`findByTenantAndId` ⇒ `404` otherwise, invariant 1), rejects a document not in the terminal `FAILED` state (`400` — nothing to retry), then releases it to `QUEUED` (`DocumentTransitionService#releaseFailedToQueued`, guarded by `status = 'FAILED'`, clears the stale `error_code` / `error_summary` diagnostic and bumps the optimistic `version`) and re-enqueues a fresh `document.ingestion-requested.v1` through the transactional outbox in the same transaction (a new event id, so it is not a duplicate of the original already-consumed request, invariant 3). It is `503` without a database (S01-02 — the transition writer is an opt-in `@Autowired(required = false)` constructor parameter, `null` ⇒ `requireTransition` fails loudly instead of the app failing to boot).
- **Reindex on pipeline-version change.** `ingestion.application.IngestionService#index` records the reindex trigger the schema declares: it computes `reindexOnVersionChange = !chunker.pipelineVersion().equals(document.pipelineVersion())` (the deterministic label `Chunker.pipelineVersion()` seeds on, S10-09) and claims the document for indexing when that is true — or when the redelivery policy is `update` — so a retuned chunking/embedding pipeline re-embeds the chunks under the new version rather than being a no-op on an already-`INDEXED` document. `complete(...)` and `indexedPayloadJson(...)` thread the just-completed `pipeline_version` through, and `DocumentTransitionService#markIndexed` / `DocumentRepository#markIndexed` record it (with a `null` / blank guard) so the document always reflects the pipeline version backing it.


### Why
`S10-13` made chunk indexing idempotent per `(document_id, chunk_index, pipeline_version)`, but left two behaviours open: what a re-upload of identical content does, and what a retuned pipeline does to an already-indexed document. The plan fixes both. Duplicate-by-content keeps ingestion idempotent (a repeated upload creates no duplicate logical document or chunk, invariant 3 — never exactly-once). The manual retry edge lets an operator re-drive a document that hit a permanent / transient-exhausted `FAILED` without re-uploading the bytes. The reindex trigger keeps the invariant that a document always reflects the pipeline version currently backing it (S10-10 / S10-13): a retuned chunking or embedding configuration must re-embed, and recording the completed `pipeline_version` on `markIndexed` makes the change detectable. All three stay within the `knowledgebase` / `ingestion` modules and their existing seams (no new module edge), and each is unit-testable DB-free — the upload / retry paths mock the `documents` writer and the outbox; the reindex path mocks the transition service and the chunker.


### Verification
Four DB-free suites plus one real-PostgreSQL suite, all JUnit-XML-verified (`failures="0"` `errors="0"` `skipped="0"`):

- [`DocumentRepositoryTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/persistence/DocumentRepositoryTest.java) (8, new): `claimForIndexingUpdatesProcessingsForTheClaimableStates` asserts the two claimable state names are bound (without pinning the `IN (...)` position the `Set.of` iteration order yields), `claimForIndexingRejectsEmptyClaimableStates` (an empty claimable set is a programming error, not a transition), `requeueFailedToQueuedIsGuardedByFailedStatus` (guards `status = 'FAILED'` and clears the diagnostic), and `markIndexedRecordsThePipelineVersionAndPageCountGuardedByProcessing`.
- [`DocumentTransitionServiceTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/application/DocumentTransitionServiceTest.java) (8, new): `claimForIndexingReindexFlagSelectsTheClaimableStates`, `markIndexedForwardsThePipelineVersionAndPageCount`, `markIndexedRejectsBlankPipelineVersion`, `releaseFailedToQueuedDelegatesToTheQueueFailedTransition`, and fail-loud without a database.
- [`DocumentServiceTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/application/DocumentServiceTest.java) (11): `uploadReturnsExistingDocumentOnDuplicateTenantScopedChecksum` (returns the existing row, no new insert / enqueue), `uploadOnOtherTenantIdenticalChecksumIsFreshUpload` (cross-tenant ⇒ miss), `retryFailedReleasesFailedToQueuedAndReenqueues`, `retryFailedOnNonFailedDocumentIsRejected` (`400`), `retryFailedOnMissingTenantOwnedDocumentIsNotFound` (`404`), `retryFailedFailsLoudlyWithoutTransitionWriter` (`503`).
- [`IngestionServiceTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/ingestion/application/IngestionServiceTest.java) (10): the reindex-on-version-change claim path.
- [`DocumentRepositoryIT`](../../apps/agentflow-app/src/integrationTest/java/com/example/agentflow/knowledgebase/persistence/DocumentRepositoryIT.java) (7, new — a real PostgreSQL, `pgvector/pgvector:0.8.6-pg18`, the exact image Compose pins): the SQL the mocked unit suites cannot prove — the duplicate lookup hits within the tenant and misses another tenant's content-identical payload, the retry existence check is tenant-scoped (`404` material for a foreign id), `requeueFailedToQueued` releases only a `FAILED` row (clearing the stale diagnostic, bumping the optimistic `version`) and touches nothing otherwise, and `markIndexed` records the `pipeline_version` / `page_count` guarded by `PROCESSING` (a non-claimed document is not completed). `IngestionIndexingIT` (8) re-ran green against the changed indexing path.

The previously flaky `claimForIndexingUpdatesProcessingsForTheClaimableStates` (~25 %, reproduced 12+ runs): the assertion pinned the exact positions of the two claimable state names in the `IN (?, ?)` args, but the args are bound from a `Set.of(UPLOADED, QUEUED)` iteration order rendered non-deterministically through Mockito's varargs `JdbcTemplate.update(String, Object...)` capture (the `JdbcTemplate` overloads are `update(String, Object[])` and `update(String, Object...)`; Mockito captures the varargs array run-to-run). The SQL placeholders and the args are built from the same set in the same order, so the clause is always valid and consistent — the real invariant is that both claimable state names are bound, not their position, so the assertion now checks `containsExactlyInAnyOrder(UPLOADED.name(), QUEUED.name())`. Verified stable over 20 fresh `--rerun-tasks` runs.

`./gradlew :apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest` — the `knowledgebase` boundary is `{knowledgebase, ai, workflowexecution, messaging}`, `ingestion` `{ingestion, knowledgebase, messaging, ai}`, unchanged; `PackageDependencyGraphTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + formatting (`spotless`) + OpenAPI gates; `git diff --check` clean.


### Not changed
No migration (the `(tenant_id, sha256)` index and the `pipeline_version` column already exist from `V3_3_18` / `V3_3_19`), no REST endpoint (the upload / retry paths are `knowledgebase` application services exercised through the unit tests — a controller is a follow-up), no new module edge (all changes stay within the already-allowed `knowledgebase` / `ingestion` module boundaries). No ADR — `S10-19` does not change module ownership, persistence approach or an invariant. Related changelog entry: **Duplicate upload and manual retry of a failed document (S10-19)** in [`CHANGELOG.md`](../../CHANGELOG.md) (Unreleased → Added).
