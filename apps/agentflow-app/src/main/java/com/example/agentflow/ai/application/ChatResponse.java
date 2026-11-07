package com.example.agentflow.ai.application;

import java.util.Objects;

/**
 * The result of a {@link ChatModelPort#chat(ChatRequest)} call.
 *
 * <p>Provider-agnostic: {@link #content()} is the raw completion text — structured JSON when the
 * request carried an {@link ChatOptions#outputSchemaJson()} schema, plain text otherwise — so the
 * domain parses and validates it against the plan schema without ever seeing a provider DTO. When
 * the provider refuses or returns an empty completion, {@link #content()} is {@code null} and the
 * domain treats that as malformed output (S09-08), never as a valid plan.
 *
 * @param model the model that produced the completion
 * @param content the completion text; {@code null} when the provider returned nothing / refused
 * @param finishReason the provider's finish reason (for example {@code stop} / {@code length});
 *     {@code null} when the provider does not report one
 * @param usage the token accounting; {@code null} when the provider does not report it
 */
public record ChatResponse(String model, String content, String finishReason, ChatUsage usage) {

  /**
   * Canonical constructor.
   *
   * @param model the model id; must not be {@code null} or blank
   * @param content the completion text; may be {@code null} (a refusal / empty completion)
   * @param finishReason the finish reason; may be {@code null}
   * @param usage the token accounting; may be {@code null}
   * @throws IllegalArgumentException if {@code model} is {@code null} or blank
   */
  public ChatResponse {
    Objects.requireNonNull(model, "model must not be null");
    if (model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
  }
}
