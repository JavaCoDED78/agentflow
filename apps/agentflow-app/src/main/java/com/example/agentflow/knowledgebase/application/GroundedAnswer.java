package com.example.agentflow.knowledgebase.application;

import java.util.List;
import java.util.UUID;

/**
 * The no-evidence decision the {@code knowledgebase} layer reports for an RAG call (S10-18): either
 * one {@link Grounded} to the real retrieved chunks (the {@code citations} the retrieval returned,
 * each resolving to a fragment the caller can see — invariant 1) or one {@link
 * InsufficientEvidence} when there is nothing to ground in.
 *
 * <p>It is the {@code knowledgebase} answer to the question "what do I report when retrieval found
 * nothing relevant?" — not "did retrieval find anything?" An empty retrieval is reported here; the
 * {@link RetrievalService#hybridSearch} / {@code #search} / {@code #citations} steps only decide
 * whether the list is empty. The caller feeds the {@code grounded.citations} to the model as {@code
 * USER} content (invariant 7 — document fragments are untrusted text) and never invents a source
 * (invariant 1 / 7).
 *
 * <p>Sealed on purpose: the only ways to report a grounding are a grounding and an evidence gap, so
 * a consumer is exhaustive without a {@code default} branch and cannot invent a third outcome (e.g.
 * a fabricated citation for an empty result).
 *
 * @param citations the real citations grounding the model's answer (empty when {@link
 *     GroundingPolicy#decide} returned {@code INSUFFICIENT_EVIDENCE}); never invented — an empty
 *     retrieval yields an empty list, which this type reports as {@link InsufficientEvidence}
 */
public sealed interface GroundedAnswer
    permits GroundedAnswer.Grounded, GroundedAnswer.InsufficientEvidence {

  /**
   * The grounding reported when the policy found evidence: the {@code citations} the retrieval
   * returned, each resolving to a chunk the caller can see.
   *
   * @param citations the real citations (in fused/retrieved order); empty only when the policy
   *     returned {@code INSUFFICIENT_EVIDENCE}, which never happens for a {@code Grounded}
   */
  record Grounded(List<CitationDto> citations) implements GroundedAnswer {
    /**
     * Canonical constructor.
     *
     * @param citations the real citations; must not be {@code null} (an empty list is a legitimate
     *     "grounded in nothing" answer the policy, not this constructor, reports)
     * @throws IllegalArgumentException if {@code citations} is {@code null}
     */
    public Grounded {
      if (citations == null) {
        throw new IllegalArgumentException("citations must not be null");
      }
      citations = List.copyOf(citations);
    }
  }

  /**
   * The evidence gap reported when the policy found nothing to ground in (an empty retrieval, or a
   * retrieval below the {@code minimum-citations} bar): the model must answer "I have no relevant
   * source" and never invent a citation.
   *
   * <p>The {@code searchId} correlates the gap with the {@code SearchRequest} that produced it, so
   * an operator (or an audit probe) can tell which query the caller answered "no evidence" for,
   * without storing the query text.
   *
   * @param searchId the id of the search the retrieval (and therefore the answer) belonged to;
   *     correlates the gap with the request that produced it
   * @throws IllegalArgumentException if {@code searchId} is {@code null}
   */
  record InsufficientEvidence(UUID searchId) implements GroundedAnswer {
    /**
     * Canonical constructor.
     *
     * @param searchId the id of the search the answer belonged to (correlation only); must not be
     *     {@code null}
     * @throws IllegalArgumentException if {@code searchId} is {@code null}
     */
    public InsufficientEvidence {
      if (searchId == null) {
        throw new IllegalArgumentException("searchId must not be null");
      }
    }
  }
}
