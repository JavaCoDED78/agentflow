package com.example.agentflow.toolexecution.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The safe, dependency-free report formatter (S08-06).
 *
 * <p>It closes the concrete {@code report.format} tool: it takes structured {@link StructuredFact}s
 * and their {@link Citation}s and renders them into a {@link ReportFormat#MARKDOWN} or {@link
 * ReportFormat#JSON} report. It is a pure value that reads neither time nor randomness and carries
 * no Spring or I/O dependency (invariant 10), so it is fully testable in isolation, exactly like
 * {@link Calculator} and {@link SchemaValidator}.
 *
 * <p>It honours three guarantees that make its output safe and its schema stable:
 *
 * <ul>
 *   <li><b>No executable HTML.</b> Every dynamic value (fact {@link StructuredFact#text()} and each
 *       {@link Citation#source()}/{@link Citation#url()}) is HTML-escaped before it is written, in
 *       <em>both</em> output formats. A hostile or prompt-injected fact (documents and tool outputs
 *       are untrusted content, invariant 7) can therefore never turn the report into live markup —
 *       {@code <script>} becomes {@code &lt;script&gt;}.
 *   <li><b>A bounded result.</b> The report is capped at {@value #MAX_RESULT_CHARS} characters.
 *       Facts are included <em>whole</em> (never cut mid-fact): the formatter stops before the fact
 *       that would exceed the cap. This keeps a Markdown line and a JSON object well-formed, so the
 *       output shape is stable for empty, valid and oversized inputs (the S08-06 acceptance
 *       criterion), and marks a report {@link ReportResult#truncated()} when facts were dropped.
 *   <li><b>Stable shape for empty input.</b> A {@code null}/{@code blank} fact text is an empty
 *       fact, a {@code null} facts list is empty facts, and a {@code null} report format defaults
 *       to {@link ReportFormat#MARKDOWN} — so none of them throws, and empty/valid/oversized inputs
 *       all produce a report with the same keys.
 * </ul>
 *
 * <p>The {@link #format(ReportRequest)} method is a static entry point, the report-layer
 * counterpart of {@code report.format} in the built-in tool descriptor ({@link
 * BuiltInTools#reportFormatter()}).
 */
public final class ReportFormatter {

  /** The maximum character length of a rendered report ({@code 4000}). */
  private static final int MAX_RESULT_CHARS = 4000;

  /**
   * A per-format reserve, in characters, kept clear of the fact content so the fixed overhead — the
   * Markdown header or the JSON metadata ({@code truncated}/{@code factCount}/{@code facts}), a
   * truncation marker and their separators — keeps the assembled report within {@link
   * #MAX_RESULT_CHARS}. Eighty is comfortably larger than either overhead, so the cap is never
   * breached by metadata.
   */
  private static final int OVERHEAD_CHARS = 80;

  private ReportFormatter() {}

  /**
   * Renders structured facts and citations into a report.
   *
   * <p>A {@code null} request is a programming error and is rejected with {@link
   * IllegalArgumentException}; a {@code null} facts list is empty facts, a {@code null} fact entry
   * is skipped, and a {@code null} format defaults to {@link ReportFormat#MARKDOWN}, so empty,
   * valid and oversized inputs all yield a report with the same shape.
   *
   * @param request the report request; never {@code null}
   * @return the rendered {@link ReportResult}: the HTML-escaped report text, the format actually
   *     used, the character length, and whether it was truncated
   * @throws IllegalArgumentException if {@code request} is {@code null}
   */
  public static ReportResult format(ReportRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("report request must not be null");
    }
    ReportFormat format = request.format() != null ? request.format() : ReportFormat.MARKDOWN;
    List<StructuredFact> facts = request.facts();

    // Pre-render each non-null fact to its per-format block. (A null entry inside a caller-supplied
    // list is possible and skipped here, which keeps the schema stable for partially malformed
    // input.)
    List<FactBlock> blocks = new ArrayList<>();
    for (StructuredFact fact : facts) {
      if (fact == null) {
        continue;
      }
      blocks.add(new FactBlock(fact, renderFact(fact, format)));
    }

    // Greedily include whole facts until the report would exceed the cap. Whole facts are kept so a
    // Markdown line and a JSON object always stay well-formed; dropping the rest is what keeps the
    // output schema stable for empty, valid and oversized inputs (S08-06 acceptance).
    String separator = separatorFor(format);
    List<FactBlock> included = new ArrayList<>();
    boolean truncated = false;
    for (FactBlock block : blocks) {
      if (!included.isEmpty()
          && contentLength(included, separator)
                  + block.rendered().length()
                  + separator.length()
                  + OVERHEAD_CHARS
              > MAX_RESULT_CHARS) {
        truncated = true;
        break;
      }
      included.add(block);
    }

    String text = renderReport(format, included, truncated);
    return new ReportResult(text, format, text.length(), truncated);
  }

  // ---- per-fact rendering ------------------------------------------------

  private static String renderFact(StructuredFact fact, ReportFormat format) {
    return format == ReportFormat.JSON ? renderFactJson(fact) : renderFactMarkdown(fact);
  }

  private static String renderFactMarkdown(StructuredFact fact) {
    StringBuilder sb = new StringBuilder("- ");
    sb.append(html(fact.text()));
    // appendCitations only opens the parenthesised group when at least one source is present.
    appendCitations(sb, fact.citations(), " (", "; ", ")");
    return sb.toString();
  }

  private static String renderFactJson(StructuredFact fact) {
    StringBuilder sb = new StringBuilder("{\"text\":");
    sb.append(jsonText(fact.text()));
    sb.append(",\"citations\":[");
    appendCitationsJson(sb, fact.citations());
    sb.append("]}");
    return sb.toString();
  }

  private static void appendCitationsJson(StringBuilder sb, List<Citation> citations) {
    boolean first = true;
    for (Citation citation : citations) {
      if (citation.source() == null || citation.source().isBlank()) {
        continue;
      }
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append(renderCitationJson(citation));
    }
  }

  private static String renderCitationJson(Citation citation) {
    return "{\"source\":"
        + jsonText(citation.source())
        + ",\"url\":"
        + (citation.url() == null ? "null" : jsonText(citation.url()))
        + "}";
  }

  /**
   * Appends citation sources into a Markdown parenthesised group, skipping null/blank sources.
   *
   * @param sb the target builder
   * @param citations the citations to append
   * @param open the leading group opener ({@code " ("})
   * @param join the separator between sources ({@code "; "})
   * @param close the trailing group closer ({@code ")"})
   * @return {@code true} if at least one source was appended
   */
  private static boolean appendCitations(
      StringBuilder sb, List<Citation> citations, String open, String join, String close) {
    boolean first = true;
    boolean found = false;
    for (Citation citation : citations) {
      if (citation.source() == null || citation.source().isBlank()) {
        continue;
      }
      if (first) {
        sb.append(open);
        first = false;
      } else {
        sb.append(join);
      }
      sb.append(html(citation.source()));
      found = true;
    }
    if (!found) {
      return false;
    }
    sb.append(close);
    return true;
  }

  // ---- report assembly ---------------------------------------------------

  private static String renderReport(
      ReportFormat format, List<FactBlock> blocks, boolean truncated) {
    return format == ReportFormat.JSON
        ? renderReportJson(blocks, truncated)
        : renderReportMarkdown(blocks, truncated);
  }

  private static String renderReportJson(List<FactBlock> blocks, boolean truncated) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"truncated\":").append(truncated);
    sb.append(",\"factCount\":").append(blocks.size());
    sb.append(",\"facts\":[");
    boolean first = true;
    for (FactBlock block : blocks) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append(block.rendered());
    }
    sb.append("]}");
    return sb.toString();
  }

  private static String renderReportMarkdown(List<FactBlock> blocks, boolean truncated) {
    StringBuilder sb = new StringBuilder("# Report\n");
    if (blocks.isEmpty()) {
      return sb.append("No facts to report.").toString();
    }
    for (int i = 0; i < blocks.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      sb.append(blocks.get(i).rendered());
    }
    if (truncated) {
      sb.append("\n... [truncated]");
    }
    return sb.toString();
  }

  // ---- helpers -----------------------------------------------------------

  private static String separatorFor(ReportFormat format) {
    return format == ReportFormat.JSON ? "," : "\n";
  }

  private static int contentLength(List<FactBlock> blocks, String separator) {
    int total = 0;
    for (int i = 0; i < blocks.size(); i++) {
      if (i > 0) {
        total += separator.length();
      }
      total += blocks.get(i).rendered().length();
    }
    return total;
  }

  /**
   * Escapes the three characters that introduce or extend HTML markup ({@code &}, {@code <}, {@code
   * >}), so a value can never become executable markup. {@code &} is escaped first so an existing
   * ampersand is not double-escaped into {@code &amp;amp;}.
   *
   * @param value the text to escape; never {@code null}
   * @return the HTML-escaped text
   */
  private static String html(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /**
   * Encodes a value as a JSON string literal, escaping every JSON-special character (quote,
   * backslash, control characters) <em>and</em> the three HTML-markup characters ({@code &}, {@code
   * <}, {@code >}). The result is a quoted literal safe to embed both in JSON and in an HTML
   * document, so a JSON report carries no executable markup either.
   *
   * @param value the string to encode; never {@code null}
   * @return the quoted, escaped JSON string literal
   */
  private static String jsonText(String value) {
    StringBuilder sb = new StringBuilder(value.length() + 2);
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append("\\u").append(String.format("%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
    return sb.toString();
  }

  // ---- internal model ----------------------------------------------------

  /**
   * A pre-rendered fact: the fact, and its already-escaped rendered block in the chosen format.
   *
   * @param fact the fact the block was rendered from
   * @param rendered the rendered block (Markdown line or JSON object), HTML-escaped
   */
  private record FactBlock(StructuredFact fact, String rendered) {}
}
