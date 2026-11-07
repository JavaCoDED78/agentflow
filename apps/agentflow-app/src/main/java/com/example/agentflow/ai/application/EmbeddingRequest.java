package com.example.agentflow.ai.application;

import java.util.List;
import java.util.Objects;

/**
 * An embedding-generation request to the {@link EmbeddingPort}.
 *
 * <p>The neutral contract the {@code ingestion} / {@code knowledgebase} modules build (S10) and the
 * provider-specific adapter consumes; it never references a provider's embedding request DTO. The
 * {@link #model()} and {@link #texts()} are mandatory and validated up front, and {@link #texts()}
 * is returned as an unmodifiable snapshot.
 *
 * @param model the embedding model id; non-null, non-blank
 * @param texts the documents / chunks to embed; must not be {@code null} or empty
 */
public record EmbeddingRequest(String model, List<String> texts) {

  /**
   * Canonical constructor.
   *
   * @param model the embedding model id; must not be {@code null} or blank
   * @param texts the texts to embed; must not be {@code null} and must not be empty
   * @throws NullPointerException if {@code model} or {@code texts} is {@code null}
   * @throws IllegalArgumentException if {@code model} is blank or {@code texts} is empty
   */
  public EmbeddingRequest {
    Objects.requireNonNull(model, "model must not be null");
    if (model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    Objects.requireNonNull(texts, "texts must not be null");
    if (texts.isEmpty()) {
      throw new IllegalArgumentException("texts must not be empty");
    }
  }

  /**
   * @return an unmodifiable view of the texts; the backing list is copied so the caller's list can
   *     never be mutated through this accessor
   */
  @Override
  public List<String> texts() {
    return List.copyOf(texts);
  }
}
