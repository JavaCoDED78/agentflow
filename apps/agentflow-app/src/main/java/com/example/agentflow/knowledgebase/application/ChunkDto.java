package com.example.agentflow.knowledgebase.application;

import java.util.UUID;

/**
 * One retrieved chunk of a tenant/ACL-filtered vector search (S10-14) — the public result element
 * of {@link RetrievalService#search}.
 *
 * <p>Carries the full (document, chunk) binding the glossary §7 fixes for grounding and the future
 * citation ({@code S10-17}): which document the fragment belongs to, which pages it spans, the
 * fragment text and the {@link #score()} it was retrieved at. The {@link #content()} is
 * <b>untrusted document text</b> (invariant 7 — prompt-injection surface): the caller feeds it to a
 * model only as {@code USER} content, and it is never logged, hashed or otherwise written to logs,
 * metrics or traces (invariant 8 — "redacted" from every observability surface, not from the
 * retrieval payload itself. The score is a count-only observability signal, never the text — see
 * {@link RetrievalService#hybridSearch}).
 *
 * <p>{@link #documentName()} is the parent document's {@code filename} (its safe, sanitised display
 * name) the repository resolves from the {@code documents} join — the label the glossary §8 fixes
 * for a citation's document reference. It is populated on every element the service returns
 * (S10-17) and is trusted document metadata (not the untrusted {@code #content()}); it is never
 * logged.
 *
 * <p>{@link #score()} is <b>half-specific</b>: the vector half ({@code search}) fills it with the
 * cosine distance ({@code embedding <=> query}), {@code 0} = identical, lower is closer; the
 * lexical half ({@code lexicalSearch}) fills it with the FTS {@code ts_rank}, higher is closer; the
 * hybrid fusion ({@code hybridSearch}, S10-16) fills it with the reciprocal-rank fused score
 * (higher is better). The direction is therefore not fixed by the type — it is fixed by which
 * retrieval half produced the instance.
 *
 * @param id the chunk id (the persisted {@code document_chunks.id})
 * @param documentId the parent document (FK {@code documents.id})
 * @param documentName the parent document's {@code filename} (its safe, sanitised display name);
 *     resolved by the repository from the {@code documents} join, populated on every returned
 *     element (S10-17)
 * @param pageFrom the first page the fragment spans (>= 1)
 * @param pageTo the last page the fragment spans (>= {@code pageFrom})
 * @param score the retrieval score for the half that produced this instance: the cosine distance
 *     (vector half, lower is closer), the FTS relevance (lexical half, higher is closer), or the
 *     reciprocal-rank fused relevance (hybrid half, higher is better); always a finite value {@code
 *     >= 0}
 * @param content the fragment text (untrusted content — never logged)
 */
public record ChunkDto(
    UUID id,
    UUID documentId,
    String documentName,
    int pageFrom,
    int pageTo,
    double score,
    String content) {

  /**
   * Canonical constructor.
   *
   * @param id the chunk id; must not be {@code null}
   * @param documentId the parent document id; must not be {@code null}
   * @param documentName the parent document's display name; must not be {@code null}
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
    if (documentName == null) {
      throw new IllegalArgumentException("documentName must not be null");
    }
    if (content == null) {
      throw new IllegalArgumentException("content must not be null");
    }
    if (score < 0 || Double.isNaN(score) || Double.isInfinite(score)) {
      throw new IllegalArgumentException("score must be a finite value >= 0, was " + score);
    }
  }
}
