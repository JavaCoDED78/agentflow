package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.ChatMessage;
import com.example.agentflow.ai.application.ChatRequest;
import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.MessageRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * S09-02 unit tests for {@link OpenAiChatModelAdapter} (DB-free).
 *
 * <p>The adapter depends on the provider-neutral {@link ChatModel} interface, so the mapping logic
 * is exercised against a {@link FakeChatModel} — a minimal {@link ChatModel} that captures the
 * {@link Prompt} the adapter builds and returns a canned Spring AI response. This keeps the test
 * deterministic and free of any network, API key or model dependency, while still driving the real
 * mapping code: the message-role rendering, the per-call options, and the {@link ChatResponse} ->
 * {@link ChatResponse} contract mapping.
 */
class OpenAiChatModelAdapterTest {

  @Test
  void maps_a_completion_into_the_contract() {
    FakeChatModel model = new FakeChatModel("structured:ok", "stop", "gpt-4o-mini", 11, 7, true);
    OpenAiChatModelAdapter adapter = new OpenAiChatModelAdapter(model);

    ChatRequest request =
        new ChatRequest("gpt-4o-mini", List.of(new ChatMessage(MessageRole.USER, "hi")));

    ChatResponse response = adapter.chat(request);

    assertThat(response.content()).isEqualTo("structured:ok");
    assertThat(response.model()).isEqualTo("gpt-4o-mini");
    assertThat(response.finishReason()).isEqualTo("stop");
    assertThat(response.usage()).isNotNull();
    assertThat(response.usage().inputTokens()).isEqualTo(11L);
    assertThat(response.usage().outputTokens()).isEqualTo(7L);
    assertThat(response.usage().totalTokens()).isEqualTo(18L);
  }

  @Test
  void renders_all_three_message_roles_into_provider_message_types() {
    FakeChatModel model = new FakeChatModel("ok", null, "gpt-4o-mini", 1, 1, false);
    OpenAiChatModelAdapter adapter = new OpenAiChatModelAdapter(model);

    adapter.chat(
        new ChatRequest(
            "gpt-4o-mini",
            List.of(
                new ChatMessage(MessageRole.SYSTEM, "sys"),
                new ChatMessage(MessageRole.ASSISTANT, "as"),
                new ChatMessage(MessageRole.USER, "us"))));

    List<Message> rendered = model.captured().getInstructions();
    assertThat(rendered).hasSize(3);
    assertThat(rendered.get(0)).isInstanceOf(SystemMessage.class);
    assertThat(rendered.get(1)).isInstanceOf(AssistantMessage.class);
    assertThat(rendered.get(2)).isInstanceOf(UserMessage.class);
  }

  @Test
  void returns_null_content_on_a_refusal_or_empty_completion() {
    FakeChatModel model = new FakeChatModel(null, "stop", "gpt-4o-mini", 3, 0, true);
    OpenAiChatModelAdapter adapter = new OpenAiChatModelAdapter(model);

    ChatResponse response =
        adapter.chat(
            new ChatRequest("gpt-4o-mini", List.of(new ChatMessage(MessageRole.USER, "hi"))));

    // null content, never an empty string: the domain treats it as malformed output (S09-08).
    assertThat(response.content()).isNull();
  }

  @Test
  void passes_tuning_and_schema_through_on_the_prompt() {
    FakeChatModel model = new FakeChatModel("ok", null, "gpt-4o-mini", 1, 1, false);
    OpenAiChatModelAdapter adapter = new OpenAiChatModelAdapter(model);

    adapter.chat(
        new ChatRequest(
            "gpt-4o-mini",
            List.of(new ChatMessage(MessageRole.USER, "hi")),
            new com.example.agentflow.ai.application.ChatOptions(
                0.2, 256, "{\"type\":\"object\"}")));

    OpenAiChatOptions options = (OpenAiChatOptions) model.captured().getOptions();
    assertThat(options.getTemperature()).isEqualTo(0.2);
    assertThat(options.getMaxTokens()).isEqualTo(256);
    assertThat(options.getOutputSchema()).isEqualTo("{\"type\":\"object\"}");
  }

  @Test
  void usage_is_null_when_the_provider_reports_none() {
    FakeChatModel model = new FakeChatModel("ok", null, "gpt-4o-mini", 0, 0, false);
    model.withUsage(false);
    OpenAiChatModelAdapter adapter = new OpenAiChatModelAdapter(model);

    ChatResponse response =
        adapter.chat(
            new ChatRequest("gpt-4o-mini", List.of(new ChatMessage(MessageRole.USER, "hi"))));

    assertThat(response.usage()).isNull();
  }

  @Test
  void model_defaults_to_the_request_when_the_metadata_has_no_model() {
    FakeChatModel model = new FakeChatModel("ok", null, null, 1, 1, false);
    OpenAiChatModelAdapter adapter = new OpenAiChatModelAdapter(model);

    ChatResponse response =
        adapter.chat(
            new ChatRequest("gpt-4o-mini", List.of(new ChatMessage(MessageRole.USER, "hi"))));

    assertThat(response.model()).isEqualTo("gpt-4o-mini");
  }

  @Test
  void constructor_rejects_a_null_model() {
    assertThatThrownBy(() -> new OpenAiChatModelAdapter(null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * A minimal {@link ChatModel} the adapter drives: it captures the {@link Prompt} it is called
   * with and returns a canned Spring AI response built from the fixture values.
   */
  private static final class FakeChatModel implements ChatModel {

    private Prompt captured;
    private final String text;
    private final String finishReason;
    private final String model;
    private final Integer promptTokens;
    private final Integer completionTokens;
    private boolean includeUsage;

    FakeChatModel(
        String text,
        String finishReason,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        boolean includeUsage) {
      this.text = text;
      this.finishReason = finishReason;
      this.model = model;
      this.promptTokens = promptTokens;
      this.completionTokens = completionTokens;
      this.includeUsage = includeUsage;
    }

    void withUsage(boolean includeUsage) {
      this.includeUsage = includeUsage;
    }

    Prompt captured() {
      return captured;
    }

    @Override
    public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
      this.captured = prompt;
      AssistantMessage output =
          (text == null) ? AssistantMessage.builder().build() : new AssistantMessage(text);
      ChatGenerationMetadata generationMetadata =
          ChatGenerationMetadata.builder().finishReason(finishReason).build();
      Generation generation = new Generation(output, generationMetadata);

      ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().model(model);
      if (includeUsage) {
        metadata.usage(new DefaultUsage(promptTokens, completionTokens));
      }
      return new org.springframework.ai.chat.model.ChatResponse(
          List.of(generation), metadata.build());
    }

    @Override
    public Flux<org.springframework.ai.chat.model.ChatResponse> stream(Prompt prompt) {
      // Not exercised by the adapter; never subscribed in these tests.
      return Flux.error(
          new UnsupportedOperationException("stream is not exercised by the adapter"));
    }
  }
}
