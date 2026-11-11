package com.example.agentflow.ingestion.domain;

import java.util.Objects;

/**
 * Immutable, validated configuration for the deterministic {@code Chunker} (S10-09).
 *
 * <p>A chunk is one fragment of an extracted document that is embedded and indexed; its persisted
 * shape ({@code document_chunks}) is keyed by {@code (document_id, chunk_index, pipeline_version)}
 * (domain glossary §7), so {@link #pipelineVersion()} is part of that identity: it must change only
 * when a parameter that affects the produced chunks changes, so a retuned configuration invalidates
 * the old chunks on reindex. It is deterministic by construction &mdash; no clock, no randomness
 * &mdash; the same parameters always produce the same label.
 */
public final class ChunkingConfig {

  private final Strategy strategy;
  private final int maxChunkChars;
  private final int overlapChars;

  /**
   * @param strategy the chunking strategy (never {@code null}); the active algorithm
   * @param maxChunkChars the maximum retained characters per chunk (must be &gt; 0); a single word
   *     longer than this still becomes its own chunk, never split
   * @param overlapChars the characters carried forward from the end of one chunk into the next
   *     (must be &ge; 0 and &lt; {@code maxChunkChars}); aligned to a word boundary
   */
  public ChunkingConfig(Strategy strategy, int maxChunkChars, int overlapChars) {
    this.strategy = Objects.requireNonNull(strategy, "strategy");
    if (maxChunkChars <= 0) {
      throw new IllegalArgumentException("maxChunkChars must be > 0, was " + maxChunkChars);
    }
    if (overlapChars < 0) {
      throw new IllegalArgumentException("overlapChars must be >= 0, was " + overlapChars);
    }
    if (overlapChars >= maxChunkChars) {
      throw new IllegalArgumentException(
          "overlapChars (" + overlapChars + ") must be < maxChunkChars (" + maxChunkChars + ")");
    }
    this.maxChunkChars = maxChunkChars;
    this.overlapChars = overlapChars;
  }

  /**
   * @return the active chunking strategy (never {@code null}).
   */
  public Strategy strategy() {
    return strategy;
  }

  /**
   * @return the maximum retained characters per chunk (always &gt; 0).
   */
  public int maxChunkChars() {
    return maxChunkChars;
  }

  /**
   * @return the characters carried forward from one chunk into the next (always &ge; 0 and &lt;
   *     {@link #maxChunkChars()}).
   */
  public int overlapChars() {
    return overlapChars;
  }

  /**
   * A stable, self-describing label for this chunking configuration. It changes only when a
   * parameter that affects the produced chunks changes (strategy, size or overlap), so a persisted
   * {@code pipeline_version} invalidates the old chunks when the configuration is retuned (S10-13 /
   * S10-19). Deterministic by construction &mdash; no clock, no randomness.
   *
   * @return the pipeline version label (never {@code null} or blank).
   */
  public String pipelineVersion() {
    return "chunking:"
        + strategy.name().toLowerCase()
        + ":cs="
        + maxChunkChars
        + ":ov="
        + overlapChars;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ChunkingConfig other)) {
      return false;
    }
    return maxChunkChars == other.maxChunkChars
        && overlapChars == other.overlapChars
        && strategy == other.strategy;
  }

  @Override
  public int hashCode() {
    return Objects.hash(strategy, maxChunkChars, overlapChars);
  }

  @Override
  public String toString() {
    return "ChunkingConfig[strategy="
        + strategy
        + ", maxChunkChars="
        + maxChunkChars
        + ", overlapChars="
        + overlapChars
        + ", pipelineVersion='"
        + pipelineVersion()
        + "'}";
  }
}
