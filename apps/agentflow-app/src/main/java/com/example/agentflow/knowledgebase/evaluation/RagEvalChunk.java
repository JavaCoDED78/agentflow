package com.example.agentflow.knowledgebase.evaluation;

import java.util.UUID;

/**
 * One indexed chunk of the RAG evaluation corpus (S10-20): the binding a retrieval result (and the
 * citation built from it) must reproduce — which tenant and document the fragment belongs to and
 * which pages it spans — plus the fragment text the deterministic {@link RagEvalEmbedding}
 * vectorises.
 *
 * @param id the chunk id (a labelled fixture id)
 * @param tenantId the owning tenant — the eval fixture tenant for corpus chunks, the foreign
 *     fixture tenant for the isolation decoy
 * @param documentId the parent document id
 * @param documentName the parent document's display name
 * @param pageFrom the first page the fragment spans (at least 1)
 * @param pageTo the last page the fragment spans (at least {@code pageFrom})
 * @param content the fragment text (synthetic eval content, never logged)
 */
public record RagEvalChunk(
    UUID id,
    UUID tenantId,
    UUID documentId,
    String documentName,
    int pageFrom,
    int pageTo,
    String content) {

  /**
   * Canonical constructor.
   *
   * @throws IllegalArgumentException if an id, the name or the content is {@code null}, or the page
   *     range is invalid
   */
  public RagEvalChunk {
    if (id == null) {
      throw new IllegalArgumentException("id must not be null");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must not be null");
    }
    if (documentId == null) {
      throw new IllegalArgumentException("documentId must not be null");
    }
    if (documentName == null || documentName.isBlank()) {
      throw new IllegalArgumentException("documentName must not be null or blank");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be null or blank");
    }
    if (pageFrom < 1) {
      throw new IllegalArgumentException("pageFrom must be at least 1, was " + pageFrom);
    }
    if (pageTo < pageFrom) {
      throw new IllegalArgumentException(
          "pageTo must be >= pageFrom (" + pageFrom + "), was " + pageTo);
    }
  }

  /**
   * @return this fragment's deterministic embedding, computed on demand (pure, cheap, reproducible)
   */
  public float[] vector() {
    return RagEvalEmbedding.of(content);
  }
}
