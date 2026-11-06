package com.example.agentflow.toolexecution.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The input to the {@link ReportFormatter} (S08-06): the output {@link ReportFormat} and the
 * structured facts to render.
 *
 * <p>It is an immutable value object with value equality. It tolerates the "empty" shapes that the
 * formatter treats as a stable, empty report: a {@code null} facts list is normalised to an empty
 * list at construction (so empty and null inputs yield the same output). {@code null} entries
 * inside the facts list are preserved (the formatter skips them), which keeps the schema stable for
 * partially-malformed input too.
 *
 * @param format the output format to render into (may be {@code null}; the formatter defaults it to
 *     {@link ReportFormat#MARKDOWN})
 * @param facts the structured facts to render (may be {@code null}; normalised to an empty list)
 */
public record ReportRequest(ReportFormat format, List<StructuredFact> facts) {

  /**
   * @param format the output format (may be {@code null})
   * @param facts the structured facts to render (may be {@code null})
   * @return a validated, immutable {@link ReportRequest}
   */
  public ReportRequest {
    List<StructuredFact> copy =
        facts == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(facts));
    facts = copy;
  }

  /**
   * @param format the output format (may be {@code null})
   * @param facts the structured facts to render (never {@code null})
   * @return a {@link ReportRequest}
   */
  public static ReportRequest of(ReportFormat format, List<StructuredFact> facts) {
    return new ReportRequest(format, facts);
  }

  /**
   * @param facts the structured facts to render
   * @return a {@link ReportRequest} that renders into {@link ReportFormat#MARKDOWN}
   */
  public static ReportRequest markdown(List<StructuredFact> facts) {
    return new ReportRequest(ReportFormat.MARKDOWN, facts);
  }

  /**
   * @param facts the structured facts to render
   * @return a {@link ReportRequest} that renders into {@link ReportFormat#JSON}
   */
  public static ReportRequest json(List<StructuredFact> facts) {
    return new ReportRequest(ReportFormat.JSON, facts);
  }
}
