package com.example.agentflow.ai.application;

/**
 * The outbound port for embedding calls (S09-01).
 *
 * <p>The single boundary between the {@code ai} domain and the concrete provider SDK for embedding
 * generation. The {@code ingestion} and {@code knowledgebase} modules build a neutral {@link
 * EmbeddingRequest} and call {@link #embed(EmbeddingRequest)}; the provider-specific adapter is the
 * only code that knows the SDK and imports its embedding DTO. Like {@link ChatModelPort}, it is
 * intentionally narrow (one {@link #embed(EmbeddingRequest)}), not a generic abstraction.
 *
 * <p>It is a separate port from {@link ChatModelPort} because embedding and text generation have
 * different shapes and lifecycles, matching the two ports documented in {@code
 * docs/architecture/module-api.md}.
 */
public interface EmbeddingPort {

  /**
   * Produce one vector per input text, in the same order.
   *
   * @param request the request to send
   * @return the vectors; {@link EmbeddingResponse#embeddings()} entries are independent copies
   * @throws IllegalArgumentException if the request is invalid (a contract-level, permanent fault)
   */
  EmbeddingResponse embed(EmbeddingRequest request);
}
