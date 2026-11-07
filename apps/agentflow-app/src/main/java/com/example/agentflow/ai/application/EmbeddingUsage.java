package com.example.agentflow.ai.application;

/**
 * Token / dimension accounting for an {@link EmbeddingResponse}.
 *
 * <p>Carried so the {@code usage} module can meter embedding calls the same way it meters chat
 * calls. The {@link #dimensions()} is a stable, provider-declared property reused when storing the
 * vector in pgvector (S10).
 *
 * @param tokens the tokens consumed by the embedding call (or an estimate the provider did not
 *     report)
 * @param dimensions the embedding vector dimension the model produces
 */
public record EmbeddingUsage(long tokens, int dimensions) {}
