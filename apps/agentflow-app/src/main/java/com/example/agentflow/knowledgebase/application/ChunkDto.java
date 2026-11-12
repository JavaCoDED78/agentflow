package com.example.agentflow.knowledgebase.application;

import java.util.UUID;

/**
 * One retrieved chunk of a tenant/ACL-filtered vector search (S10-14) — the public result element
 * of {@link RetrievalService#search}.
 *
 * <p>Carries the full (document, chunk) binding the glossary §7 fixes for grounding and the future
 * citation ({@code S10-17}): which document the fragment belongs to, which pages it spans, the
 * fragment text and the cosine {@link #score()} it was retrieved at. The {@link #content()} is
 * <b>untrusted document text</b> (invariant 7 — prompt-injection surface): the caller feeds it to a
 * model only as {@code USER} content, and it is never logged, hashed or otherwise written to logs,
 * metrics or traces (invariant 8 — "redacted" from every observability surface, not from the
 * retrieval payload itself).
 *
 * @param id the chunk id (the persisted {@code document_chunks.id})
 * @param documentId the parent document (FK {@code documents.id})
 * @param pageFrom the first page the fragment spans (>= 1)
 * @param pageTo the last page the fragment spans (>= {@code pageFrom})
 * @param score the cosine distance ({@code embedding <=> query}) the chunk was returned at: {@code
 *     0} = identical to the query vector, lower is closer; only chunks within the configured
 *     relevance threshold are returned
 * @param content the fragment text (untrusted content — never logged)
 */
public record ChunkDto(
    UUID id, UUID documentId, int pageFrom, int pageTo, double score, String content) {

  /**
   * Canonical constructor.
   *
   * @param id the chunk id; must not be {@code null}
   * @param documentId the parent document id; must not be {@code null}
   * @param pageFrom the first page the fragment spans
   * @param pageTo the last page the fragment spans
   * @param score the cosine distance (>= 0)
   * @param content the fragment text; must not be {@code null}
   * @throws IllegalArgumentException if an id or the content is {@code null}, or {@code score} is
   *     negative or not finite
   */
  public ChunkDto {
    if (id == null) {
      throw new IllegalArgumentException("id must not be null");
    }
    if (documentId == null) {
      throw new IllegalArgumentException("documentId must not be null");
    }
    if (content == null) {
      throw new IllegalArgumentException("content must not be null");
    }
    if (score < 0 || Double.isNaN(score) || Double.isInfinite(score)) {
      throw new IllegalArgumentException("score must be a finite value >= 0, was " + score);
    }
  }
}
