package com.example.agentflow.ai.application;

/**
 * Tuning options for a {@link ChatModelPort#chat(ChatRequest)} call.
 *
 * <p>All three values are optional: {@code null} means "leave it to the adapter's default". When
 * {@link #outputSchemaJson()} is present, the adapter is expected to produce a completion whose
 * text is valid JSON conforming to that schema (this is how the Planner requests structured output
 * — S09-05 — without the contract knowing anything about the provider's structured-output
 * mechanism, which stays behind the port). When it is {@code null}, the adapter returns plain text.
 *
 * <p>The schema is carried as a JSON <em>string</em>, not a parsed object: it is neutral data that
 * the adapter renders into the provider's own schema format, so the contract never depends on a
 * provider's schema type. It is validated server-side against the same validator the Planner uses
 * (S09-07).
 */
public record ChatOptions(Double temperature, Integer maxOutputTokens, String outputSchemaJson) {}
