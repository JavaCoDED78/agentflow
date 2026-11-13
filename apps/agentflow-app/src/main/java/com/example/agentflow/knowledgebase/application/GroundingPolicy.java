package com.example.agentflow.knowledgebase.application;

import java.util.List;
import java.util.UUID;

/**
 * The no-evidence policy the {@code knowledgebase} layer reports for an RAG call (S10-18): decide,
 * from the retrieved evidence, whether to {@link GroundedAnswer.Grounded report a grounding} or
 * {@link GroundedAnswer.InsufficientEvidence report an evidence gap}.
 *
 * <p>Pure and side-effect free — no Spring, no database, no I/O — exactly like {@link
 * CitationBuilder.cite} / {@link RankFusion}, so it is unit-testable in isolation and never part of
 * the retrieval transaction. It owns only the *reporting* decision for an empty (or below-bar)
 * {@link List} of citations: {@link RetrievalService#hybridSearch} / {@code #citations} decide
 * whether the list is non-empty. The {@code citations} the policy reads are already tenant- and
 * collection-scoped (invariant 1 — each one resolves to a chunk the caller can see), so the policy
 * never invents a source: an empty list is {@code INSUFFICIENT_EVIDENCE}, never a fabricated {@code
 * Grounded}.
 *
 * <p>The {@code minimum-citations} bar keeps the policy honest when a single weakly-relevant chunk
 * is returned: {@code 0} means "any <b>non-empty</b> retrieval grounds the answer" (an empty
 * retrieval is an evidence gap), any value {@code >= 1} means "the retrieval must return at least
 * that many chunks". Both are meaningful policy knobs ({@code 0} for a strict "no retrieval ⇒ no
 * evidence" stance; a higher bar for "one chunk is not enough evidence"); {@code minimum-citations}
 * below zero is a contract failure. The effective bar is {@code max(1, minimum-citations)} so an
 * empty retrieval is always an evidence gap (see {@link #decide}).
 */
public final class GroundingPolicy {

  /** The policy the glossary §8 fixes: {@code 0} — any non-empty evidence grounds the answer. */
  public static final GroundingPolicy DEFAULT = new GroundingPolicy(0);

  private final int minimumCitations;

  /**
   * Canonical constructor.
   *
   * @param minimumCitations the fewest citations the retrieval must return before the answer is
   *     grounded; {@code 0} means "any non-empty retrieval grounds it" (an empty retrieval is an
   *     evidence gap), a value {@code >= 1} requires the retrieval to return at least that many
   *     chunks
   * @throws IllegalArgumentException if {@code minimumCitations} is negative
   */
  public GroundingPolicy(int minimumCitations) {
    if (minimumCitations < 0) {
      throw new IllegalArgumentException("minimumCitations must not be negative");
    }
    this.minimumCitations = minimumCitations;
  }

  /**
   * @return the fewest citations the retrieval must return before the answer is grounded; {@code 0}
   *     means "any non-empty retrieval grounds it", a value {@code >= 1} requires that many chunks
   *     (the effective bar is {@code max(1, this)}, so an empty retrieval never grounds)
   */
  public int minimumCitations() {
    return minimumCitations;
  }

  /**
   * Decide what to report for the retrieved evidence.
   *
   * <p>The bar is {@code Math.max(1, minimumCitations)}: an empty retrieval (size {@code 0}) is
   * never a grounding (a {@code Grounded} with no citations would let the model answer with no
   * source), so {@code 0} means "any non-empty retrieval grounds" and any value {@code >= 1} means
   * "that many or more are required".
   *
   * @param citations the real citations the retrieval returned (empty when nothing was relevant)
   * @param searchId the id of the search the answer belonged to (correlation only)
   * @return {@code Grounded(citations)} when the policy found evidence to ground in, otherwise
   *     {@code InsufficientEvidence(searchId)} — never a fabricated citation for an empty result
   * @throws IllegalArgumentException if {@code citations} is {@code null} or {@code searchId} is
   *     {@code null}
   */
  public GroundedAnswer decide(List<CitationDto> citations, UUID searchId) {
    if (citations == null) {
      throw new IllegalArgumentException("citations must not be null");
    }
    if (searchId == null) {
      throw new IllegalArgumentException("searchId must not be null");
    }
    int bar = Math.max(1, minimumCitations);
    return citations.size() >= bar
        ? new GroundedAnswer.Grounded(citations)
        : new GroundedAnswer.InsufficientEvidence(searchId);
  }
}
