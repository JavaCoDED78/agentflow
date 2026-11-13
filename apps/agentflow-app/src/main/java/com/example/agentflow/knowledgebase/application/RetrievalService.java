package com.example.agentflow.knowledgebase.application;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRepository;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRow;
import com.example.agentflow.knowledgebase.persistence.KnowledgeBaseRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The tenant/ACL-filtered retrieval service (S10-14 / S10-15), the public search entry point of the
 * {@code knowledgebase} module.
 *
 * <p>It exposes two retrieval halves over the same tenant-scoped {@code document_chunks} data, plus
 * a hybrid that fuses them:
 *
 * <ol>
 *   <li><b>Vector (embedding similarity).</b> {@link #search} is three steps, each with a single
 *       owner: resolve the collection's {@code embedding_model} (invariant 1 — a cross-tenant
 *       collection is indistinguishable from a missing one), embed the query through the neutral
 *       {@code ai} {@link EmbeddingPort} (opt-in, {@code ADR-014}), and retrieve, bounded and
 *       thresholded, through {@link DocumentChunkRepository#findNearest} (the access predicate runs
 *       inside the query, before top-k).
 *   <li><b>Lexical (full-text search).</b> {@link #lexicalSearch} (S10-15) is one step: it queries
 *       the tenant/ACL-scoped FTS index directly for chunks whose text matches a keyword, with the
 *       access predicate inside the query. It needs no embedding provider.
 *   <li><b>Hybrid (S10-16).</b> {@link #hybridSearch} runs both halves and fuses them with a simple
 *       reciprocal-rank {@link RankFusion}, deduplicates by chunk id, caps the result at {@code
 *       max-top-k}, and records the latency and the half result counts — without any document text
 *       (invariants 7/8/9). With no embedding provider it degrades to the lexical half only.
 *   <li><b>No-evidence policy (S10-18).</b> {@link #groundedAnswer} runs the hybrid retrieval and
 *       builds the real {@link CitationDto} citations for what it returned (S10-17), then lets the
 *       {@link GroundingPolicy} decide whether to {@link GroundedAnswer.Grounded report a
 *       grounding} (a non-empty, above-bar retrieval — each citation resolves to a chunk the caller
 *       can see) or {@link GroundedAnswer.InsufficientEvidence report an evidence gap} (an empty or
 *       below-bar retrieval ⇒ the caller answers "no relevant source" and never invents a citation,
 *       invariants 1/7).
 * </ol>
 *
 * The vector half resolves the collection's {@code embedding_model} ({@code
 * knowledge_bases.embedding_model}, S10-05); the query text must be vectorised in exactly the space
 * the stored {@code vector(768)} chunks live in. A knowledge base the tenant does not own is
 * indistinguishable from a missing one (invariant 1), so the lookup is by the {@code (tenant_id,
 * id)} pair and yields an empty {@link SearchResult} instead of an error.
 * <li><b>Embed the query.</b> One call to the neutral {@code ai} {@link EmbeddingPort} with the
 *     collection's model (the provider's own retry/timeout bounds live in the {@code ai} resilience
 *     layer, never here). The port is opt-in ({@code ADR-014}): with no provider the service still
 *     constructs and fails loudly at call time.
 * <li><b>Retrieve, bounded.</b> {@link DocumentChunkRepository#findNearest} runs the
 *     nearest-neighbour search with the tenant <em>and</em> collection predicates inside the query,
 *     before top-k (invariant 1 — a post-filter is not permitted, so a foreign chunk can never
 *     surface, no matter how close its embedding). The caller's {@code topK} is clamped to the
 *     configured {@code max-top-k} (a missing one falls back to {@code default-top-k}), and a
 *     candidate whose cosine distance exceeds the configured {@code threshold} is dropped.
 * </ol>
 *
 * <p>The service is a read: no state changes, no outbox, no Kafka. PostgreSQL is the source of
 * truth for what is retrieved (invariant 2); nothing is cached, so a fresh search always sees the
 * committed index.
 *
 * <p>Nothing here is ever logged beyond counts and identifiers: the query text and the retrieved
 * fragments are untrusted content (invariants 7/8) and stay out of logs, metrics and traces.
 */
public class RetrievalService {

  /** Hybrid-search call counter (invariant 9: no tenant / document label). */
  static final String METRIC_HYBRID = "agentflow.knowledgebase.retrieval.hybrid";

  /** Hybrid-search result-count counter, one increment per raw result both halves produce. */
  static final String METRIC_HALF_RESULTS = "agentflow.knowledgebase.retrieval.half-results";

  /** Hybrid-search latency timer, in seconds. */
  static final String METRIC_DURATION = "agentflow.knowledgebase.retrieval.duration";

  private final DocumentChunkRepository chunkRepository;
  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final EmbeddingPort embeddingPort;
  private final int defaultTopK;
  private final int maxTopK;
  private final double threshold;
  private final MeterRegistry meterRegistry;
  private final Counter hybridCalls;
  private final Counter halfResults;
  private final Timer duration;

  /**
   * The no-evidence policy (S10-18): decide whether the citations the grounding returns are enough
   * to {@link GroundedAnswer.Grounded report a grounding} or to {@link
   * GroundedAnswer.InsufficientEvidence report an evidence gap}. {@code null} means "the default
   * policy" (any non-empty retrieval grounds the answer — {@link GroundingPolicy#DEFAULT}).
   */
  private final GroundingPolicy groundingPolicy;

  /**
   * @param chunkRepository the tenant/ACL-scoped {@code document_chunks} read path, or {@code null}
   *     when no database is configured (S01-02)
   * @param knowledgeBaseRepository the {@code knowledge_bases} read path, or {@code null} under the
   *     same condition
   * @param embeddingPort the neutral embedding seam, or {@code null} when no embedding provider is
   *     configured (the {@code ADR-014} opt-in shape — a call then fails loudly)
   * @param defaultTopK the result size when the caller names no {@code topK} (at least 1)
   * @param maxTopK the hard ceiling a caller {@code topK} is clamped to (at least {@code
   *     defaultTopK})
   * @param threshold the maximum cosine distance a candidate may have to stay in the result,
   *     bounded to {@code [0.0, 2.0]}
   * @param meterRegistry the Micrometer registry the hybrid meters are registered on; {@code null}
   *     is treated as "no shared registry" and the meters are registered on a throwaway one (no
   *     hybrid observability — the metering is opt-in)
   * @param groundingPolicy the no-evidence policy (S10-18) to report for an empty/below-bar
   *     retrieval; {@code null} means the default policy (any non-empty retrieval grounds the
   *     answer)
   */
  public RetrievalService(
      DocumentChunkRepository chunkRepository,
      KnowledgeBaseRepository knowledgeBaseRepository,
      EmbeddingPort embeddingPort,
      int defaultTopK,
      int maxTopK,
      double threshold,
      MeterRegistry meterRegistry,
      GroundingPolicy groundingPolicy) {
    this.chunkRepository = chunkRepository;
    this.knowledgeBaseRepository = knowledgeBaseRepository;
    this.embeddingPort = embeddingPort;
    this.groundingPolicy = groundingPolicy == null ? GroundingPolicy.DEFAULT : groundingPolicy;
    if (defaultTopK < 1) {
      throw new IllegalArgumentException("defaultTopK must be at least 1, was " + defaultTopK);
    }
    if (maxTopK < defaultTopK) {
      throw new IllegalArgumentException(
          "maxTopK must be at least defaultTopK (" + defaultTopK + "), was " + maxTopK);
    }
    if (threshold < 0.0 || threshold > 2.0 || Double.isNaN(threshold)) {
      throw new IllegalArgumentException(
          "threshold must be a cosine distance in [0.0, 2.0], was " + threshold);
    }
    this.defaultTopK = defaultTopK;
    this.maxTopK = maxTopK;
    this.threshold = threshold;
    this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
    this.hybridCalls =
        Counter.builder(METRIC_HYBRID)
            .description("Hybrid retrievals performed (S10-16); one increment per hybrid search")
            .register(this.meterRegistry);
    this.halfResults =
        Counter.builder(METRIC_HALF_RESULTS)
            .description(
                "Raw results produced by the two hybrid retrieval halves (S10-16), summed; "
                    + "carries no document text or tenant")
            .register(this.meterRegistry);
    this.duration =
        Timer.builder(METRIC_DURATION)
            .description("Hybrid retrieval latency, seconds")
            .register(this.meterRegistry);
  }

  /**
   * Search one tenant-owned knowledge base for the chunks closest to {@code request}.
   *
   * <p>Tenant and knowledge base come from the verified security context and the request path —
   * never from the caller's body (invariant 1) — and both predicates run inside the retrieval
   * query, before top-k: the result can never contain another tenant's or another collection's
   * chunk, even when its embedding is maximally close to the query vector (the S10-14 acceptance).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param knowledgeBaseId the knowledge base to search
   * @param request the query text and the optional {@code topK}
   * @return the closest chunks within the relevance threshold, in ascending distance order; empty
   *     when the collection is missing (including a cross-tenant id), has no indexed chunks, or
   *     nothing is close enough
   * @throws IllegalArgumentException if an argument is {@code null}
   * @throws IllegalStateException when no database connection or no embedding provider is
   *     configured (S01-02 / {@code ADR-014})
   */
  public SearchResult search(UUID tenantId, UUID knowledgeBaseId, SearchRequest request) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must not be null");
    }
    if (knowledgeBaseId == null) {
      throw new IllegalArgumentException("knowledgeBaseId must not be null");
    }
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (chunkRepository == null || knowledgeBaseRepository == null) {
      throw new IllegalStateException(
          "retrieval is unavailable: no database connection is configured (S01-02)");
    }
    if (embeddingPort == null) {
      throw new IllegalStateException(
          "retrieval is unavailable: no EmbeddingPort provider is configured (ADR-014)");
    }
    Optional<String> embeddingModel =
        knowledgeBaseRepository.findEmbeddingModelByIdAndTenant(tenantId, knowledgeBaseId);
    if (embeddingModel.isEmpty()) {
      // Another tenant's collection is indistinguishable from a missing one (invariant 1):
      // retrieval simply finds nothing.
      return SearchResult.empty();
    }
    EmbeddingResponse response =
        embeddingPort.embed(new EmbeddingRequest(embeddingModel.get(), List.of(request.query())));
    float[] queryVector = response.embeddings().get(0);
    int topK = request.topK() == null ? defaultTopK : Math.min(request.topK(), maxTopK);
    List<DocumentChunkRow> rows =
        chunkRepository.findNearest(tenantId, knowledgeBaseId, queryVector, topK);
    List<ChunkDto> chunks =
        rows.stream()
            .filter(row -> row.distance() <= threshold)
            .map(
                row ->
                    new ChunkDto(
                        row.id(),
                        row.documentId(),
                        row.documentName(),
                        row.pageFrom(),
                        row.pageTo(),
                        row.distance(),
                        row.content()))
            .toList();
    return new SearchResult(chunks);
  }

  /**
   * Search one tenant-owned knowledge base for the chunks whose {@code content} matches {@code
   * keyword} — the lexical (full-text) half of the hybrid retrieval (S10-15).
   *
   * <p>This is the complement of {@link #search}: {@code search} matches by embedding similarity,
   * this matches chunks by their exact text. Tenant and knowledge base come from the verified
   * security context and the request path — never from the caller's body (invariant 1) — and both
   * predicates run inside {@link DocumentChunkRepository#findByKeyword}, before the relevance
   * ordering and the limit, so the result can never contain another tenant's or another
   * collection's chunk. Unlike {@link #search}, this half needs no embedding provider: it reads the
   * tenant-scoped lexical index directly, so it also works when no provider is configured (the
   * {@code ADR-014} opt-in shape).
   *
   * <p>The candidates carry the FTS relevance as their {@code ChunkDto#score()} (the lexical half's
   * score; the vector half applies its cosine-distance threshold in {@link #search}, and the fusion
   * that merges and deduplicates the two halves is {@code S10-16}). The result is capped at the
   * same {@code topK} bounds as {@link #search}.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param knowledgeBaseId the knowledge base to search
   * @param keyword the keyword / term to find in the chunk text (tokenised as natural language)
   * @param topK the maximum number of chunks, or {@code null} for the configured default
   * @return the matching chunks by descending relevance; empty when the collection is missing
   *     (including a cross-tenant id) or nothing matches
   * @throws IllegalArgumentException if an argument is {@code null}
   * @throws IllegalStateException when no database connection is configured (S01-02)
   */
  public SearchResult lexicalSearch(
      UUID tenantId, UUID knowledgeBaseId, String keyword, Integer topK) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must not be null");
    }
    if (knowledgeBaseId == null) {
      throw new IllegalArgumentException("knowledgeBaseId must not be null");
    }
    if (keyword == null) {
      throw new IllegalArgumentException("keyword must not be null");
    }
    if (chunkRepository == null) {
      throw new IllegalStateException(
          "retrieval is unavailable: no database connection is configured (S01-02)");
    }
    int limit = topK == null ? defaultTopK : Math.min(topK, maxTopK);
    List<DocumentChunkRow> rows =
        chunkRepository.findByKeyword(tenantId, knowledgeBaseId, keyword, limit);
    List<ChunkDto> chunks =
        rows.stream()
            .map(
                row ->
                    new ChunkDto(
                        row.id(),
                        row.documentId(),
                        row.documentName(),
                        row.pageFrom(),
                        row.pageTo(),
                        row.rank(),
                        row.content()))
            .toList();
    return new SearchResult(chunks);
  }

  /**
   * Search one tenant-owned knowledge base for the chunks closest to {@code request} by
   * <em>both</em> embedding similarity and lexical match, fusing the two independent halves into
   * one result (S10-16).
   *
   * <p>This is the hybrid retrieval the glossary §7 "Retrieval" names (the vector + FTS {@code
   * hybrid}). It runs the vector half ({@link #search}) and the lexical half ({@link
   * #lexicalSearch}) independently over the same tenant-scoped index and fuses them:
   *
   * <ol>
   *   <li><b>Fusion.</b> Each half contributes its ordered chunk ids; {@link RankFusion} scores
   *       every chunk by the reciprocal of its rank in each half it appears in (reciprocal-rank
   *       fusion), so a chunk matched by <em>both</em> halves outranks one matched by one, and the
   *       two different score scales (cosine distance vs. {@code ts_rank}) are never compared.
   *   <li><b>Deduplication.</b> A chunk present in both halves is emitted once, keyed by chunk id.
   *   <li><b>Limit.</b> The fused result is capped at {@code max-top-k}, so one request can never
   *       pull more than that many fragments out of the index.
   * </ol>
   *
   * <p>Tenant and knowledge base come from the verified security context and the request path —
   * never from the caller's body (invariant 1) — and every candidate is already scoped to one
   * tenant and one collection by the two halves, so the fused result is tenant- and
   * collection-safe. The vector half only runs when an {@link EmbeddingPort} is configured; with no
   * provider the hybrid degrades to the lexical half (still deduplicated and capped), so hybrid
   * search is never entirely unavailable just because the embedding provider is off.
   *
   * <p>Observability (invariants 8/9): on a successful fusion the service records, on meters with
   * <b>no tenant / document / prompt label</b>, the call count, the raw result count both halves
   * produced and the elapsed latency (seconds). No document text, chunk text or identifier is ever
   * passed to the meters.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param knowledgeBaseId the knowledge base to search
   * @param request the query text (used as the vector query and the lexical keyword) and the
   *     optional {@code topK}
   * @return the fused, deduplicated, capped chunks ordered by fused relevance (higher score first);
   *     empty when the collection is missing (including a cross-tenant id) or nothing matches
   * @throws IllegalArgumentException if an argument is {@code null}
   * @throws IllegalStateException when no database connection is configured (S01-02)
   */
  public SearchResult hybridSearch(UUID tenantId, UUID knowledgeBaseId, SearchRequest request) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must not be null");
    }
    if (knowledgeBaseId == null) {
      throw new IllegalArgumentException("knowledgeBaseId must not be null");
    }
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (chunkRepository == null || knowledgeBaseRepository == null) {
      throw new IllegalStateException(
          "retrieval is unavailable: no database connection is configured (S01-02)");
    }
    long startedNanos = System.nanoTime();
    SearchResult vectorResult =
        embeddingPort != null ? search(tenantId, knowledgeBaseId, request) : SearchResult.empty();
    // Lexical half: always. It needs no embedding provider.
    SearchResult lexicalResult =
        lexicalSearch(tenantId, knowledgeBaseId, request.query(), request.topK());
    int vectorCount = vectorResult.chunks().size();
    int lexicalCount = lexicalResult.chunks().size();
    SearchResult result = fuse(vectorResult, lexicalResult);
    recordMetrics(vectorCount, lexicalCount, startedNanos);
    return result;
  }

  /**
   * Build the citations the retrieved evidence supports (S10-17): one citation per fused chunk, the
   * knowledgebase layer's citation step over {@link #hybridSearch}.
   *
   * <p>It runs the hybrid retrieval and builds a citation for every chunk it returned, so each
   * citation resolves to a <em>real retrieved chunk</em> — never to another tenant's document,
   * because the chunks the hybrid returns are already tenant- and collection-scoped (invariant 1,
   * the two halves' predicates run inside the SQL). An empty retrieval (a missing or cross-tenant
   * collection, or a non-match) yields an empty list; reporting {@code InsufficientEvidence} for
   * that empty list is the no-evidence policy (S10-18), not this layer.
   *
   * <p>Observability (invariants 7/8/9): the hybrid call records its own no-label meters; this step
   * adds nothing to the meters and never logs the retrieved text.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param knowledgeBaseId the knowledge base to search
   * @param request the query text (used as the vector query and the lexical keyword) and the
   *     optional {@code topK}
   * @return one citation per fused chunk, in fused order; empty when the collection is missing
   *     (including a cross-tenant id) or nothing matches — never an invented citation
   * @throws IllegalArgumentException if an argument is {@code null}
   * @throws IllegalStateException when no database connection is configured (S01-02)
   */
  public List<CitationDto> citations(UUID tenantId, UUID knowledgeBaseId, SearchRequest request) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must not be null");
    }
    if (knowledgeBaseId == null) {
      throw new IllegalArgumentException("knowledgeBaseId must not be null");
    }
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (chunkRepository == null || knowledgeBaseRepository == null) {
      throw new IllegalStateException(
          "retrieval is unavailable: no database connection is configured (S01-02)");
    }
    return CitationBuilder.cite(hybridSearch(tenantId, knowledgeBaseId, request));
  }

  /**
   * Build the no-evidence decision for an RAG call (S10-18): run the hybrid retrieval, build the
   * real {@link CitationDto} citations for what it returned (S10-17), and let the {@link
   * GroundingPolicy} decide whether to {@link GroundedAnswer.Grounded report a grounding} or {@link
   * GroundedAnswer.InsufficientEvidence report an evidence gap}.
   *
   * <p>It is the {@code knowledgebase} entry point a caller feeds to the model: a non-empty
   * retrieval (at least {@code minimum-citations} fused chunks) yields {@code Grounded(citations)}
   * — one {@link CitationDto} per chunk, each resolving to a fragment the caller can see (invariant
   * 1, the two halves' predicates run inside the SQL). An empty retrieval, or one below the policy
   * bar, yields {@code InsufficientEvidence(searchId)} — the caller answers "I have no relevant
   * source" and never invents a citation (invariant 1 / 7).
   *
   * <p>It is the reporting half of the RAG loop on top of {@link #citations}: the retrieval decides
   * whether the list is empty, this policy decides what to report for it. A {@code null} argument
   * is a contract failure, and with no database configured it fails loudly at call time (S01-02),
   * exactly like {@link #citations}.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param knowledgeBaseId the knowledge base to search
   * @param request the query text (used as the vector query and the lexical keyword) and the
   *     optional {@code topK}
   * @param searchId the id of the RAG call this grounding belongs to (correlation only)
   * @return {@code Grounded(citations)} when the policy found evidence, otherwise {@code
   *     InsufficientEvidence(searchId)} — never a fabricated citation for an empty result
   * @throws IllegalArgumentException if an argument is {@code null}
   * @throws IllegalStateException when no database connection is configured (S01-02)
   */
  public GroundedAnswer groundedAnswer(
      UUID tenantId, UUID knowledgeBaseId, SearchRequest request, UUID searchId) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must not be null");
    }
    if (knowledgeBaseId == null) {
      throw new IllegalArgumentException("knowledgeBaseId must not be null");
    }
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (searchId == null) {
      throw new IllegalArgumentException("searchId must not be null");
    }
    if (chunkRepository == null || knowledgeBaseRepository == null) {
      throw new IllegalStateException(
          "retrieval is unavailable: no database connection is configured (S01-02)");
    }
    GroundingPolicy policy = groundingPolicy != null ? groundingPolicy : GroundingPolicy.DEFAULT;
    return policy.decide(citations(tenantId, knowledgeBaseId, request), searchId);
  }

  /**
   * Fuse the two halves and cap the result. Deduplicates by chunk id (a chunk in both halves is
   * emitted once), orders by the reciprocal-rank fused score (see {@link RankFusion}) and caps at
   * {@code max-top-k}.
   *
   * <p>Each fused {@link ChunkDto} carries the fused relevance as its {@code score} (higher is
   * better); its content / pages / {@code documentName} / document come from whichever half
   * produced it (they are identical for the same chunk id). The {@code documentName} is the parent
   * document's safe display name, so a future citation built from a fused element already has the
   * document label the glossary §8 fixes (S10-17).
   */
  private SearchResult fuse(SearchResult vector, SearchResult lexical) {
    List<ChunkDto> vectorChunks = vector.chunks();
    List<ChunkDto> lexicalChunks = lexical.chunks();

    Map<UUID, ChunkDto> byId = new HashMap<>(vectorChunks.size() + lexicalChunks.size());
    byId.putAll(vectorChunks.stream().collect(Collectors.toMap(ChunkDto::id, c -> c)));
    byId.putAll(lexicalChunks.stream().collect(Collectors.toMap(ChunkDto::id, c -> c)));

    List<List<UUID>> rankedIds =
        List.of(
            vectorChunks.stream().map(ChunkDto::id).collect(Collectors.toList()),
            lexicalChunks.stream().map(ChunkDto::id).collect(Collectors.toList()));

    List<ChunkDto> fused = new ArrayList<>();
    for (RankFusion.FusedCandidate candidate : RankFusion.fuse(rankedIds)) {
      if (fused.size() >= maxTopK) {
        break;
      }
      ChunkDto base = byId.get(candidate.chunkId());
      if (base != null) {
        fused.add(
            new ChunkDto(
                base.id(),
                base.documentId(),
                base.documentName(),
                base.pageFrom(),
                base.pageTo(),
                candidate.score(),
                base.content()));
      }
    }
    return new SearchResult(fused);
  }

  /**
   * Record the hybrid retrieval latency and the half result counts on the no-label meters
   * (invariant 9).
   */
  private void recordMetrics(int vectorCount, int lexicalCount, long startedNanos) {
    duration.record(java.time.Duration.ofNanos(System.nanoTime() - startedNanos));
    hybridCalls.increment();
    halfResults.increment(vectorCount + lexicalCount);
  }

  public int defaultTopK() {
    return defaultTopK;
  }

  /**
   * @return the hard ceiling a caller {@code topK} is clamped to
   */
  public int maxTopK() {
    return maxTopK;
  }

  /**
   * @return the maximum cosine distance a candidate may have to stay in the result
   */
  public double threshold() {
    return threshold;
  }
}
