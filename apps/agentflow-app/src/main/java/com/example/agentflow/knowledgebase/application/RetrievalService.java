package com.example.agentflow.knowledgebase.application;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRepository;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRow;
import com.example.agentflow.knowledgebase.persistence.KnowledgeBaseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant/ACL-filtered vector retrieval service (S10-14), the public search entry point of the
 * {@code knowledgebase} module.
 *
 * <p>One {@link #search} call is three steps, each with a single owner:
 *
 * <ol>
 *   <li><b>Resolve the embedding model.</b> The collection pins its {@code embedding_model} ({@code
 *       knowledge_bases.embedding_model}, S10-05); the query text must be vectorised in exactly the
 *       space the stored {@code vector(768)} chunks live in. A knowledge base the tenant does not
 *       own is indistinguishable from a missing one (invariant 1), so the lookup is by the {@code
 *       (tenant_id, id)} pair and yields an empty {@link SearchResult} instead of an error.
 *   <li><b>Embed the query.</b> One call to the neutral {@code ai} {@link EmbeddingPort} with the
 *       collection's model (the provider's own retry/timeout bounds live in the {@code ai}
 *       resilience layer, never here). The port is opt-in ({@code ADR-014}): with no provider the
 *       service still constructs and fails loudly at call time.
 *   <li><b>Retrieve, bounded.</b> {@link DocumentChunkRepository#findNearest} runs the
 *       nearest-neighbour search with the tenant <em>and</em> collection predicates inside the
 *       query, before top-k (invariant 1 — a post-filter is not permitted, so a foreign chunk can
 *       never surface, no matter how close its embedding). The caller's {@code topK} is clamped to
 *       the configured {@code max-top-k} (a missing one falls back to {@code default-top-k}), and a
 *       candidate whose cosine distance exceeds the configured {@code threshold} is dropped.
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

  private final DocumentChunkRepository chunkRepository;
  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final EmbeddingPort embeddingPort;
  private final int defaultTopK;
  private final int maxTopK;
  private final double threshold;

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
   */
  public RetrievalService(
      DocumentChunkRepository chunkRepository,
      KnowledgeBaseRepository knowledgeBaseRepository,
      EmbeddingPort embeddingPort,
      int defaultTopK,
      int maxTopK,
      double threshold) {
    this.chunkRepository = chunkRepository;
    this.knowledgeBaseRepository = knowledgeBaseRepository;
    this.embeddingPort = embeddingPort;
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
                        row.pageFrom(),
                        row.pageTo(),
                        row.distance(),
                        row.content()))
            .toList();
    return new SearchResult(chunks);
  }

  /**
   * @return the result size a search falls back to when the caller names no {@code topK}
   */
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
