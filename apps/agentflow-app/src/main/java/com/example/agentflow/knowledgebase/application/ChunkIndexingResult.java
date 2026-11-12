package com.example.agentflow.knowledgebase.application;

/**
 * The outcome of one idempotent chunk-indexing pass (S10-13): how many {@code document_chunks} rows
 * were written and how many were left untouched because the {@code (document_id, chunk_index,
 * pipeline_version)} unique key already held them (the redelivery {@code SKIP} policy).
 *
 * @param written the rows inserted or updated by this pass
 * @param skipped the rows left untouched because an identical key already existed under the {@code
 *     SKIP} policy (always {@code 0} under the {@code UPDATE} policy, which upserts in place)
 */
public record ChunkIndexingResult(int written, int skipped) {

  /**
   * Canonical constructor.
   *
   * @param written the rows written (>= 0)
   * @param skipped the rows skipped (>= 0)
   */
  public ChunkIndexingResult {
    if (written < 0) {
      throw new IllegalArgumentException("written must be >= 0, was " + written);
    }
    if (skipped < 0) {
      throw new IllegalArgumentException("skipped must be >= 0, was " + skipped);
    }
  }
}
