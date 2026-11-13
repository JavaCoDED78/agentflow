package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRepository;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRow;
import com.example.agentflow.knowledgebase.persistence.KnowledgeBaseRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the {@code S10-14} vector retrieval service, {@link RetrievalService}.
 *
 * <p>DB-free: {@link DocumentChunkRepository}, {@link KnowledgeBaseRepository} and the {@link
 * EmbeddingPort} seam are mocks/stubs, so no {@code DataSource} or provider is needed. The tests
 * pin the {@code S10-14} facts that hold before any REST endpoint exists:
 *
 * <ul>
 *   <li>the query is embedded with the collection's own {@code embedding_model} and the
 *       nearest-neighbour call carries the exact {@code (tenantId, knowledgeBaseId)} pair — the
 *       access predicate the repository enforces inside the query (invariant 1);
 *   <li>a caller {@code topK} falls back to the configured default, is honored below the maximum
 *       and is clamped above it;
 *   <li>candidates beyond the configured cosine-distance threshold are dropped, boundary values
 *       kept, and the chunks keep the repository's ascending-distance order with their scores;
 *   <li>the hybrid search ({@code S10-16}) fuses the vector and lexical halves, deduplicates by
 *       chunk id and caps the fused result at the configured maximum;
 *   <li>a missing — including cross-tenant — knowledge base yields an empty result without ever
 *       calling the embedding port or the chunk repository; and
 *   <li>with no database or no embedding provider the service fails loudly at call time (S01-02 /
 *       {@code ADR-014}).
 *   <li>the hybrid search records its latency and half result counts on meters that carry no tenant
 *       or document label (invariant 9).
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

  private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555566667777");
  private static final UUID KNOWLEDGE_BASE =
      UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final String MODEL = "nomic-embed-text";

  @Mock private DocumentChunkRepository chunkRepository;
  @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock private EmbeddingPort embeddingPort;

  private RetrievalService service;

  @BeforeEach
  void setUp() {
    service =
        new RetrievalService(
            chunkRepository, knowledgeBaseRepository, embeddingPort, 10, 50, 0.5, null);
  }

  /**
   * Stub the owned-collection lookup most tests rely on (kept out of {@code @BeforeEach} so the
   * strict-stubbed tests that never reach it stay clean).
   */
  private void stubOwnedKnowledgeBase() {
    when(knowledgeBaseRepository.findEmbeddingModelByIdAndTenant(TENANT, KNOWLEDGE_BASE))
        .thenReturn(Optional.of(MODEL));
  }

  private static float[] vector768() {
    float[] v = new float[768];
    v[0] = 1f;
    return v;
  }

  private static DocumentChunkRow row(
      UUID documentId, String content, int chunkIndex, double distance) {
    return new DocumentChunkRow(
        UUID.randomUUID(),
        TENANT,
        documentId,
        "v1",
        chunkIndex,
        1,
        2,
        content,
        "hash-" + chunkIndex,
        "tester",
        OffsetDateTime.now(),
        distance,
        0.0);
  }

  /** The happy path: the collection's model embeds the query; rows map to scored, ordered DTOs. */
  @Test
  void search_returns_the_closest_chunks_with_their_scores_in_distance_order() {
    stubOwnedKnowledgeBase();
    UUID document = UUID.randomUUID();
    when(embeddingPort.embed(any()))
        .thenAnswer(
            invocation -> {
              EmbeddingRequest request = invocation.getArgument(0);
              return new EmbeddingResponse(request.model(), List.of(vector768()), null);
            });
    when(chunkRepository.findNearest(eq(TENANT), eq(KNOWLEDGE_BASE), any(float[].class), eq(10)))
        .thenReturn(
            List.of(
                row(document, "closer fragment", 0, 0.1),
                row(document, "further fragment", 1, 0.4)));

    SearchResult result =
        service.search(TENANT, KNOWLEDGE_BASE, new SearchRequest("how do I reset", null));

    // The query was embedded under the collection's own model.
    ArgumentCaptor<EmbeddingRequest> captor = ArgumentCaptor.forClass(EmbeddingRequest.class);
    verify(embeddingPort).embed(captor.capture());
    assertThat(captor.getValue().model()).isEqualTo(MODEL);
    assertThat(captor.getValue().texts()).containsExactly("how do I reset");

    assertThat(result.chunks()).hasSize(2);
    assertThat(result.chunks().get(0).content()).isEqualTo("closer fragment");
    assertThat(result.chunks().get(0).score()).isEqualTo(0.1);
    assertThat(result.chunks().get(0).documentId()).isEqualTo(document);
    assertThat(result.chunks().get(0).pageFrom()).isEqualTo(1);
    assertThat(result.chunks().get(0).pageTo()).isEqualTo(2);
    assertThat(result.chunks().get(1).content()).isEqualTo("further fragment");
    assertThat(result.chunks().get(1).score()).isEqualTo(0.4);
  }

  /** A missing topK falls back to the configured default result size. */
  @Test
  void search_uses_the_default_top_k_when_the_request_names_none() {
    stubOwnedKnowledgeBase();
    when(embeddingPort.embed(any()))
        .thenReturn(new EmbeddingResponse(MODEL, List.of(vector768()), null));
    when(chunkRepository.findNearest(eq(TENANT), eq(KNOWLEDGE_BASE), any(float[].class), eq(10)))
        .thenReturn(List.of());

    service.search(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null));

    verify(chunkRepository).findNearest(eq(TENANT), eq(KNOWLEDGE_BASE), any(float[].class), eq(10));
  }

  /** A caller topK above the configured maximum is clamped, not rejected. */
  @Test
  void search_clamps_a_requested_top_k_to_the_configured_maximum() {
    stubOwnedKnowledgeBase();
    when(embeddingPort.embed(any()))
        .thenReturn(new EmbeddingResponse(MODEL, List.of(vector768()), null));
    when(chunkRepository.findNearest(eq(TENANT), eq(KNOWLEDGE_BASE), any(float[].class), eq(50)))
        .thenReturn(List.of());

    service.search(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", 100));

    verify(chunkRepository).findNearest(eq(TENANT), eq(KNOWLEDGE_BASE), any(float[].class), eq(50));
  }

  /** A caller topK below the maximum is honored exactly. */
  @Test
  void search_honors_a_requested_top_k_below_the_maximum() {
    stubOwnedKnowledgeBase();
    when(embeddingPort.embed(any()))
        .thenReturn(new EmbeddingResponse(MODEL, List.of(vector768()), null));
    when(chunkRepository.findNearest(eq(TENANT), eq(KNOWLEDGE_BASE), any(float[].class), eq(3)))
        .thenReturn(List.of());

    service.search(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", 3));

    verify(chunkRepository).findNearest(eq(TENANT), eq(KNOWLEDGE_BASE), any(float[].class), eq(3));
  }

  /** The relevance threshold drops distant candidates; a candidate exactly at it stays. */
  @Test
  void search_drops_candidates_beyond_the_relevance_threshold() {
    stubOwnedKnowledgeBase();
    UUID document = UUID.randomUUID();
    when(embeddingPort.embed(any()))
        .thenReturn(new EmbeddingResponse(MODEL, List.of(vector768()), null));
    when(chunkRepository.findNearest(eq(TENANT), eq(KNOWLEDGE_BASE), any(float[].class), eq(10)))
        .thenReturn(
            List.of(
                row(document, "at threshold", 0, 0.5), row(document, "beyond threshold", 1, 0.9)));

    SearchResult result = service.search(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null));

    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().get(0).content()).isEqualTo("at threshold");
    assertThat(result.chunks().get(0).score()).isEqualTo(0.5);
  }

  /** An empty candidate set is a legitimate "nothing relevant" answer. */
  @Test
  void search_returns_an_empty_result_when_nothing_is_close_enough() {
    stubOwnedKnowledgeBase();
    when(embeddingPort.embed(any()))
        .thenReturn(new EmbeddingResponse(MODEL, List.of(vector768()), null));
    when(chunkRepository.findNearest(eq(TENANT), eq(KNOWLEDGE_BASE), any(float[].class), eq(10)))
        .thenReturn(List.of());

    SearchResult result = service.search(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null));

    assertThat(result.chunks()).isEmpty();
  }

  /**
   * Invariant 1: a knowledge base the tenant does not own (or that does not exist) is
   * indistinguishable from a missing one — the search finds nothing and never reaches the provider
   * or the index.
   */
  @Test
  void search_returns_empty_when_the_collection_is_missing_or_cross_tenant() {
    when(knowledgeBaseRepository.findEmbeddingModelByIdAndTenant(TENANT, KNOWLEDGE_BASE))
        .thenReturn(Optional.empty());

    SearchResult result = service.search(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null));

    assertThat(result.chunks()).isEmpty();
    verifyNoInteractions(embeddingPort, chunkRepository);
  }

  /** S01-02: no database — the service exists and fails loudly at call time. */
  @Test
  void search_fails_loudly_without_a_database_connection() {
    RetrievalService noDatabase =
        new RetrievalService(null, null, embeddingPort, 10, 50, 0.5, null);

    assertThatThrownBy(
            () -> noDatabase.search(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no database connection");
  }

  // --- lexical (full-text) half (S10-15) -----------------------------------------------------

  /**
   * The lexical half maps directly to the tenant-scoped repository method with no embedding step.
   */
  @Test
  void lexical_search_queries_the_tenant_scoped_repository() {
    service.lexicalSearch(TENANT, KNOWLEDGE_BASE, "retention", null);

    verify(chunkRepository).findByKeyword(TENANT, KNOWLEDGE_BASE, "retention", 10);
    verify(embeddingPort, never()).embed(any());
  }

  /** The lexical half honours the same top-k bounds as the vector half (default, then clamped). */
  @Test
  void lexical_search_respects_the_topk_bound() {
    service.lexicalSearch(TENANT, KNOWLEDGE_BASE, "retention", 3);
    verify(chunkRepository).findByKeyword(TENANT, KNOWLEDGE_BASE, "retention", 3);

    service.lexicalSearch(TENANT, KNOWLEDGE_BASE, "retention", 999);
    verify(chunkRepository).findByKeyword(TENANT, KNOWLEDGE_BASE, "retention", 50);
  }

  /** The FTS relevance is surfaced as the candidate score, not the cosine distance. */
  @Test
  void lexical_search_maps_frs_relevance_to_the_candidate_score() {
    DocumentChunkRow row =
        new DocumentChunkRow(
            UUID.randomUUID(),
            TENANT,
            UUID.randomUUID(),
            "v1",
            0,
            1,
            2,
            "fragment",
            "hash-0",
            "tester",
            OffsetDateTime.now(),
            0.0,
            0.7);
    when(chunkRepository.findByKeyword(any(), any(), any(), anyInt())).thenReturn(List.of(row));

    SearchResult result = service.lexicalSearch(TENANT, KNOWLEDGE_BASE, "retention", null);

    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().get(0).score()).isEqualTo(0.7);
  }

  /** S01-02: no database connection (lexical half never needs an embedding provider). */
  @Test
  void lexical_search_fails_loudly_without_a_database_connection() {
    RetrievalService noDatabase =
        new RetrievalService(null, knowledgeBaseRepository, embeddingPort, 10, 50, 0.5, null);

    assertThatThrownBy(() -> noDatabase.lexicalSearch(TENANT, KNOWLEDGE_BASE, "retention", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no database connection");
  }

  /** ADR-014: no embedding provider — the service exists and fails loudly at call time. */
  @Test
  void search_fails_loudly_without_an_embedding_provider() {
    RetrievalService noProvider =
        new RetrievalService(chunkRepository, knowledgeBaseRepository, null, 10, 50, 0.5, null);

    assertThatThrownBy(
            () -> noProvider.search(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no EmbeddingPort provider");
  }

  /** Entry-point argument guards are contract failures (IllegalArgumentException). */
  @Test
  void search_rejects_null_arguments() {
    assertThatThrownBy(() -> service.search(null, KNOWLEDGE_BASE, new SearchRequest("q", null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.search(TENANT, null, new SearchRequest("q", null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.search(TENANT, KNOWLEDGE_BASE, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** The service-level relationship of the configured top-k bounds is validated at construction. */
  @Test
  void the_constructor_rejects_a_max_top_k_below_the_default() {
    assertThatThrownBy(
            () ->
                new RetrievalService(
                    chunkRepository, knowledgeBaseRepository, embeddingPort, 10, 9, 0.5, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxTopK");
  }

  /** The threshold is a cosine distance and must stay inside [0.0, 2.0]. */
  @Test
  void the_constructor_rejects_an_out_of_range_threshold() {
    assertThatThrownBy(
            () ->
                new RetrievalService(
                    chunkRepository, knowledgeBaseRepository, embeddingPort, 10, 50, 2.5, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("threshold");
    assertThatThrownBy(
            () ->
                new RetrievalService(
                    chunkRepository, knowledgeBaseRepository, embeddingPort, 10, 50, -0.1, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("threshold");
  }

  // --- hybrid (rank-fusion) half (S10-16) ---------------------------------------------------

  private static DocumentChunkRow vectorChunkRow(UUID chunkId, String content, double distance) {
    return new DocumentChunkRow(
        chunkId,
        TENANT,
        UUID.randomUUID(),
        "v1",
        0,
        1,
        2,
        content,
        "hash",
        "tester",
        OffsetDateTime.now(),
        distance,
        0.0);
  }

  private static DocumentChunkRow lexicalChunkRow(
      UUID chunkId, String content, double frsRelevance) {
    return new DocumentChunkRow(
        chunkId,
        TENANT,
        UUID.randomUUID(),
        "v1",
        0,
        1,
        2,
        content,
        "hash",
        "tester",
        OffsetDateTime.now(),
        0.0,
        frsRelevance);
  }

  /**
   * The two halves are fused and deduplicated by chunk id: a chunk present in both halves is
   * emitted once and outranks one matched by only one half (reciprocal-rank fusion).
   */
  @Test
  void hybrid_search_fuses_and_deduplicates_the_two_halves() {
    stubOwnedKnowledgeBase();
    when(embeddingPort.embed(any()))
        .thenReturn(new EmbeddingResponse(MODEL, List.of(vector768()), null));

    UUID overlap = UUID.randomUUID();
    UUID vectorOnly = UUID.randomUUID();
    UUID lexicalOnly = UUID.randomUUID();

    // Vector half ranked by distance: overlap first, then a vector-only chunk.
    when(chunkRepository.findNearest(eq(TENANT), eq(KNOWLEDGE_BASE), any(float[].class), eq(10)))
        .thenReturn(
            List.of(
                vectorChunkRow(overlap, "overlap", 0.1),
                vectorChunkRow(vectorOnly, "vector only", 0.2)));
    // Lexical half ranked by FTS relevance: overlap first, then a lexical-only chunk.
    when(chunkRepository.findByKeyword(eq(TENANT), eq(KNOWLEDGE_BASE), any(String.class), eq(10)))
        .thenReturn(
            List.of(
                lexicalChunkRow(overlap, "overlap", 0.9),
                lexicalChunkRow(lexicalOnly, "lexical only", 0.3)));

    SearchResult result =
        service.hybridSearch(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null));

    // Three distinct chunks after dedup; the overlap chunk is first (matched by both halves).
    assertThat(result.chunks()).hasSize(3);
    assertThat(result.chunks().stream().map(ChunkDto::id).toList()).doesNotHaveDuplicates();
    assertThat(result.chunks().get(0).id()).isEqualTo(overlap);
    assertThat(result.chunks().stream().filter(c -> c.id().equals(overlap)).count()).isEqualTo(1);
  }

  /**
   * The fused result is capped at the configured maximum, so one request can never pull more than
   * that many fragments from the two halves combined.
   */
  @Test
  void hybrid_search_caps_the_fused_result_at_the_configured_maximum() {
    stubOwnedKnowledgeBase();
    when(embeddingPort.embed(any()))
        .thenReturn(new EmbeddingResponse(MODEL, List.of(vector768()), null));

    List<DocumentChunkRow> manyVector = new ArrayList<>();
    List<DocumentChunkRow> manyLexical = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      manyVector.add(vectorChunkRow(UUID.randomUUID(), "v" + i, 0.1));
      manyLexical.add(lexicalChunkRow(UUID.randomUUID(), "l" + i, 0.1));
    }
    when(chunkRepository.findNearest(eq(TENANT), eq(KNOWLEDGE_BASE), any(float[].class), eq(50)))
        .thenReturn(manyVector);
    when(chunkRepository.findByKeyword(eq(TENANT), eq(KNOWLEDGE_BASE), any(String.class), anyInt()))
        .thenReturn(manyLexical);

    SearchResult result = service.hybridSearch(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", 50));

    assertThat(result.chunks()).hasSize(50);
  }

  /**
   * Invariant 1: a cross-tenant / missing collection yields an empty result even for the hybrid —
   * the vector half finds no model and the lexical half is tenant/ACL scoped.
   */
  @Test
  void hybrid_search_returns_empty_for_a_cross_tenant_or_missing_collection() {
    when(knowledgeBaseRepository.findEmbeddingModelByIdAndTenant(TENANT, KNOWLEDGE_BASE))
        .thenReturn(Optional.empty());
    when(chunkRepository.findByKeyword(eq(TENANT), eq(KNOWLEDGE_BASE), any(String.class), anyInt()))
        .thenReturn(List.of());

    SearchResult result =
        service.hybridSearch(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null));

    assertThat(result.chunks()).isEmpty();
  }

  /**
   * With no embedding provider the hybrid degrades to the lexical half only: still deduplicated and
   * capped, and the embedding port is never touched.
   */
  @Test
  void hybrid_search_degrades_to_the_lexical_half_without_an_embedding_provider() {
    RetrievalService noProvider =
        new RetrievalService(chunkRepository, knowledgeBaseRepository, null, 10, 50, 0.5, null);

    DocumentChunkRow a = lexicalChunkRow(UUID.randomUUID(), "lexical hit", 0.7);
    DocumentChunkRow b = lexicalChunkRow(UUID.randomUUID(), "lexical two", 0.4);
    when(chunkRepository.findByKeyword(any(), any(), any(), anyInt())).thenReturn(List.of(a, b));

    SearchResult result =
        noProvider.hybridSearch(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null));

    assertThat(result.chunks()).hasSize(2);
    verify(embeddingPort, never()).embed(any());
  }

  /** S01-02: no database connection — the hybrid fails loudly at call time, before either half. */
  @Test
  void hybrid_search_fails_loudly_without_a_database_connection() {
    RetrievalService noDatabase =
        new RetrievalService(null, null, embeddingPort, 10, 50, 0.5, null);

    assertThatThrownBy(
            () -> noDatabase.hybridSearch(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no database connection");
  }

  /**
   * The hybrid records its call count, half result count and latency on meters that carry no tenant
   * or document label (invariant 9).
   */
  @Test
  void hybrid_search_records_the_meter_without_any_tenant_or_document_label() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RetrievalService svc =
        new RetrievalService(
            chunkRepository, knowledgeBaseRepository, embeddingPort, 10, 50, 0.5, registry);

    stubOwnedKnowledgeBase();
    when(embeddingPort.embed(any()))
        .thenReturn(new EmbeddingResponse(MODEL, List.of(vector768()), null));
    when(chunkRepository.findByKeyword(any(), any(), any(), anyInt()))
        .thenReturn(List.of(lexicalChunkRow(UUID.randomUUID(), "lexical hit", 0.7)));

    svc.hybridSearch(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null));

    Search hybrid = registry.find(RetrievalService.METRIC_HYBRID);
    List<Meter> hybridMeters = hybrid.meters().stream().toList();
    assertThat(hybridMeters).hasSize(1);
    assertThat(hybridMeters.get(0).getId().getTags()).isEmpty();
    assertThat(registry.find(RetrievalService.METRIC_HALF_RESULTS).meters()).isNotEmpty();
    assertThat(registry.find(RetrievalService.METRIC_DURATION).meters()).isNotEmpty();
  }
}
