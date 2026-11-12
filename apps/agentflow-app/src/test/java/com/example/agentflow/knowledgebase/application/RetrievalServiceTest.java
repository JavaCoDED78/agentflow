package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRepository;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRow;
import com.example.agentflow.knowledgebase.persistence.KnowledgeBaseRepository;
import java.time.OffsetDateTime;
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
 *   <li>a missing — including cross-tenant — knowledge base yields an empty result without ever
 *       calling the embedding port or the chunk repository; and
 *   <li>with no database or no embedding provider the service fails loudly at call time (S01-02 /
 *       {@code ADR-014}).
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
        new RetrievalService(chunkRepository, knowledgeBaseRepository, embeddingPort, 10, 50, 0.5);
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
        distance);
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
    RetrievalService noDatabase = new RetrievalService(null, null, embeddingPort, 10, 50, 0.5);

    assertThatThrownBy(
            () -> noDatabase.search(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no database connection");
  }

  /** ADR-014: no embedding provider — the service exists and fails loudly at call time. */
  @Test
  void search_fails_loudly_without_an_embedding_provider() {
    RetrievalService noProvider =
        new RetrievalService(chunkRepository, knowledgeBaseRepository, null, 10, 50, 0.5);

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
                    chunkRepository, knowledgeBaseRepository, embeddingPort, 10, 9, 0.5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxTopK");
  }

  /** The threshold is a cosine distance and must stay inside [0.0, 2.0]. */
  @Test
  void the_constructor_rejects_an_out_of_range_threshold() {
    assertThatThrownBy(
            () ->
                new RetrievalService(
                    chunkRepository, knowledgeBaseRepository, embeddingPort, 10, 50, 2.5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("threshold");
    assertThatThrownBy(
            () ->
                new RetrievalService(
                    chunkRepository, knowledgeBaseRepository, embeddingPort, 10, 50, -0.1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("threshold");
  }
}
