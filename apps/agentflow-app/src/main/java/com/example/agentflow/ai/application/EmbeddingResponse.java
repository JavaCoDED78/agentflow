package com.example.agentflow.ai.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The result of an {@link EmbeddingPort#embed(EmbeddingRequest)} call.
 *
 * <p>Provider-agnostic: each {@link #embeddings()} entry is a plain {@code float[]} (the type
 * pgvector stores), so the domain never sees a provider's vector object. Each array is copied on
 * the way out (see {@link #embeddings()}) so the holder owns its data and a caller cannot corrupt a
 * stored embedding by mutating the returned array.
 *
 * @param model the embedding model id that produced the vectors
 * @param embeddings one vector per input text, in order; stored immutably, arrays copied out
 * @param usage optional token / dimension accounting; may be {@code null}
 */
public record EmbeddingResponse(String model, List<float[]> embeddings, EmbeddingUsage usage) {

  /**
   * Canonical constructor.
   *
   * @param model the embedding model id; must not be {@code null} or blank
   * @param embeddings the vectors, one per input text; must not be {@code null} or empty
   * @param usage optional token / dimension accounting; may be {@code null}
   * @throws NullPointerException if {@code model} or {@code embeddings} is {@code null}
   * @throws IllegalArgumentException if {@code model} is blank or {@code embeddings} is empty
   */
  public EmbeddingResponse {
    Objects.requireNonNull(model, "model must not be null");
    if (model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    Objects.requireNonNull(embeddings, "embeddings must not be null");
    if (embeddings.isEmpty()) {
      throw new IllegalArgumentException("embeddings must not be empty");
    }
  }

  /**
   * @return a new list of <em>cloned</em> vectors; each returned {@code float[]} is independent of
   *     the backing storage, so mutating it never affects a previously returned vector or the
   *     holder
   */
  @Override
  public List<float[]> embeddings() {
    List<float[]> copy = new ArrayList<>(embeddings.size());
    for (float[] embedding : embeddings) {
      copy.add(embedding.clone());
    }
    return copy;
  }
}
