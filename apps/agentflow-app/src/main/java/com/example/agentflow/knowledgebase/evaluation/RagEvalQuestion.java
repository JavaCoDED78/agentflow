package com.example.agentflow.knowledgebase.evaluation;

import java.util.Set;
import java.util.UUID;

/**
 * One fixed eval question of the RAG evaluation corpus (S10-20): the query text retrieval runs, the
 * reference chunk ids the answer must be found in (the "эталонные pages/chunks" the plan fixes),
 * and whether the question expects grounding at all — the no-evidence fixture carries an empty
 * reference set and expects {@code InsufficientEvidence}.
 *
 * @param id the stable case id a report line and a diff name (never the query text)
 * @param query the natural-language question, embedded and lexically matched as-is
 * @param referenceChunkIds the chunk ids a correct retrieval must surface (defensive copy); empty
 *     only for a no-evidence case
 * @param expectsEvidence {@code true} when the corpus contains the answer (the case passes on full
 *     recall and correct citations), {@code false} for the off-topic fixture (the case passes when
 *     retrieval reports an evidence gap and invents nothing)
 */
public record RagEvalQuestion(
    String id, String query, Set<UUID> referenceChunkIds, boolean expectsEvidence) {

  /**
   * Canonical constructor.
   *
   * @throws IllegalArgumentException if the id or the query is {@code null} / blank, the references
   *     are {@code null}, or a case that expects evidence names no reference chunk
   */
  public RagEvalQuestion {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be null or blank");
    }
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be null or blank");
    }
    if (referenceChunkIds == null) {
      throw new IllegalArgumentException("referenceChunkIds must not be null");
    }
    if (expectsEvidence && referenceChunkIds.isEmpty()) {
      throw new IllegalArgumentException(
          "a question that expects evidence must name at least one reference chunk");
    }
    referenceChunkIds = Set.copyOf(referenceChunkIds);
  }
}
