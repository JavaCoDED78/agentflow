package com.example.agentflow.ingestion.application.embed;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * The embedding generation service of the ingestion pipeline (S10-12): it turns a chunk set into
 * embeddings by fanning the chunk texts out to the {@link EmbeddingPort} in bounded batches and
 * validating every provider response before it reaches the caller.
 *
 * <p>This is the <em>embed</em> half of the extract → chunk → embed → index pipeline the module
 * owns (the indexing half that persists the vectors is {@code S10-13}). The service itself holds no
 * provider knowledge: it builds the neutral {@link EmbeddingRequest}, and everything
 * provider-specific — the SDK, the rate-limit and retry behaviour, the timeouts — stays behind the
 * {@code ai} port ({@code ai.provider.ResilientEmbeddingModel}, {@code ADR-014}). The two layers
 * meet at exactly one injected {@link EmbeddingPort}.
 *
 * <p><b>Batching.</b> The texts are sent in consecutive batches of at most {@link #batchSize()},
 * one {@link EmbeddingPort#embed(EmbeddingRequest)} call per batch. The batch size is configuration
 * ({@code agentflow.ingestion.embedding.batch-size}), never a magic number; a misconfigured value
 * fails the service construction. Batching never reorders anything: the result vectors are
 * concatenated in the exact input order, so the vector at result position {@code i} always belongs
 * to the text at input position {@code i} — the caller pairs a vector with its chunk by position,
 * never by content.
 *
 * <p><b>Validation.</b> Every batch response is checked against the port contract before it is
 * trusted: one vector per input text, no {@code null} or empty vector, and one uniform embedding
 * width across every batch of the call. A provider that breaks the contract fails the run with
 * {@link IllegalStateException} — a loud, permanent fault, never a silently truncated or
 * mis-aligned result persisted into the vector column.
 *
 * <p>Token accounting ({@link EmbeddingBatchResult#tokens()}) is aggregated across the batch calls
 * so the {@code usage} module can meter the embedding pass the way it meters chat calls; a batch
 * that reports no usage contributes zero, never an invented estimate.
 *
 * <p>The service is pure orchestration: no Spring annotations, no time, no randomness and no I/O of
 * its own (invariant 10) — the port seam makes every outcome reproducible in a DB-free, model-free
 * unit test, which is the S10-12 acceptance (the pipeline verified without a real model).
 */
public final class EmbeddingGenerationService {

  private final EmbeddingPort embeddingPort;
  private final int batchSize;

  /**
   * @param embeddingPort the embedding port every batch is sent through; the resilience layer
   *     (retry, rate limit, timeout) lives behind it, in the {@code ai} module. {@code null} when
   *     no embedding provider is configured — the port is opt-in ({@code ADR-014}) — in which case
   *     {@link #embed(String, List)} fails loudly at call time instead of the app failing to boot
   *     (the {@code PlannerService} / {@code ChatModelPort} idiom)
   * @param batchSize the maximum number of texts per {@link EmbeddingPort} call; at least 1
   * @throws IllegalArgumentException if {@code batchSize} is below 1
   */
  public EmbeddingGenerationService(EmbeddingPort embeddingPort, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1 but was " + batchSize);
    }
    this.embeddingPort = embeddingPort;
    this.batchSize = batchSize;
  }

  /**
   * @return the maximum number of texts sent per {@link EmbeddingPort} call
   */
  public int batchSize() {
    return batchSize;
  }

  /**
   * Embed every text of the input, in order, fanning out to the port in batches of at most {@link
   * #batchSize()} texts.
   *
   * @param model the embedding model id to call; must not be {@code null} or blank (the caller —
   *     the indexing worker — pins it from the knowledge base, {@code
   *     knowledge_bases.embedding_model})
   * @param texts the chunk texts to embed; must not be {@code null} or empty; every entry must not
   *     be {@code null}
   * @return one vector per input text in the exact input order, plus the aggregated token
   *     accounting and the uniform embedding width
   * @throws IllegalArgumentException if {@code model} or {@code texts} violates the entry contract
   * @throws IllegalStateException if no {@link EmbeddingPort} is configured (the provider is
   *     opt-in, {@code ADR-014}) or if a provider response breaks the port contract (wrong vector
   *     count, a {@code null} or empty vector, or a non-uniform embedding width)
   * @throws RuntimeException a provider fault propagated from the port after its resilience layer
   *     gave up; the run fails as a whole — no partial result is returned
   */
  public EmbeddingBatchResult embed(String model, List<String> texts) {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model must not be null or blank");
    }
    if (texts == null || texts.isEmpty()) {
      throw new IllegalArgumentException("texts must not be null or empty");
    }
    for (String text : texts) {
      if (text == null) {
        throw new IllegalArgumentException("texts must not contain null entries");
      }
    }
    if (embeddingPort == null) {
      throw new IllegalStateException(
          "no EmbeddingPort is configured: the embedding provider is opt-in (ADR-014),"
              + " so embedding requires a provider profile (for example the fake provider of the"
              + " test sources or a concrete provider adapter)");
    }

    List<float[]> vectors = new ArrayList<>(texts.size());
    long tokens = 0L;
    int dimensions = -1;
    int offset = 0;
    while (offset < texts.size()) {
      int to = Math.min(offset + batchSize, texts.size());
      List<String> batch = texts.subList(offset, to);
      EmbeddingResponse response =
          embeddingPort.embed(new EmbeddingRequest(model, new ArrayList<>(batch)));
      List<float[]> batchVectors = response.embeddings();
      if (batchVectors.size() != batch.size()) {
        throw new IllegalStateException(
            "provider returned "
                + batchVectors.size()
                + " embeddings for a "
                + batch.size()
                + "-text batch");
      }
      for (float[] vector : batchVectors) {
        if (vector == null || vector.length == 0) {
          throw new IllegalStateException(
              "provider returned a null or empty embedding for a text of batch at offset "
                  + offset);
        }
        if (dimensions == -1) {
          dimensions = vector.length;
        } else if (vector.length != dimensions) {
          throw new IllegalStateException(
              "provider returned a non-uniform embedding width: expected "
                  + dimensions
                  + " but found "
                  + vector.length);
        }
        vectors.add(vector);
      }
      if (response.usage() != null) {
        tokens += response.usage().tokens();
      }
      offset = to;
    }
    return new EmbeddingBatchResult(vectors, tokens, dimensions);
  }
}
