package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tests for the {@code S10-14} search-request value, {@link SearchRequest}. */
class SearchRequestTest {

  @Test
  void a_query_with_a_null_top_k_is_the_default_request() {
    SearchRequest request = new SearchRequest("how do I reset my password", null);

    assertThat(request.query()).isEqualTo("how do I reset my password");
    assertThat(request.topK()).isNull();
  }

  @Test
  void a_query_with_an_explicit_top_k_is_kept() {
    SearchRequest request = new SearchRequest("q", 5);

    assertThat(request.topK()).isEqualTo(5);
  }

  @Test
  void a_null_query_is_a_contract_failure() {
    assertThatThrownBy(() -> new SearchRequest(null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("query");
  }

  @Test
  void a_blank_query_is_a_contract_failure() {
    assertThatThrownBy(() -> new SearchRequest("   ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void a_top_k_below_one_is_a_contract_failure() {
    assertThatThrownBy(() -> new SearchRequest("q", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("topK");
  }
}
