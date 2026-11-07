package com.example.agentflow.toolexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.toolexecution.domain.Calculator;
import com.example.agentflow.toolexecution.domain.Citation;
import com.example.agentflow.toolexecution.domain.ReportFormatter;
import com.example.agentflow.toolexecution.domain.ReportRequest;
import com.example.agentflow.toolexecution.domain.SchemaValidationException;
import com.example.agentflow.toolexecution.domain.SchemaValidator;
import com.example.agentflow.toolexecution.domain.StructuredFact;
import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code docs/tools/README.md} authoring guide to the real descriptors and tools (S08-15).
 *
 * <p>The guide's reference sections (calculator §4, report §5) quote registered descriptor values
 * and example payloads; this suite proves the quotes are not drift: each example is validated
 * against the registered input schema through the real {@link SchemaValidator}, executed through
 * the real domain tool, and the descriptor metadata the guide tabulates (id, name, version, risk
 * class, scopes, timeout, approval flag) is asserted against the registered values. The strict
 * contract the guide tells authors to copy (the {@code ticket} descriptor) is pinned too: the
 * guide's example conforms, and an unknown property is rejected before any call.
 *
 * <p>DB-free, no model/Kafka/system-clock dependency: everything here is a pure value.
 */
class ToolAuthoringGuideExamplesTest {

  private static final Map<String, Object> CALCULATOR_EXAMPLE = Map.of("expression", "2 + 3 * 4");

  private static final Map<String, Object> REPORT_EXAMPLE =
      Map.of(
          "format",
          "MARKDOWN",
          "facts",
          List.of(
              Map.of(
                  "text",
                  "Revenue grew 12% year over year",
                  "citations",
                  List.of(
                      Map.of("source", "Q3 earnings report", "url", "https://example.com/q3")))));

  @Test
  void the_calculator_guide_example_passes_the_registered_input_schema_and_evaluates_to_14() {
    ToolDescriptor calculator = BuiltInTools.calculator();

    assertThatCode(() -> SchemaValidator.of(calculator.inputSchema()).validate(CALCULATOR_EXAMPLE))
        .doesNotThrowAnyException();
    assertThat(Calculator.evaluate("2 + 3 * 4")).isEqualByComparingTo("14");
  }

  @Test
  void the_report_guide_example_passes_the_registered_input_schema_and_formats_a_markdown_report() {
    ToolDescriptor report = BuiltInTools.reportFormatter();

    assertThatCode(() -> SchemaValidator.of(report.inputSchema()).validate(REPORT_EXAMPLE))
        .doesNotThrowAnyException();

    var result =
        ReportFormatter.format(
            ReportRequest.markdown(
                List.of(
                    StructuredFact.of(
                        "Revenue grew 12% year over year",
                        List.of(Citation.of("Q3 earnings report", "https://example.com/q3"))))));

    assertThat(result.text()).startsWith("# Report");
    assertThat(result.text()).contains("Revenue grew 12% year over year");
    assertThat(result.text()).contains("Q3 earnings report");
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void the_registered_descriptors_match_the_values_tabulated_in_the_guide() {
    ToolDescriptor calculator = BuiltInTools.calculator();
    assertThat(calculator.id()).isEqualTo("tool-calculator-1");
    assertThat(calculator.name()).isEqualTo("calculator");
    assertThat(calculator.version()).isEqualTo("1");
    assertThat(calculator.riskClass()).isEqualTo(ToolRiskClass.READ_ONLY);
    assertThat(calculator.requiredScopes()).isEmpty();
    assertThat(calculator.timeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(calculator.requiresHumanApproval()).isFalse();

    ToolDescriptor report = BuiltInTools.reportFormatter();
    assertThat(report.id()).isEqualTo("tool-report-formatter-1");
    assertThat(report.name()).isEqualTo("report");
    assertThat(report.version()).isEqualTo("1");
    assertThat(report.riskClass()).isEqualTo(ToolRiskClass.READ_ONLY);
    assertThat(report.requiredScopes()).isEmpty();
    assertThat(report.timeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(report.requiresHumanApproval()).isFalse();
  }

  @Test
  void
      the_strict_ticket_contract_the_guide_points_authors_to_accepts_its_example_and_rejects_unknown_properties() {
    ToolDescriptor ticket = BuiltInTools.ticketLookup();

    assertThatCode(
            () -> SchemaValidator.of(ticket.inputSchema()).validate(Map.of("ticketId", "TKT-1001")))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                SchemaValidator.of(ticket.inputSchema())
                    .validate(Map.of("ticketId", "TKT-1001", "surprise", "x")))
        .isInstanceOf(SchemaValidationException.class)
        .extracting(e -> ((SchemaValidationException) e).reason())
        .isEqualTo("unknown-property");
  }

  @Test
  void every_shipped_builtin_is_read_only_with_no_side_effect_scope() {
    assertThat(BuiltInTools.builtinDescriptors())
        .allSatisfy(
            descriptor -> {
              assertThat(descriptor.riskClass()).isEqualTo(ToolRiskClass.READ_ONLY);
              assertThat(descriptor.requiredScopes()).isEqualTo(Set.of());
              assertThat(descriptor.requiresHumanApproval()).isFalse();
            });
  }
}
