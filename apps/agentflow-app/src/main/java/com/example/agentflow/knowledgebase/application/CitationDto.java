package com.example.agentflow.knowledgebase.application;

import java.util.UUID;

/**
 * A grounded reference to a single retrieved chunk, the glossary &sect;8 "Citation" response field
 * set — {@code documentId}, {@code chunkId}, page/section and {@code score} — extended with the
 * document's display name and the fragment text the planner / finalizer renders next to an answer
 * (S10-17).
 *
 * <p>It is built <b>only</b> from retrieved evidence (the glossary rule: only real retrieved chunks
 * are cited). A citation therefore cannot point at another tenant's document: the {@link
 * #chunkId()} it names resolves to the <em>same</em> tenant- and collection-scoped chunk the {@link
 * RetrievalService} returned, and an empty retrieval produces no citation at all (never an invented
 * one — that is S10-18's {@code InsufficientEvidence}).
 *
 * <p>{@link #snippet()} is untrusted document text (invariant 7 — prompt-injection surface): the
 * caller feeds it to a model only as {@code USER} content and it is never logged, hashed or
 * otherwise written to logs, metrics or traces (invariant 8). {@link #documentName()} is the parent
 * document's safe, sanitised display name — trusted metadata, not the snippet.
 *
 * @param documentId the parent document (FK {@code documents.id}) the citation grounds in
 * @param chunkId the retrieved chunk (the persisted {@code document_chunks.id}) the citation
 *     grounds in
 * @param documentName the parent document's {@code filename} (its safe, sanitised display name) —
 *     the label a rendered answer shows
 * @param pageFrom the first page the cited fragment spans (>= 1)
 * @param pageTo the last page the cited fragment spans (>= {@code pageFrom})
 * @param score the relevance the chunk was retrieved at (the score of the half / fusion that
 *     produced the {@code ChunkDto} the citation was built from)
 * @param snippet the fragment text the citation cites (untrusted content — never logged)
 */
public record CitationDto(
    UUID documentId,
    UUID chunkId,
    String documentName,
    int pageFrom,
    int pageTo,
    double score,
    String snippet) {

  /**
   * Canonical constructor.
   *
   * @param documentId the parent document id; must not be {@code null}
   * @param chunkId the retrieved chunk id; must not be {@code null}
   * @param documentName the parent document's display name; must not be {@code null}
   * @param pageFrom the first page the cited fragment spans
   * @param pageTo the last page the cited fragment spans
   * @param score the relevance the chunk was retrieved at (>= 0)
   * @param snippet the fragment text; must not be {@code null}
   * @throws IllegalArgumentException if any id, the display name or the snippet is {@code null}, or
   *     {@code score} is negative or not finite
   */
  public CitationDto {
    if (documentId == null) {
      throw new IllegalArgumentException("documentId must not be null");
    }
    if (chunkId == null) {
      throw new IllegalArgumentException("chunkId must not be null");
    }
    if (documentName == null) {
      throw new IllegalArgumentException("documentName must not be null");
    }
    if (snippet == null) {
      throw new IllegalArgumentException("snippet must not be null");
    }
    if (score < 0 || Double.isNaN(score) || Double.isInfinite(score)) {
      throw new IllegalArgumentException("score must be a finite value >= 0, was " + score);
    }
  }
}
