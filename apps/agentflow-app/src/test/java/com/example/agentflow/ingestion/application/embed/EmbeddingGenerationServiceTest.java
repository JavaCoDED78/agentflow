package com.example.agentflow.ingestion.application.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.ai.application.EmbeddingUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the embedding generation service (S10-12) — the S10-12 acceptance: the embeddings
 * pipeline (batching, ordering, validation, accounting) is verified without a real model, against a
 * scripted {@link EmbeddingPort} double. DB-free, no network, no clock (invariant 10).
 */
class EmbeddingGenerationServiceTest {

  private static final String MODEL = "nomic-embed-text";

  /**
   * A scripted port: answers each call from the queued response, otherwise the fixed one; records
   * every request so the batching tests assert the exact batches the service sent.
   */
  private static final class ScriptedPort implements EmbeddingPort {
    final List<EmbeddingRequest> requests = new ArrayList<>();
    final List<RuntimeException> faults = new ArrayList<>();
    final EmbeddingResponse ok;
    final AtomicInteger calls = new AtomicInteger();

    ScriptedPort(EmbeddingResponse ok) {
      this.ok = ok;
    }

    ScriptedPort failWith(RuntimeException fault) {
      faults.add(fault);
      return this;
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
      int index = calls.getAndIncrement();
      requests.add(request);
      if (index < faults.size()) {
        throw faults.get(index);
      }
      return ok;
    }
  }

  private static EmbeddingResponse response(int count, int dimensions, long tokens) {
    List<float[]> vectors = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      vectors.add(new float[dimensions]);
    }
    return new EmbeddingResponse(MODEL, vectors, new EmbeddingUsage(tokens, dimensions));
  }

  private static List<String> texts(int count) {
    List<String> texts = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      texts.add("chunk-" + i);
    }
    return texts;
  }

  @Test
  void a_single_batch_call_embeds_every_text_in_order() {
    ScriptedPort port = new ScriptedPort(response(3, 4, 30));
    EmbeddingGenerationService service = new EmbeddingGenerationService(port, 32);

    EmbeddingBatchResult result = service.embed(MODEL, texts(3));

    assertThat(result.vectors()).hasSize(3);
    assertThat(result.tokens()).isEqualTo(30);
    assertThat(result.dimensions()).isEqualTo(4);
    assertThat(port.requests).hasSize(1);
    assertThat(port.requests.get(0).texts()).containsExactly("chunk-0", "chunk-1", "chunk-2");
    assertThat(port.requests.get(0).model()).isEqualTo(MODEL);
  }

  @Test
  void texts_are_split_into_consecutive_batches_of_at_most_the_batch_size() {
    // A recording port: answers every call with one vector per text of the request.
    List<EmbeddingRequest> requests = new ArrayList<>();
    EmbeddingPort batchingPort =
        request -> {
          requests.add(request);
          return response(request.texts().size(), 4, request.texts().size() * 2L);
        };
    EmbeddingGenerationService service = new EmbeddingGenerationService(batchingPort, 2);

    EmbeddingBatchResult result = service.embed(MODEL, texts(5));

    // Three batches of at most 2 for 5 texts, each batch consecutive and in order.
    assertThat(requests).hasSize(3);
    assertThat(requests.get(0).texts()).containsExactly("chunk-0", "chunk-1");
    assertThat(requests.get(1).texts()).containsExactly("chunk-2", "chunk-3");
    assertThat(requests.get(2).texts()).containsExactly("chunk-4");
    // 5 vectors in the exact input order, tokens aggregated across the three batch calls.
    assertThat(result.vectors()).hasSize(5);
    assertThat(result.tokens()).isEqualTo(4 + 4 + 2);
    assertThat(result.dimensions()).isEqualTo(4);
  }

  @Test
  void result_vectors_are_independent_copies() {
    EmbeddingResponse ok = response(2, 4, 0);
    EmbeddingGenerationService service = new EmbeddingGenerationService(new ScriptedPort(ok), 32);

    EmbeddingBatchResult result = service.embed(MODEL, texts(2));
    List<float[]> first = result.vectors();
    first.get(0)[0] = 99.0f;

    assertThat(result.vectors().get(0)[0]).isEqualTo(0.0f);
  }

  @Test
  void a_batch_with_a_short_vector_count_fails_loudly() {
    // A provider that returns fewer vectors than texts breaks the port contract: no partial,
    // mis-aligned result may escape.
    EmbeddingPort shortPort =
        request -> new EmbeddingResponse(MODEL, List.of(new float[4]), new EmbeddingUsage(1, 4));
    EmbeddingGenerationService service = new EmbeddingGenerationService(shortPort, 32);

    assertThatThrownBy(() -> service.embed(MODEL, texts(3)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("3");
  }

  @Test
  void a_batch_with_an_empty_vector_fails_loudly() {
    EmbeddingPort emptyVectorPort =
        request ->
            new EmbeddingResponse(
                MODEL,
                java.util.Arrays.asList(new float[4], new float[0], new float[4]),
                new EmbeddingUsage(1, 4));
    EmbeddingGenerationService service = new EmbeddingGenerationService(emptyVectorPort, 32);

    assertThatThrownBy(() -> service.embed(MODEL, texts(3)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void a_non_uniform_embedding_width_across_batches_fails_loudly() {
    AtomicInteger call = new AtomicInteger();
    EmbeddingPort mixedWidthsPort =
        request -> {
          int width = call.getAndIncrement() == 0 ? 4 : 5;
          return response(request.texts().size(), width, 0);
        };
    EmbeddingGenerationService service = new EmbeddingGenerationService(mixedWidthsPort, 2);

    assertThatThrownBy(() -> service.embed(MODEL, texts(4)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-uniform");
  }

  @Test
  void a_provider_fault_propagates_and_fails_the_whole_run() {
    RuntimeException providerFault = new RuntimeException("provider unavailable");
    ScriptedPort port = new ScriptedPort(response(2, 4, 0)).failWith(providerFault);
    EmbeddingGenerationService service = new EmbeddingGenerationService(port, 2);

    assertThatThrownBy(() -> service.embed(MODEL, texts(5))).isSameAs(providerFault);
  }

  @Test
  void null_or_blank_model_is_rejected() {
    EmbeddingGenerationService service =
        new EmbeddingGenerationService(new ScriptedPort(response(1, 4, 0)), 32);

    assertThatThrownBy(() -> service.embed(null, texts(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.embed("  ", texts(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void empty_or_null_texts_are_rejected() {
    EmbeddingGenerationService service =
        new EmbeddingGenerationService(new ScriptedPort(response(1, 4, 0)), 32);

    assertThatThrownBy(() -> service.embed(MODEL, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.embed(MODEL, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.embed(MODEL, java.util.Arrays.asList("a", null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructor_guards_reject_non_positive_batch_size() {
    EmbeddingPort port = new ScriptedPort(response(1, 4, 0));

    assertThatThrownBy(() -> new EmbeddingGenerationService(port, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingGenerationService(port, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_service_without_a_port_constructs_but_fails_loudly_at_call_time() {
    // The port is opt-in (ADR-014): the app boots without a provider, so the service must
    // construct with no port and fail loudly when embedding is attempted — the PlannerService /
    // ChatModelPort idiom.
    EmbeddingGenerationService service = new EmbeddingGenerationService(null, 32);

    assertThat(service.batchSize()).isEqualTo(32);
    assertThatThrownBy(() -> service.embed(MODEL, texts(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no EmbeddingPort is configured");
  }
}
