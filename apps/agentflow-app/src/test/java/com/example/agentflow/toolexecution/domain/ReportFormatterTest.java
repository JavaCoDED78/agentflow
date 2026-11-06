package com.example.agentflow.toolexecution.domain;

import static com.example.agentflow.toolexecution.domain.ReportFormat.JSON;
import static com.example.agentflow.toolexecution.domain.ReportFormat.MARKDOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ReportFormatter} (S08-06).
 *
 * <p>They close the S08-06 acceptance criterion — the output schema is stable for {@code empty},
 * {@code valid} and {@code oversized} inputs — and prove the two safety guarantees the formatter
 * exists for: a report that carries <em>no executable HTML</em> (dynamic content is always
 * HTML-escaped, in both formats) and a result whose length is <em>bounded</em> (facts are included
 * whole and the rest dropped, marked via {@link ReportResult#truncated()}). All tests are DB-free
 * and read neither time nor randomness (invariant 10).
 */
class ReportFormatterTest {

  /** The formatter's result length cap; the oversized assertions bound the result to it. */
  private static final int MAX_RESULT_CHARS = 4000;

  // ---- empty input: schema stable -----------------------------------------

  @Test
  void an_empty_json_report_has_a_stable_shape() {
    ReportResult result = ReportFormatter.format(ReportRequest.json(List.of()));

    assertThat(result.format()).isEqualTo(JSON);
    assertThat(result.truncated()).isFalse();
    assertThat(result.charCount()).isEqualTo(result.text().length());
    // Same keys as a non-empty report, an empty facts array, a zero count.
    assertThat(result.text()).startsWith("{\"truncated\":false,\"factCount\":0,\"facts\":[]}");
    assertThat(result.text())
        .contains("\"factCount\"")
        .contains("\"facts\"")
        .contains("\"truncated\"");
  }

  @Test
  void a_null_facts_list_is_treated_as_empty() {
    ReportResult result = ReportFormatter.format(new ReportRequest(JSON, null));

    assertThat(result.text()).isEqualTo("{\"truncated\":false,\"factCount\":0,\"facts\":[]}");
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void a_null_format_defaults_to_markdown() {
    ReportResult result = ReportFormatter.format(new ReportRequest(null, List.of()));

    assertThat(result.format()).isEqualTo(MARKDOWN);
    assertThat(result.text()).startsWith("# Report\n").endsWith("No facts to report.");
  }

  @Test
  void an_empty_markdown_report_has_a_stable_shape() {
    ReportResult result = ReportFormatter.format(ReportRequest.markdown(List.of()));

    assertThat(result.format()).isEqualTo(MARKDOWN);
    assertThat(result.text()).isEqualTo("# Report\nNo facts to report.");
  }

  // ---- valid input -------------------------------------------------------

  @Test
  void a_markdown_fact_with_a_citation_is_one_bullet() {
    ReportResult result =
        ReportFormatter.format(
            ReportRequest.markdown(
                List.of(StructuredFact.of("Alpha", List.of(new Citation("srcA", null))))));

    assertThat(result.text()).isEqualTo("# Report\n- Alpha (srcA)");
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void a_markdown_fact_without_citations_has_no_parenthesis() {
    ReportResult result =
        ReportFormatter.format(ReportRequest.markdown(List.of(StructuredFact.of("Alpha", null))));

    assertThat(result.text()).isEqualTo("# Report\n- Alpha");
  }

  @Test
  void several_markdown_facts_are_separated_by_newlines() {
    ReportResult result =
        ReportFormatter.format(
            ReportRequest.markdown(
                List.of(
                    StructuredFact.of("Alpha", null),
                    StructuredFact.of("Beta", List.of(new Citation("srcB", null))))));

    assertThat(result.text()).isEqualTo("# Report\n- Alpha\n- Beta (srcB)");
  }

  @Test
  void a_json_fact_keeps_source_and_url() {
    ReportResult result =
        ReportFormatter.format(
            ReportRequest.json(
                List.of(StructuredFact.of("Alpha", List.of(new Citation("srcA", "https://x"))))));

    assertThat(result.text())
        .isEqualTo(
            "{\"truncated\":false,\"factCount\":1,\"facts\":[{\"text\":\"Alpha\",\"citations\":[{\"source\":\"srcA\",\"url\":\"https://x\"}]}]}");
  }

  @Test
  void a_json_fact_without_url_writes_null_url() {
    ReportResult result =
        ReportFormatter.format(
            ReportRequest.json(
                List.of(StructuredFact.of("Alpha", List.of(new Citation("srcA", null))))));

    assertThat(result.text()).contains("{\"source\":\"srcA\",\"url\":null}");
  }

  @Test
  void null_fact_entries_are_skipped() {
    ReportResult result =
        ReportFormatter.format(
            ReportRequest.markdown(Arrays.asList(null, StructuredFact.of("Alpha", null), null)));

    assertThat(result.text()).isEqualTo("# Report\n- Alpha");
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void a_blank_citation_source_is_skipped() {
    ReportResult result =
        ReportFormatter.format(
            ReportRequest.markdown(
                List.of(StructuredFact.of("Alpha", List.of(new Citation("  ", null))))));

    assertThat(result.text()).isEqualTo("# Report\n- Alpha");
  }

  @Test
  void a_null_request_is_rejected() {
    assertThatThrownBy(() -> ReportFormatter.format(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("report request must not be null");
  }

  // ---- no executable HTML -------------------------------------------------

  @Test
  void a_script_tag_in_text_is_escaped_in_markdown() {
    ReportResult result =
        ReportFormatter.format(
            ReportRequest.markdown(List.of(StructuredFact.of("<script>alert(1)</script>", null))));

    assertThat(result.text()).isEqualTo("# Report\n- &lt;script&gt;alert(1)&lt;/script&gt;");
    assertThat(result.text()).doesNotContain("<script").doesNotContain("<").doesNotContain(">");
  }

  @Test
  void a_script_tag_in_text_is_escaped_in_json() {
    ReportResult result =
        ReportFormatter.format(
            ReportRequest.json(List.of(StructuredFact.of("<script>alert(1)</script>", null))));

    assertThat(result.text()).contains("alert(1)").contains("&lt;script&gt;");
    assertThat(result.text()).doesNotContain("<script");
  }

  @Test
  void an_ampersand_is_escaped_before_the_other_html_characters() {
    ReportResult result =
        ReportFormatter.format(ReportRequest.markdown(List.of(StructuredFact.of("a & <b>", null))));

    assertThat(result.text()).isEqualTo("# Report\n- a &amp; &lt;b&gt;");
    assertThat(result.text()).doesNotContain("<").doesNotContain(">");
  }

  @Test
  void a_citation_source_is_escaped_too() {
    ReportResult result =
        ReportFormatter.format(
            ReportRequest.json(
                List.of(StructuredFact.of("Alpha", List.of(new Citation("<x>", null))))));

    assertThat(result.text()).contains("\"source\":\"&lt;x&gt;\"");
  }

  // ---- oversized input: schema stable, bounded ----------------------------

  @Test
  void an_oversized_report_is_truncated_but_keeps_the_same_shape() {
    int factCount = 300;
    List<StructuredFact> facts =
        java.util.stream.IntStream.range(0, factCount)
            .mapToObj(i -> StructuredFact.of("fact-" + i + "-".repeat(40), null))
            .toList();

    ReportResult result = ReportFormatter.format(ReportRequest.json(facts));

    // Dropped (not cut): schema is stable, facts are still present, the run is marked truncated.
    assertThat(result.truncated()).isTrue();
    assertThat(result.text()).startsWith("{\"truncated\":true,\"factCount\":");
    assertThat(result.text()).contains("\"facts\":[");
    assertThat(result.charCount()).isLessThanOrEqualTo(MAX_RESULT_CHARS);
  }

  @Test
  void an_oversized_markdown_report_is_bounded_and_flagged() {
    List<StructuredFact> facts =
        java.util.stream.IntStream.range(0, 300)
            .mapToObj(i -> StructuredFact.of("fact-" + i + "-".repeat(40), null))
            .toList();

    ReportResult result = ReportFormatter.format(ReportRequest.markdown(facts));

    assertThat(result.truncated()).isTrue();
    assertThat(result.text()).endsWith("... [truncated]");
    assertThat(result.charCount()).isLessThanOrEqualTo(MAX_RESULT_CHARS);
  }

  @Test
  void a_single_valid_input_stays_under_the_cap() {
    List<StructuredFact> facts =
        java.util.stream.IntStream.range(0, 5)
            .mapToObj(i -> StructuredFact.of("fact-" + i, null))
            .toList();

    ReportResult result = ReportFormatter.format(ReportRequest.json(facts));

    assertThat(result.truncated()).isFalse();
    assertThat(result.charCount()).isLessThanOrEqualTo(MAX_RESULT_CHARS);
  }
}
