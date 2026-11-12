package com.example.agentflow.knowledgebase.application;

/**
 * One chunk ready to be persisted to {@code document_chunks} (S10-13), crossing the {@code
 * knowledgebase} public application boundary from the {@code ingestion} indexing path.
 *
 * <p>Carries exactly the columns the row owns beyond the tenant/document/pipeline binding (which
 * the caller passes separately): the position, page range, the fragment text and its SHA-256
 * fingerprint, and the {@code nomic-embed-text} embedding vector. The embedding array is
 * defensively copied on the way in and out, so the holder owns its data.
 *
 * @param chunkIndex the zero-based position within the document's chunk set
 * @param pageFrom the first page the fragment spans (>= 1)
 * @param pageTo the last page the fragment spans (>= {@code pageFrom})
 * @param content the fragment text (never {@code null} or blank)
 * @param contentHash the SHA-256 hex fingerprint of {@code content} (never {@code null} or blank)
 * @param embedding the 768-dimension vector of {@code content} (never {@code null}); copied on
 *     construction and on access
 */
public record ChunkToIndex(
    int chunkIndex,
    int pageFrom,
    int pageTo,
    String content,
    String contentHash,
    float[] embedding) {

  /**
   * Canonical constructor: validates the entry contract and copies the embedding array.
   *
   * @throws IllegalArgumentException if the page range is inverted, the content or hash is blank,
   *     or the embedding is {@code null} or empty
   */
  public ChunkToIndex {
    if (chunkIndex < 0) {
      throw new IllegalArgumentException("chunkIndex must be >= 0, was " + chunkIndex);
    }
    if (pageFrom < 1) {
      throw new IllegalArgumentException("pageFrom must be >= 1, was " + pageFrom);
    }
    if (pageTo < pageFrom) {
      throw new IllegalArgumentException("pageTo (" + pageTo + ") < pageFrom (" + pageFrom + ")");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be null or blank");
    }
    if (contentHash == null || contentHash.isBlank()) {
      throw new IllegalArgumentException("contentHash must not be null or blank");
    }
    if (embedding == null || embedding.length == 0) {
      throw new IllegalArgumentException("embedding must not be null or empty");
    }
    embedding = embedding.clone();
  }

  /**
   * @return a clone of the embedding array, so a caller cannot corrupt the stored vector by
   *     mutating the returned array
   */
  @Override
  public float[] embedding() {
    return embedding.clone();
  }
}
