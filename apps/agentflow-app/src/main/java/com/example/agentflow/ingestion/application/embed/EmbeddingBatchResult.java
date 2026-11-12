package com.example.agentflow.ingestion.application.embed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The aggregated result of one {@link EmbeddingGenerationService} run over a chunk set (S10-12).
 *
 * <p>One embedding generation call embeds every text of the input in a single logical operation,
 * even though the service fans out to the provider in batches. This record is the single outcome
 * the caller (the indexing worker, {@code S10-13}) consumes: the vectors in the exact input order —
 * batch boundaries are an implementation detail and never visible here — plus the token accounting
 * aggregated across every batch call. The dimension is validated to be uniform across all vectors,
 * so a caller can persist the result against a fixed-width vector column without re-checking each
 * vector.
 *
 * @param vectors one embedding per input text, in the exact input order; every array is an
 *     independent copy
 * @param tokens the tokens consumed across every batch call (0 when the provider reported no usage)
 * @param dimensions the uniform embedding width; always {@code >= 1}
 */
public record EmbeddingBatchResult(List<float[]> vectors, long tokens, int dimensions) {

  /**
   * Canonical constructor.
   *
   * @param vectors one embedding per input text, in order; must not be {@code null} or empty
   * @param tokens the tokens consumed across the batch calls; never negative
   * @param dimensions the uniform embedding width; at least 1
   * @throws NullPointerException if {@code vectors} is {@code null}
   * @throws IllegalArgumentException if {@code vectors} is empty, any entry is {@code null} or
   *     empty, the widths are not uniform, {@code tokens} is negative or {@code dimensions} is
   *     below 1
   */
  public EmbeddingBatchResult {
    Objects.requireNonNull(vectors, "vectors must not be null");
    if (vectors.isEmpty()) {
      throw new IllegalArgumentException("vectors must not be empty");
    }
    if (tokens < 0) {
      throw new IllegalArgumentException("tokens must not be negative");
    }
    if (dimensions < 1) {
      throw new IllegalArgumentException("dimensions must be >= 1 but was " + dimensions);
    }
    for (float[] vector : vectors) {
      if (vector == null || vector.length == 0) {
        throw new IllegalArgumentException("vectors must not contain null or empty arrays");
      }
      if (vector.length != dimensions) {
        throw new IllegalArgumentException(
            "vectors must be uniform "
                + dimensions
                + "-dimensional arrays but found a "
                + vector.length
                + "-dimensional one");
      }
    }
    vectors = deepCopy(vectors);
  }

  /**
   * @return a new list of cloned vectors; each returned {@code float[]} is independent of the
   *     backing storage and of every previously returned copy
   */
  @Override
  public List<float[]> vectors() {
    return deepCopy(vectors);
  }

  private static List<float[]> deepCopy(List<float[]> source) {
    List<float[]> copy = new ArrayList<>(source.size());
    for (float[] vector : source) {
      copy.add(vector == null ? null : vector.clone());
    }
    return copy;
  }
}
