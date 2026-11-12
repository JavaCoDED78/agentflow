package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.ai.application.ProviderFailureReason;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the deterministic fake embedding provider (S10-12). The fixtures must be
 * reproducible (same text → same vector, order preserved), correctly shaped (768-wide vectors,
 * token accounting present) and must flow through the production {@link
 * ProviderExceptionClassifier} exactly like live provider faults — the property that lets the CI
 * embedding pipeline run without a real model.
 */
class FakeEmbeddingProviderTest {

  private static EmbeddingRequest request(String... texts) {
    return new EmbeddingRequest(FakeEmbeddingProvider.MODEL, java.util.List.of(texts));
  }

  @Test
  void the_same_text_always_yields_the_same_vector() {
    FakeEmbeddingProvider provider = FakeEmbeddingProvider.deterministic();

    EmbeddingResponse first = provider.embed(request("chunk-a", "chunk-b"));
    EmbeddingResponse second = provider.embed(request("chunk-a", "chunk-b"));

    assertThat(first.embeddings().get(0)).isEqualTo(second.embeddings().get(0));
    assertThat(first.embeddings().get(1)).isEqualTo(second.embeddings().get(1));
  }

  @Test
  void different_texts_yield_different_vectors_and_the_order_is_preserved() {
    FakeEmbeddingProvider provider = FakeEmbeddingProvider.deterministic();

    EmbeddingResponse response = provider.embed(request("chunk-a", "chunk-b"));

    assertThat(response.embeddings().get(0)).isNotEqualTo(response.embeddings().get(1));
    // The vector order follows the text order: re-embedding the texts reversed reverses the
    // vectors.
    EmbeddingResponse reversed = provider.embed(request("chunk-b", "chunk-a"));
    assertThat(reversed.embeddings().get(0)).isEqualTo(response.embeddings().get(1));
    assertThat(reversed.embeddings().get(1)).isEqualTo(response.embeddings().get(0));
  }

  @Test
  void every_vector_is_768_wide_and_usage_is_reported() {
    FakeEmbeddingProvider provider = FakeEmbeddingProvider.deterministic();

    EmbeddingResponse response = provider.embed(request("one", "two", "three"));

    assertThat(response.embeddings()).hasSize(3);
    assertThat(response.embeddings().get(0)).hasSize(FakeEmbeddingProvider.DIMENSIONS);
    assertThat(response.usage()).isNotNull();
    assertThat(response.usage().dimensions()).isEqualTo(FakeEmbeddingProvider.DIMENSIONS);
    // The token estimate is deterministic: one token per four characters, at least one per text.
    assertThat(response.usage().tokens()).isEqualTo(3);
  }

  @Test
  void returned_arrays_are_independent_copies() {
    FakeEmbeddingProvider provider = FakeEmbeddingProvider.deterministic();

    EmbeddingResponse response = provider.embed(request("chunk-a"));
    response.embeddings().get(0)[0] = 99.0f;

    assertThat(provider.embed(request("chunk-a")).embeddings().get(0)[0]).isNotEqualTo(99.0f);
  }

  @Test
  void every_call_is_counted() {
    FakeEmbeddingProvider provider = FakeEmbeddingProvider.deterministic();

    provider.embed(request("a"));
    provider.embed(request("b"));

    assertThat(provider.calls()).isEqualTo(2);
  }

  @Test
  void the_rate_limit_fixture_is_classified_like_a_live_429() {
    FakeEmbeddingProvider provider =
        new FakeEmbeddingProvider(FakeEmbeddingProvider.Fixture.RATE_LIMIT);
    ProviderExceptionClassifier classifier = new ProviderExceptionClassifier();

    assertThatThrownBy(() -> provider.embed(request("a")))
        .satisfies(
            fault -> {
              assertThat(classifier.classify(fault)).isEqualTo(ProviderFailureReason.RATE_LIMIT);
              assertThat(ProviderFailureReason.RATE_LIMIT.isRetryable()).isTrue();
            });
    assertThat(provider.calls()).isEqualTo(1);
  }

  @Test
  void the_timeout_fixture_is_classified_like_a_live_read_timeout() {
    FakeEmbeddingProvider provider =
        new FakeEmbeddingProvider(FakeEmbeddingProvider.Fixture.TIMEOUT);
    ProviderExceptionClassifier classifier = new ProviderExceptionClassifier();

    assertThatThrownBy(() -> provider.embed(request("a")))
        .satisfies(
            fault ->
                assertThat(classifier.classify(fault)).isEqualTo(ProviderFailureReason.TIMEOUT));
  }

  @Test
  void fixture_resolution_from_config_is_case_insensitive_and_fails_fast_on_unknown_values() {
    assertThat(FakeEmbeddingProvider.Fixture.fromConfig(null))
        .isEqualTo(FakeEmbeddingProvider.Fixture.DETERMINISTIC);
    assertThat(FakeEmbeddingProvider.Fixture.fromConfig("  "))
        .isEqualTo(FakeEmbeddingProvider.Fixture.DETERMINISTIC);
    assertThat(FakeEmbeddingProvider.Fixture.fromConfig("deterministic"))
        .isEqualTo(FakeEmbeddingProvider.Fixture.DETERMINISTIC);
    assertThat(FakeEmbeddingProvider.Fixture.fromConfig("rate-limit"))
        .isEqualTo(FakeEmbeddingProvider.Fixture.RATE_LIMIT);
    assertThat(FakeEmbeddingProvider.Fixture.fromConfig("429"))
        .isEqualTo(FakeEmbeddingProvider.Fixture.RATE_LIMIT);
    assertThat(FakeEmbeddingProvider.Fixture.fromConfig("Timeout"))
        .isEqualTo(FakeEmbeddingProvider.Fixture.TIMEOUT);

    assertThatThrownBy(() -> FakeEmbeddingProvider.Fixture.fromConfig("bogus"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bogus");
  }

  @Test
  void a_null_fixture_is_a_contract_error() {
    assertThatThrownBy(() -> new FakeEmbeddingProvider(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
