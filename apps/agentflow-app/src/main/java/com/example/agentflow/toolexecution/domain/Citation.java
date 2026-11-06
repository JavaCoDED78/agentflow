package com.example.agentflow.toolexecution.domain;

import java.util.Objects;

/**
 * A single source a {@link StructuredFact} is attributed to (S08-06).
 *
 * <p>A citation is the machine-readable counterpart of a RAG citation: it names the source a fact
 * came from ({@link #source()}) and carries an optional reference to it ({@link #url()}, which may
 * be {@code null} when there is none). It is an immutable value object with value equality.
 *
 * <p>Like every value the report formatter handles, a citation is <em>untrusted content</em>: its
 * {@link #source()} and {@link #url()} are HTML-escaped by the formatter before they reach the
 * output, so a citation can never inject executable markup into a rendered report.
 *
 * @param source the source the fact is attributed to (never {@code null}; a blank source is treated
 *     as empty by the formatter)
 * @param url an optional reference to the source (may be {@code null})
 */
public record Citation(String source, String url) {

  /**
   * @param source the source the fact is attributed to
   * @param url an optional reference to the source (may be {@code null})
   * @return a validated, immutable {@link Citation}
   * @throws NullPointerException if {@code source} is {@code null}
   */
  public Citation {
    Objects.requireNonNull(source, "source must not be null");
  }

  /**
   * @param source the source the fact is attributed to
   * @param url an optional reference to the source (may be {@code null})
   * @return a {@link Citation}, or {@code null} if {@code source} is {@code null} (an application
   *     helper for callers that already tolerate an absent citation)
   */
  public static Citation of(String source, String url) {
    return new Citation(source, url);
  }
}
