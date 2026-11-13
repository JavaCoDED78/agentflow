package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@code S10-14} search-result value, {@link SearchResult}. */
class SearchResultTest {

  private static ChunkDto chunk(String content) {
    return new ChunkDto(UUID.randomUUID(), UUID.randomUUID(), "doc", 1, 1, 0.1, content);
  }

  @Test
  void the_shared_empty_result_carries_no_chunks() {
    assertThat(SearchResult.empty().chunks()).isEmpty();
  }

  @Test
  void an_empty_chunk_list_is_a_legitimate_answer() {
    assertThat(new SearchResult(List.of()).chunks()).isEmpty();
  }

  @Test
  void the_chunks_list_is_stored_as_an_unmodifiable_copy() {
    List<ChunkDto> source = new ArrayList<>(List.of(chunk("a")));
    SearchResult result = new SearchResult(source);
    source.add(chunk("b"));

    assertThat(result.chunks()).hasSize(1);
    assertThatThrownBy(() -> result.chunks().add(chunk("c")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void a_null_chunk_list_is_a_contract_failure() {
    assertThatThrownBy(() -> new SearchResult(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunks");
  }
}
