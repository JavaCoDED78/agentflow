package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@code S10-14} retrieved-chunk value, {@link ChunkDto}. */
class ChunkDtoTest {

  private static final UUID CHUNK = UUID.fromString("11111111-2222-3333-4444-555566667777");
  private static final UUID DOCUMENT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final String DOC_NAME = "doc-title";

  @Test
  void a_chunk_carries_the_document_binding_display_name_pages_score_and_content() {
    ChunkDto chunk = new ChunkDto(CHUNK, DOCUMENT, DOC_NAME, 3, 7, 0.25, "fragment text");

    assertThat(chunk.id()).isEqualTo(CHUNK);
    assertThat(chunk.documentId()).isEqualTo(DOCUMENT);
    assertThat(chunk.documentName()).isEqualTo(DOC_NAME);
    assertThat(chunk.pageFrom()).isEqualTo(3);
    assertThat(chunk.pageTo()).isEqualTo(7);
    assertThat(chunk.score()).isEqualTo(0.25);
    assertThat(chunk.content()).isEqualTo("fragment text");
  }

  @Test
  void a_zero_score_is_a_valid_exact_match() {
    assertThat(new ChunkDto(CHUNK, DOCUMENT, DOC_NAME, 1, 1, 0.0, "identical").score()).isZero();
  }

  @Test
  void a_null_id_or_document_or_display_name_or_content_is_a_contract_failure() {
    assertThatThrownBy(() -> new ChunkDto(null, DOCUMENT, DOC_NAME, 1, 1, 0.1, "c"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ChunkDto(CHUNK, null, DOC_NAME, 1, 1, 0.1, "c"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ChunkDto(CHUNK, DOCUMENT, null, 1, 1, 0.1, "c"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("documentName");
    assertThatThrownBy(() -> new ChunkDto(CHUNK, DOCUMENT, DOC_NAME, 1, 1, 0.1, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_negative_or_non_finite_score_is_a_contract_failure() {
    assertThatThrownBy(() -> new ChunkDto(CHUNK, DOCUMENT, DOC_NAME, 1, 1, -0.1, "c"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("score");
    assertThatThrownBy(() -> new ChunkDto(CHUNK, DOCUMENT, DOC_NAME, 1, 1, Double.NaN, "c"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("score");
  }
}
