package com.example.agentflow.toolexecution.domain;

import java.util.Collections;
import java.util.List;

/**
 * One structured fact with its citations, as accepted by the {@link ReportFormatter} (S08-06).
 *
 * <p>A fact is a piece of content ({@link #text()}) optionally attributed to one or more {@link
 * Citation}s. It is an immutable value object with value equality.
 *
 * <p>The {@link #text()} is <em>untrusted content</em> (it may come from an uploaded document or an
 * LLM); the formatter HTML-escapes it before it reaches the output, so a fact can never inject
 * executable markup into a rendered report. A {@code null} or blank text is treated as an empty
 * fact by the formatter, which keeps the output schema stable for empty input (S08-06).
 *
 * @param text the fact content (may be {@code null}; the formatter treats it as an empty string)
 * @param citations the sources the fact is attributed to (may be {@code null}; treated as an empty
 *     list)
 */
public record StructuredFact(String text, List<Citation> citations) {

  /**
   * @param text the fact content (may be {@code null})
   * @param citations the sources the fact is attributed to (may be {@code null})
   * @return a validated, immutable {@link StructuredFact}
   */
  public StructuredFact {
    if (text == null) {
      text = "";
    }
    List<Citation> copy = citations == null ? List.of() : List.copyOf(citations);
    citations = Collections.unmodifiableList(copy);
  }

  /**
   * @param text the fact content (may be {@code null})
   * @param citations the sources the fact is attributed to (may be {@code null})
   * @return a validated, immutable {@link StructuredFact}
   */
  public static StructuredFact of(String text, List<Citation> citations) {
    return new StructuredFact(text, citations);
  }
}
