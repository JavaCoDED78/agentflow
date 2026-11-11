package com.example.agentflow.ingestion.domain;

/**
 * The chunking strategy the {@code Chunker} applies.
 *
 * <p>Backed by {@code ingestion.application.chunk.Chunker}; the value is recorded on every {@link
 * DocumentChunk} through its {@code pipeline_version}, so a change here invalidates the old chunks
 * when the document is reindexed (see {@code S10-13} / {@code S10-19}).
 */
public enum Strategy {

  /**
   * Largest-possible character budget chunking that respects word boundaries. The text is split
   * into the fewest chunks that stay within {@code maxChunkChars} without breaking a word, and
   * overlapping chunks carry context forward.
   *
   * <p>This is the base strategy chosen for {@code S10-09}. It beats a pure paragraph strategy (a
   * token stream, a single run of prose or a list has no paragraphs to split on) and a pure token
   * strategy (which can produce uneven, size-unbounded chunks) for a size-bounded, natural chunk
   * for both prose and token streams, and it is fully deterministic: the same input and the same
   * configuration always yield the same chunk set, with no clock, no randomness and no map
   * iteration.
   */
  CHARACTER_BOUNDARY
}
