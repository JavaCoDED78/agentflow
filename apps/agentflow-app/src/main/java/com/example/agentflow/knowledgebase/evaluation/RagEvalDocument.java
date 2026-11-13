package com.example.agentflow.knowledgebase.evaluation;

import java.util.UUID;

/**
 * One sample document of the RAG evaluation corpus (S10-20) — synthetic, authored for the eval (no
 * third-party text, no personal data), the unit the fixed questions reference their pages against.
 *
 * @param id the document id (a labelled fixture id, never a real uploaded document)
 * @param name the document's display name (the safe label a citation renders)
 * @param pageCount the number of pages the document spans (at least 1)
 */
public record RagEvalDocument(UUID id, String name, int pageCount) {

  /**
   * Canonical constructor.
   *
   * @throws IllegalArgumentException if an id or the name is {@code null} / blank, or {@code
   *     pageCount} is below 1
   */
  public RagEvalDocument {
    if (id == null) {
      throw new IllegalArgumentException("id must not be null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be null or blank");
    }
    if (pageCount < 1) {
      throw new IllegalArgumentException("pageCount must be at least 1, was " + pageCount);
    }
  }
}
