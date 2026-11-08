package com.example.agentflow.ai.provider;

import com.example.agentflow.ai.application.ChatMessage;
import com.example.agentflow.ai.application.ChatModelPort;
import com.example.agentflow.ai.application.ChatRequest;
import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.ChatUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The concrete Spring AI (OpenAI) adapter behind {@link ChatModelPort} (S09-02).
 *
 * <p>It is the only class in the {@code ai} module (and the only class in the module graph that
 * S09-02 adds) that imports {@code org.springframework.ai}: it renders the neutral {@link
 * com.example.agentflow.ai.application.ChatRequest} contract into the Spring AI {@link Prompt} /
 * {@link Message} shapes and maps the {@link ChatResponse} back to the contract. The domain never
 * sees a Spring AI type — it only ever builds and reads the neutral records behind the port
 * (ADR-014).
 *
 * <p>It depends on the provider-neutral {@link ChatModel} <em>interface</em>, never on the concrete
 * {@link OpenAiChatModel}: the concrete model is wired by {@link OpenAiChatModelConfiguration}, so
 * the adapter's mapping logic can be exercised against a fake model in a DB-free unit test, and the
 * provider SDK stays behind the configuration boundary.
 *
 * <p>The mapping is faithful to the contract's invariants: a {@code null} completion text (the
 * provider refused or returned an empty completion) is returned as {@code null} content, never an
 * empty string, so the domain treats it as malformed output (S09-08); the token accounting is
 * derived from the provider's {@link Usage} and is {@code null} when the provider reports none; and
 * every neutral role is rendered into the provider's own role vocabulary (never the reverse).
 */
public final class OpenAiChatModelAdapter implements ChatModelPort {

  private final ChatModel chatModel;

  /**
   * @param chatModel the Spring AI chat model the adapter talks to; the provider-neutral {@link
   *     ChatModel} interface, so the concrete {@link OpenAiChatModel} is created elsewhere (@link
   *     OpenAiChatModelConfiguration)
   * @throws NullPointerException if {@code chatModel} is {@code null}
   */
  public OpenAiChatModelAdapter(ChatModel chatModel) {
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
  }

  @Override
  public ChatResponse chat(ChatRequest request) {
    Prompt prompt = toPrompt(request);
    org.springframework.ai.chat.model.ChatResponse aiResponse = chatModel.call(prompt);
    return toResponse(request.model(), aiResponse);
  }

  private static Prompt toPrompt(ChatRequest request) {
    List<Message> messages = new ArrayList<>(request.messages().size());
    for (ChatMessage message : request.messages()) {
      messages.add(toSpringMessage(message));
    }
    // The per-call tuning rides on the prompt; the model's own OpenAiChatOptions (API key, base
    // URL, default model) come from the adapter's wiring, not from here.
    return new Prompt(messages, toChatOptions(request));
  }

  private static Message toSpringMessage(ChatMessage message) {
    // The contract's three roles map onto the provider's own role vocabulary here, never the
    // reverse: the domain speaks SYSTEM / ASSISTANT / USER, the adapter renders each into the
    // provider message type. A blank content never reaches here (the contract rejects it up front).
    switch (message.role()) {
      case SYSTEM:
        return new SystemMessage(message.content());
      case ASSISTANT:
        return new AssistantMessage(message.content());
      case USER:
      default:
        return new UserMessage(message.content());
    }
  }

  private static org.springframework.ai.chat.prompt.ChatOptions toChatOptions(ChatRequest request) {
    OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
    if (request.model() != null) {
      options.model(request.model());
    }
    if (request.options() == null) {
      return options.build();
    }
    Double temperature = request.options().temperature();
    if (temperature != null) {
      options.temperature(temperature);
    }
    Integer maxTokens = request.options().maxOutputTokens();
    if (maxTokens != null) {
      options.maxTokens(maxTokens);
    }
    // A structured-output schema is a neutral JSON string on the contract; rendering it into the
    // provider's structured-output form stays behind the port, so the contract never imports a
    // provider schema type.
    String schema = request.options().outputSchemaJson();
    if (schema != null) {
      options.outputSchema(schema);
    }
    return options.build();
  }

  private static ChatResponse toResponse(
      String requestedModel, org.springframework.ai.chat.model.ChatResponse aiResponse) {
    Generation generation = aiResponse.getResult();
    AssistantMessage output = generation == null ? null : generation.getOutput();
    // A null text is the provider refusing / returning an empty completion: return null, never an
    // empty string, so the domain treats it as malformed output (S09-08), not a valid plan.
    String content = output == null ? null : output.getText();

    ChatResponseMetadata metadata = aiResponse.getMetadata();
    String model =
        (metadata == null || metadata.getModel() == null) ? requestedModel : metadata.getModel();

    String finishReason = null;
    if (generation != null && generation.getMetadata() != null) {
      finishReason = generation.getMetadata().getFinishReason();
    }

    return new ChatResponse(model, content, finishReason, toChatUsage(metadata));
  }

  private static ChatUsage toChatUsage(ChatResponseMetadata metadata) {
    if (metadata == null) {
      return null;
    }
    Usage usage = metadata.getUsage();
    // EmptyUsage is Spring AI's "the provider reported no usage" sentinel (zero tokens on every
    // field): treat it exactly like a missing usage so the domain sees null rather than a bogus
    // zero-token completion (which would be read as a free request by cost accounting).
    if (usage == null || usage instanceof EmptyUsage) {
      return null;
    }
    return new ChatUsage(
        toLong(usage.getPromptTokens()),
        toLong(usage.getCompletionTokens()),
        toLong(usage.getTotalTokens()));
  }

  private static long toLong(Integer value) {
    return value == null ? 0L : value.longValue();
  }
}
