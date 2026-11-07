package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S09-01 acceptance evidence for the {@code ai} provider port contract.
 *
 * <p>The port is the boundary through which another module calls the model provider (planning /
 * evaluation chat completions, and embedding generation). The whole point of the port is that the
 * domain depends only on these neutral, provider-agnostic records and never on a provider-specific
 * DTO (the acceptance criterion for {@code S09-01}), so these tests pin the neutral contract:
 *
 * <ul>
 *   <li>required fields are validated up front (fail fast, explicit);
 *   <li>collections are held immutably (the caller's mutation cannot change the request
 *       mid-flight);
 *   <li>array payloads ({@code float[]} embeddings) are copied on the way out, so the holder owns
 *       its data.
 * </ul>
 *
 * None of these types imports any provider SDK: the {@link AiProviderPortArchitecturalTest} asserts
 * that.
 */
class AiProviderPortContractTest {

  @Test
  void chat_message_exposes_role_and_content() {
    ChatMessage message = new ChatMessage(MessageRole.USER, "hello");

    assertThat(message.role()).isEqualTo(MessageRole.USER);
    assertThat(message.content()).isEqualTo("hello");
  }

  @Test
  void chat_message_rejects_null_role() {
    assertThatThrownBy(() -> new ChatMessage(null, "hello"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void chat_message_rejects_blank_content() {
    assertThatThrownBy(() -> new ChatMessage(MessageRole.USER, "   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void chat_options_accepts_a_blank_slate() {
    ChatOptions options = new ChatOptions(null, null, null);

    assertThat(options.temperature()).isNull();
    assertThat(options.maxOutputTokens()).isNull();
    assertThat(options.outputSchemaJson()).isNull();
  }

  @Test
  void chat_options_carries_a_structured_output_schema() {
    ChatOptions options = new ChatOptions(0.2, 256, "{\"type\":\"object\"}");

    assertThat(options.temperature()).isEqualTo(0.2);
    assertThat(options.maxOutputTokens()).isEqualTo(256);
    assertThat(options.outputSchemaJson()).isEqualTo("{\"type\":\"object\"}");
  }

  @Test
  void chat_request_validates_its_required_fields() {
    assertThatThrownBy(() -> new ChatRequest(null, List.of(new ChatMessage(MessageRole.USER, "x"))))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ChatRequest("   ", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ChatRequest("openai", null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ChatRequest("openai", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void chat_request_holds_its_messages_immutably() {
    List<ChatMessage> sent = List.of(new ChatMessage(MessageRole.USER, "hi"));
    ChatRequest request = new ChatRequest("openai", sent, null);

    assertThatThrownBy(() -> request.messages().add(new ChatMessage(MessageRole.USER, "again")))
        .isInstanceOf(UnsupportedOperationException.class);

    // The request returns its own view, so the caller's list is neither mutated nor leaked.
    assertThat(sent).hasSize(1);
  }

  @Test
  void chat_response_allows_a_refusal_as_null_content() {
    ChatResponse response = new ChatResponse("openai", null, "stop", new ChatUsage(1, 0, 1));

    assertThat(response.content()).isNull();
    assertThat(response.finishReason()).isEqualTo("stop");
    assertThat(response.usage().totalTokens()).isEqualTo(1);
  }

  @Test
  void chat_response_validates_the_model_label() {
    assertThatThrownBy(() -> new ChatResponse("", "content", "stop", new ChatUsage(1, 1, 2)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void embedding_request_validates_its_required_fields() {
    assertThatThrownBy(() -> new EmbeddingRequest(null, List.of("doc")))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EmbeddingRequest("   ", List.of("doc")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmbeddingRequest("openai", null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new EmbeddingRequest("openai", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void embedding_response_copies_each_embedding_on_the_way_out() {
    float[] first = {0.1f, 0.2f, 0.3f};
    float[] second = {0.4f, 0.5f, 0.6f};
    EmbeddingResponse response = new EmbeddingResponse("openai", List.of(first, second), null);

    float[] returnedFirst = response.embeddings().get(0);
    returnedFirst[0] = 9.0f;

    assertThat(first).containsExactly(0.1f, 0.2f, 0.3f);
    assertThat(response.embeddings().get(0)).containsExactly(0.1f, 0.2f, 0.3f);
    assertThat(response.usage()).isNull();
  }

  @Test
  void embedding_response_validates_the_model_label() {
    assertThatThrownBy(() -> new EmbeddingResponse("", List.of(new float[] {1f}), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
