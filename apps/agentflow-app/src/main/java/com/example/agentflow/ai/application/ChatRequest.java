package com.example.agentflow.ai.application;

import java.util.List;
import java.util.Objects;

/**
 * A chat-completion request to the {@link ChatModelPort} (planning and evaluation are both chat
 * completions — S09-01).
 *
 * <p>It is the neutral contract the domain builds and the provider-specific adapter (S09-02)
 * consumes; it never references a provider's request DTO. The {@link #model()} and {@link
 * #messages()} are mandatory and validated up front, and the messages are returned by {@link
 * #messages()} as an unmodifiable snapshot so a caller can never mutate the request in flight.
 *
 * @param model the model id to call; non-null, non-blank
 * @param messages the conversation, at least one turn; stored immutably
 * @param options optional tuning ({@link ChatOptions}); may be {@code null}
 */
public record ChatRequest(String model, List<ChatMessage> messages, ChatOptions options) {

  /**
   * Canonical constructor.
   *
   * @param model the model id; must not be {@code null} or blank
   * @param messages the conversation; must not be {@code null} and must not be empty
   * @param options optional tuning; may be {@code null}
   * @throws NullPointerException if {@code model} or {@code messages} is {@code null}
   * @throws IllegalArgumentException if {@code model} is blank or {@code messages} is empty
   */
  public ChatRequest {
    Objects.requireNonNull(model, "model must not be null");
    if (model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    Objects.requireNonNull(messages, "messages must not be null");
    if (messages.isEmpty()) {
      throw new IllegalArgumentException("messages must not be empty");
    }
  }

  /**
   * Convenience constructor for a request with no tuning options.
   *
   * @param model the model id; must not be {@code null} or blank
   * @param messages the conversation; must not be {@code null} and must not be empty
   */
  public ChatRequest(String model, List<ChatMessage> messages) {
    this(model, messages, null);
  }

  /**
   * @return an unmodifiable view of the conversation; the backing list is copied so the caller's
   *     list can never be mutated through this accessor
   */
  @Override
  public List<ChatMessage> messages() {
    return List.copyOf(messages);
  }
}
