package com.example.agentflow.ai.application;

import java.util.Objects;

/**
 * One message in a {@link ChatRequest}.
 *
 * <p>A neutral, provider-agnostic turn: the role is one of the three {@link MessageRole}s and the
 * content is the raw text. The provider-specific adapter (S09-02) renders this into the provider's
 * message shape; the contract itself never imports a provider DTO. The content must be present
 * (never {@code null} or blank) — a blank turn is a caller error, not a valid message — and the
 * object is validated up front so an invalid message fails fast, before the call reaches the
 * adapter.
 */
public record ChatMessage(MessageRole role, String content) {

  /**
   * Canonical constructor.
   *
   * @param role the message role; must not be {@code null}
   * @param content the message text; must not be {@code null} or blank
   * @throws NullPointerException if {@code role} is {@code null}
   * @throws IllegalArgumentException if {@code content} is {@code null} or blank
   */
  public ChatMessage {
    Objects.requireNonNull(role, "role must not be null");
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be null or blank");
    }
  }
}
