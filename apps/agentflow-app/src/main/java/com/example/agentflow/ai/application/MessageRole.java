package com.example.agentflow.ai.application;

/**
 * The role of a message in a {@link ChatRequest}.
 *
 * <p>These are the neutral roles of the chat contract. They map onto the provider's own role
 * vocabulary inside the provider-specific adapter (S09-02), never in the contract: the domain
 * speaks {@code SYSTEM / ASSISTANT / USER} and the adapter renders that into whatever the provider
 * expects. Keeping it an enum rather than a free string closes the vocabulary to the three roles a
 * planner, an evaluator or a caller can legitimately send.
 */
public enum MessageRole {

  /** A system / developer instruction that frames the conversation. */
  SYSTEM,

  /** A previous assistant (model) turn. */
  ASSISTANT,

  /** A user turn. */
  USER
}
