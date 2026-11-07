package com.example.agentflow.ai.application;

/**
 * The outbound port for text-generation calls — the model the Planner uses to propose a typed plan
 * (S09-06) and the same call the {@code LLM_EVALUATE} node uses for an evaluation (S09-04).
 *
 * <p>It is the single boundary between the {@code ai} domain and the concrete provider SDK. Callers
 * build a neutral {@link ChatRequest}; the provider-specific adapter (S09-02) is the only code that
 * knows which SDK it talks to, and it is the only code that imports the provider DTO. Swapping or
 * retrying the provider never touches the domain (an S09-06 consequence of {@code ADR-006}).
 *
 * <p>The port is intentionally narrow — one {@link #chat(ChatRequest)} returning a neutral {@link
 * ChatResponse} — and is deliberately <em>not</em> a generic "LLM" abstraction: it exists because a
 * provider must be behind a boundary, not because a second provider is already required.
 */
public interface ChatModelPort {

  /**
   * Perform one chat completion.
   *
   * @param request the request to send
   * @return the provider's response; {@link ChatResponse#content()} is {@code null} on a refusal /
   *     empty completion
   * @throws IllegalArgumentException if the request is invalid (a contract-level, permanent fault)
   */
  ChatResponse chat(ChatRequest request);
}
