package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@code S10-17} grounded-citation value object, {@link CitationDto}. */
class CitationDtoTest {

  private static final UUID DOCUMENT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID CHUNK = UUID.fromString("11111111-2222-3333-4444-555566667777");
  private static final String DOC_NAME = "doc-title";

  @Test
  void a_citation_carries_the_document_chunk_name_pages_score_and_snippet() {
    CitationDto citation = new CitationDto(DOCUMENT, CHUNK, DOC_NAME, 3, 7, 0.25, "the fragment");

    assertThat(citation.documentId()).isEqualTo(DOCUMENT);
    assertThat(citation.chunkId()).isEqualTo(CHUNK);
    assertThat(citation.documentName()).isEqualTo(DOC_NAME);
    assertThat(citation.pageFrom()).isEqualTo(3);
    assertThat(citation.pageTo()).isEqualTo(7);
    assertThat(citation.score()).isEqualTo(0.25);
    assertThat(citation.snippet()).isEqualTo("the fragment");
  }

  @Test
  void a_zero_score_is_a_valid_exact_match() {
    assertThat(new CitationDto(DOCUMENT, CHUNK, DOC_NAME, 1, 1, 0.0, "identical").score()).isZero();
  }

  @Test
  void a_null_id_or_document_name_or_snippet_is_a_contract_failure() {
    assertThatThrownBy(() -> new CitationDto(null, CHUNK, DOC_NAME, 1, 1, 0.1, "s"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CitationDto(DOCUMENT, null, DOC_NAME, 1, 1, 0.1, "s"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CitationDto(DOCUMENT, CHUNK, null, 1, 1, 0.1, "s"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("documentName");
    assertThatThrownBy(() -> new CitationDto(DOCUMENT, CHUNK, DOC_NAME, 1, 1, 0.1, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_negative_or_non_finite_score_is_a_contract_failure() {
    assertThatThrownBy(() -> new CitationDto(DOCUMENT, CHUNK, DOC_NAME, 1, 1, -0.1, "s"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("score");
    assertThatThrownBy(() -> new CitationDto(DOCUMENT, CHUNK, DOC_NAME, 1, 1, Double.NaN, "s"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("score");
  }
}
