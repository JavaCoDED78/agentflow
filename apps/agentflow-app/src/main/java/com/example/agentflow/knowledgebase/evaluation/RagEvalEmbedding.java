package com.example.agentflow.knowledgebase.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The deterministic eval embedding of the RAG evaluation corpus (S10-20): a pure, reproducible text
 * → vector function that vectorises both the corpus chunks and the eval queries into the same
 * fixed-width space, so the offline eval needs no model, no API key and no network — the {@code
 * S10-12} fake-provider pattern applied to the retrieval eval.
 *
 * <p>It is a <b>hashed bag of words</b>, not a semantic model: every alphanumeric token of the text
 * (lowercased, stopwords removed, length at least 3) adds one unit to one slot of a {@link
 * #DIMENSIONS}-wide vector chosen by {@link String#hashCode} of the token, and the vector is
 * L2-normalised. Two texts that share content words land close together, unrelated texts land at
 * (or near) the maximum cosine distance {@code 1.0} — exactly the separation a recall@k corpus
 * needs. Same text always yields the same vector (a pure function of the text, no clock, no
 * randomness, invariant 10); the width is pinned to {@code 768} because the production repository
 * ({@code DocumentChunkRepository#findNearest}) refuses any other query width.
 *
 * <p>This is fixture infrastructure of the eval, never a production embedding provider: the corpus
 * documents are synthetic and the harness wires it through the neutral {@code ai} {@code
 * EmbeddingPort} in the DB-free test only.
 */
public final class RagEvalEmbedding {

  /**
   * The vector width — pinned to {@code 768} (the {@code vector(768)} the S10-11 migration stores)
   * so the vectors pass the production repository's width guard.
   */
  public static final int DIMENSIONS = 768;

  /** The small English stopword set both chunk and query texts are tokenised with. */
  private static final Set<String> STOPWORDS =
      Set.of(
          "the", "and", "for", "with", "are", "was", "were", "what", "how", "when", "which",
          "where", "does", "did", "into", "from", "any", "all", "not", "can", "must", "than",
          "that", "this", "these", "those", "has", "have", "had");

  private RagEvalEmbedding() {
    // pure static utility — never instantiated
  }

  /**
   * Vectorise one text into the shared eval space.
   *
   * @param text the text to vectorise; must not be {@code null}
   * @return the L2-normalised {@link #DIMENSIONS}-wide vector; never {@code null}
   * @throws IllegalArgumentException if {@code text} is {@code null}
   */
  public static float[] of(String text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    float[] vector = new float[DIMENSIONS];
    for (String token : tokens(text)) {
      vector[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1.0f;
    }
    normalise(vector);
    return vector;
  }

  /**
   * The cosine distance between two vectors of this space: {@code 1 - cosine}, so {@code 0} means
   * identical and two texts sharing no content word sit at (or numerically next to) {@code 1.0} —
   * the property the no-evidence eval case relies on.
   *
   * @param a the first vector; must not be {@code null}
   * @param b the second vector; must not be {@code null}
   * @return the cosine distance in {@code [0.0, 2.0]}; {@code 1.0} when either vector is zero
   * @throws IllegalArgumentException if an argument is {@code null}
   */
  public static double cosineDistance(float[] a, float[] b) {
    if (a == null || b == null) {
      throw new IllegalArgumentException("vectors must not be null");
    }
    if (a.length != b.length) {
      throw new IllegalArgumentException(
          "vectors must have the same width, were " + a.length + " and " + b.length);
    }
    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    if (normA == 0.0 || normB == 0.0) {
      return 1.0;
    }
    return 1.0 - dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  /**
   * Split one text into its content tokens: lowercased alphanumeric runs of at least three
   * characters with the stopwords removed. The same tokenisation drives the query vectors, the
   * chunk vectors (via {@link #of}) and the in-memory lexical half of the eval wiring, so all three
   * never disagree.
   *
   * @param text the text to tokenise; must not be {@code null}
   * @return the content tokens, in encounter order; never {@code null}
   * @throws IllegalArgumentException if {@code text} is {@code null}
   */
  public static List<String> tokens(String text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
        .filter(token -> token.length() >= 3)
        .filter(token -> !STOPWORDS.contains(token))
        .toList();
  }

  private static void normalise(float[] vector) {
    double norm = 0.0;
    for (float value : vector) {
      norm += (double) value * value;
    }
    norm = Math.sqrt(norm);
    if (norm == 0.0) {
      return;
    }
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) (vector[i] / norm);
    }
  }
}
