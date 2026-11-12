package com.example.agentflow.knowledgebase.application;

import java.util.List;

/**
 * The result of a tenant/ACL-filtered vector search (S10-14) — the public return value of {@link
 * RetrievalService#search}.
 *
 * <p>Every chunk in {@link #chunks()} belongs to the requesting tenant and the requested knowledge
 * base: the access predicate ran inside the retrieval query, before top-k (invariant 1), so a
 * foreign chunk can never appear, no matter how close its embedding is. An empty {@link #chunks()}
 * means the collection has no chunk close enough to the query — the caller reports insufficient
 * evidence and never invents a source ({@code S10-17} / {@code S10-18} own the citation and
 * no-evidence layers on top of this result).
 *
 * @param chunks the retrieved chunks in ascending cosine-distance order (closest first); stored as
 *     an unmodifiable defensive copy
 */
public record SearchResult(List<ChunkDto> chunks) {

  /** The shared empty result. */
  private static final SearchResult EMPTY = new SearchResult(List.of());

  /**
   * Canonical constructor.
   *
   * @param chunks the retrieved chunks; must not be {@code null} (an empty list is a legitimate
   *     "nothing relevant" answer)
   * @throws IllegalArgumentException if {@code chunks} is {@code null}
   */
  public SearchResult {
    if (chunks == null) {
      throw new IllegalArgumentException("chunks must not be null");
    }
    chunks = List.copyOf(chunks);
  }

  /**
   * @return the shared empty result — a search that found nothing relevant
   */
  public static SearchResult empty() {
    return EMPTY;
  }
}
