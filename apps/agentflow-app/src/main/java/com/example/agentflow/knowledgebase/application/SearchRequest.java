package com.example.agentflow.knowledgebase.application;

/**
 * A tenant/ACL-filtered vector search request (S10-14), the public entry value of {@link
 * RetrievalService#search}.
 *
 * <p>The {@link #query()} is the natural-language question embedded and matched against the
 * collection's stored vectors; it is <b>untrusted content</b> (invariant 7) — retrieval never
 * treats it as a policy, a filter source or anything but the text to vectorise. The {@link #topK()}
 * is the caller's desired result size, {@code null} meaning "the configured default"; it is a
 * bound, never a privilege — the service clamps it to the configured maximum.
 *
 * <p>The tenant and the knowledge base are deliberately <em>not</em> part of this value: they are
 * authorization context supplied by the verified security context and the request path, never by
 * the caller's body (invariant 1 / invariant 11), and the service takes them as separate arguments.
 *
 * @param query the natural-language query text to embed and search with; non-null, non-blank
 * @param topK the desired maximum number of chunks, or {@code null} for the configured default;
 *     when non-null, must be at least 1 (the service clamps it to the configured maximum)
 */
public record SearchRequest(String query, Integer topK) {

  /**
   * Canonical constructor.
   *
   * @param query the query text; must not be {@code null} or blank
   * @param topK the desired result size, or {@code null} for the configured default; when non-null,
   *     must be at least 1
   * @throws IllegalArgumentException if {@code query} is {@code null} or blank, or {@code topK} is
   *     not {@code null} and below 1
   */
  public SearchRequest {
    if (query == null) {
      throw new IllegalArgumentException("query must not be null");
    }
    if (query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    if (topK != null && topK < 1) {
      throw new IllegalArgumentException("topK must be null or at least 1, was " + topK);
    }
  }
}
