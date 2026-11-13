package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CitationBuilder} — the pure citation-over-retrieved-evidence transformer
 * (S10-17). DB-free: it operates on in-memory {@link SearchResult}/{@link ChunkDto} fixtures, so it
 * needs no {@code DataSource} or embedding provider.
 */
class CitationBuilderTest {

  private static final UUID DOCUMENT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final String DOC_NAME = "doc-title";

  private static ChunkDto chunk(UUID chunkId, String content, double score) {
    return new ChunkDto(chunkId, DOCUMENT, DOC_NAME, 1, 2, score, content);
  }

  @Test
  void one_citation_is_built_per_retrieved_chunk_in_retrieved_order() {
    SearchResult result =
        new SearchResult(
            List.of(
                chunk(UUID.randomUUID(), "first", 0.4), chunk(UUID.randomUUID(), "second", 0.2)));

    List<CitationDto> citations = CitationBuilder.cite(result);

    assertThat(citations).hasSize(2);
    // One citation per chunk, in retrieved order, carrying the chunk's grounding and its snippet.
    assertThat(citations.get(0).snippet()).isEqualTo("first");
    assertThat(citations.get(0).score()).isEqualTo(0.4);
    assertThat(citations.get(0).documentId()).isEqualTo(DOCUMENT);
    assertThat(citations.get(0).documentName()).isEqualTo(DOC_NAME);
    assertThat(citations.get(1).snippet()).isEqualTo("second");
    assertThat(citations.get(1).score()).isEqualTo(0.2);
  }

  @Test
  void an_empty_result_yields_no_citations_never_an_invented_one() {
    assertThat(CitationBuilder.cite(new SearchResult(List.of()))).isEmpty();
  }

  @Test
  void the_citation_resolves_to_the_same_chunk_it_cites() {
    UUID chunkId = UUID.randomUUID();
    SearchResult result = new SearchResult(List.of(chunk(chunkId, "the fragment", 0.9)));

    assertThat(CitationBuilder.cite(result).get(0).chunkId()).isEqualTo(chunkId);
  }

  @Test
  void a_null_result_is_a_contract_failure() {
    assertThatThrownBy(() -> CitationBuilder.cite(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("result");
  }
}
