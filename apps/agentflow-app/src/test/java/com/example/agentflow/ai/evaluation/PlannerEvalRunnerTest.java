package com.example.agentflow.ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.ChatMessage;
import com.example.agentflow.ai.application.ChatModelPort;
import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.ChatUsage;
import com.example.agentflow.ai.application.MessageRole;
import com.example.agentflow.ai.application.PlannerFailure;
import com.example.agentflow.ai.application.PlannerService;
import com.example.agentflow.ai.application.PromptCataloguePort;
import com.example.agentflow.ai.application.PromptVersion;
import com.example.agentflow.ai.domain.plan.PlanLimits;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for the {@link PlannerEvalRunner} (S09-14): the baseline eval scores a Planner
 * configuration on the four structural metrics (schema validity, tool allowlist conformance,
 * expected shape, limits), records classified model failures as failed cases, and renders a
 * deterministic report a prompt/model change can be diffed against.
 *
 * <p>The model is a scripted {@link ChatModelPort} that answers each request with a canned plan
 * looked up by the task text in the request's {@code USER} message — no provider, no network, no
 * key. The catalogue is a same-package fake {@link PromptCataloguePort} (the {@code
 * PlannerServiceTest} pattern), so the suite never imports {@code ai.prompt} and the acyclicity
 * gate stays untouched.
 */
class PlannerEvalRunnerTest {

  private static final String MODEL = "eval-fake-model";
  private static final String PLANNER_TEMPLATE = "PLANNER_POLICY_v1";
  private static final ChatUsage USAGE = new ChatUsage(12, 6, 18);

  private static final String RETRIEVE_CONTEXT = "RETRIEVE_CONTEXT";
  private static final String LLM_PLAN = "LLM_PLAN";
  private static final String TOOL_CALL = "TOOL_CALL";
  private static final String HUMAN_APPROVAL = "HUMAN_APPROVAL";
  private static final String LLM_EVALUATE = "LLM_EVALUATE";
  private static final String FINALIZE = "FINALIZE";
  private static final Set<String> CLOSED_NODE_TYPES =
      Set.of(RETRIEVE_CONTEXT, LLM_PLAN, TOOL_CALL, HUMAN_APPROVAL, LLM_EVALUATE, FINALIZE);

  @Test
  void the_baseline_passes_against_an_ideal_model() {
    PlannerEvalReport report = runner(scriptedProvider(idealPlans())).runBaseline();

    assertThat(report.allPassed()).isTrue();
    assertThat(report.total()).isEqualTo(4);
    assertThat(report.passedCount()).isEqualTo(4);
    for (PlannerEvalCaseResult result : report.results()) {
      assertThat(result.schemaValid()).isTrue();
      assertThat(result.toolsWithinAllowlist()).isTrue();
      assertThat(result.expectedShapeMet()).isTrue();
      assertThat(result.withinLimits()).isTrue();
      assertThat(result.failure()).isNull();
      assertThat(result.model()).isEqualTo(MODEL);
      assertThat(result.promptVersion().name()).isEqualTo("planner");
      assertThat(result.gateViolations()).isEmpty();
    }
    assertThat(report.render()).contains("planner-eval baseline: 4/4 cases passed");
    for (PlannerEvalCase evalCase : PlannerEvalCases.baseline()) {
      assertThat(report.render()).contains(evalCase.id());
    }
  }

  @Test
  void a_tool_outside_the_allowlist_fails_the_case_with_an_unknown_tool_violation() {
    String sneakyPlan =
        "{\"steps\":[{\"id\":\"s1\",\"type\":\"TOOL_CALL\",\"toolId\":\"shell-exec\","
            + "\"arguments\":{\"command\":\"ls\"}},{\"id\":\"s2\",\"type\":\"FINALIZE\","
            + "\"dependsOn\":[\"s1\"]}]}";
    Map<String, String> plans = idealPlans();
    plans.put(caseById("calc-then-finalize").task(), sneakyPlan);
    PlannerEvalReport report =
        runner(scriptedProvider(plans)).run(List.of(caseById("calc-then-finalize")));

    PlannerEvalCaseResult result = onlyResult(report);
    assertThat(result.passed()).isFalse();
    assertThat(result.schemaValid()).isFalse();
    assertThat(result.toolsWithinAllowlist()).isFalse();
    assertThat(result.expectedShapeMet()).isFalse();
    assertThat(result.gateViolations())
        .anyMatch(violation -> violation.contains("unknown tool: shell-exec"));
  }

  @Test
  void an_over_limit_plan_fails_the_limits_metric() {
    PlannerEvalRunner tightRunner =
        new PlannerEvalRunner(
            new PlannerService(catalogue(), MODEL, scriptedProvider(idealPlans()), null),
            CLOSED_NODE_TYPES,
            new PlanLimits(1, PlanLimits.DEFAULT_MAX_OUTPUT_SIZE));

    PlannerEvalReport report = tightRunner.run(List.of(caseById("calc-then-finalize")));

    PlannerEvalCaseResult result = onlyResult(report);
    assertThat(result.withinLimits()).isFalse();
    assertThat(result.schemaValid()).isFalse();
    assertThat(result.gateViolations())
        .anyMatch(violation -> violation.contains("exceeds the maximum of 1"));
    assertThat(result.passed()).isFalse();
  }

  @Test
  void a_malformed_response_is_a_schema_failure_with_its_classification() {
    Map<String, String> plans = idealPlans();
    plans.put(caseById("calc-then-finalize").task(), "{ not valid json");
    PlannerEvalReport report =
        runner(scriptedProvider(plans)).run(List.of(caseById("calc-then-finalize")));

    PlannerEvalCaseResult result = onlyResult(report);
    assertThat(result.passed()).isFalse();
    assertThat(result.schemaValid()).isFalse();
    assertThat(result.stepCount()).isZero();
    assertThat(result.failure()).isEqualTo(PlannerFailure.MALFORMED_JSON);
    assertThat(result.model()).isNull();
    assertThat(result.promptVersion()).isNull();
  }

  @Test
  void an_unexpected_step_count_fails_the_shape_metric_only() {
    String oneStepPlan = "{\"steps\":[{\"id\":\"s1\",\"type\":\"FINALIZE\"}]}";
    Map<String, String> plans = idealPlans();
    plans.put(caseById("calc-then-finalize").task(), oneStepPlan);
    PlannerEvalReport report =
        runner(scriptedProvider(plans)).run(List.of(caseById("calc-then-finalize")));

    PlannerEvalCaseResult result = onlyResult(report);
    assertThat(result.schemaValid()).isTrue();
    assertThat(result.toolsWithinAllowlist()).isTrue();
    assertThat(result.withinLimits()).isTrue();
    assertThat(result.expectedShapeMet()).isFalse();
    assertThat(result.stepCount()).isEqualTo(1);
    assertThat(result.passed()).isFalse();
  }

  @Test
  void the_rendered_report_is_deterministic_across_runs() {
    PlannerEvalRunner runner = runner(scriptedProvider(idealPlans()));

    assertThat(runner.runBaseline().render()).isEqualTo(runner.runBaseline().render());
  }

  @Test
  void null_construction_arguments_are_contract_errors() {
    PlannerService planner =
        new PlannerService(catalogue(), MODEL, scriptedProvider(idealPlans()), null);

    assertThatThrownBy(() -> new PlannerEvalRunner(null, CLOSED_NODE_TYPES, PlanLimits.DEFAULT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("planner");
    assertThatThrownBy(() -> new PlannerEvalRunner(planner, null, PlanLimits.DEFAULT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allowedNodeTypes");
    assertThatThrownBy(() -> new PlannerEvalRunner(planner, CLOSED_NODE_TYPES, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limits");
    assertThatThrownBy(() -> runner(scriptedProvider(idealPlans())).run(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cases");
  }

  private static PlannerEvalRunner runner(ChatModelPort port) {
    return new PlannerEvalRunner(
        new PlannerService(catalogue(), MODEL, port, null), CLOSED_NODE_TYPES, PlanLimits.DEFAULT);
  }

  private static PlannerEvalCaseResult onlyResult(PlannerEvalReport report) {
    assertThat(report.results()).hasSize(1);
    return report.results().get(0);
  }

  private static PlannerEvalCase caseById(String id) {
    return PlannerEvalCases.baseline().stream()
        .filter(evalCase -> evalCase.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no baseline case with id " + id));
  }

  /**
   * The ideal-model answer key: one structurally valid, expectation-matching plan per baseline
   * task, keyed by the task text so the scripted provider can resolve it from the request's {@code
   * USER} message.
   */
  private static Map<String, String> idealPlans() {
    Map<String, String> plans = new LinkedHashMap<>();
    plans.put(
        caseById("calc-then-finalize").task(),
        "{\"steps\":[{\"id\":\"s1\",\"type\":\"TOOL_CALL\",\"toolId\":\"calculator\","
            + "\"arguments\":{\"expression\":\"2+2\"}},"
            + "{\"id\":\"s2\",\"type\":\"FINALIZE\",\"dependsOn\":[\"s1\"]}]}");
    plans.put(
        caseById("calc-report-finalize").task(),
        "{\"steps\":[{\"id\":\"s1\",\"type\":\"TOOL_CALL\",\"toolId\":\"calculator\","
            + "\"arguments\":{\"expression\":\"19% of 250\"}},"
            + "{\"id\":\"s2\",\"type\":\"TOOL_CALL\",\"toolId\":\"report\",\"dependsOn\":[\"s1\"]},"
            + "{\"id\":\"s3\",\"type\":\"FINALIZE\",\"dependsOn\":[\"s2\"]}]}");
    plans.put(
        caseById("finalize-only").task(), "{\"steps\":[{\"id\":\"s1\",\"type\":\"FINALIZE\"}]}");
    plans.put(
        caseById("parallel-calc-finalize").task(),
        "{\"steps\":[{\"id\":\"s1\",\"type\":\"TOOL_CALL\",\"toolId\":\"calculator\","
            + "\"arguments\":{\"expression\":\"2+2\"}},"
            + "{\"id\":\"s2\",\"type\":\"TOOL_CALL\",\"toolId\":\"calculator\","
            + "\"arguments\":{\"expression\":\"3*3\"}},"
            + "{\"id\":\"s3\",\"type\":\"FINALIZE\",\"dependsOn\":[\"s1\",\"s2\"]}]}");
    return plans;
  }

  /**
   * A {@link ChatModelPort} that answers every request with the canned plan whose task key appears
   * in the request's {@code USER} message, so each baseline task receives its scripted answer.
   */
  private static ChatModelPort scriptedProvider(Map<String, String> taskToPlanJson) {
    return request -> {
      String userContent = request.messages().get(1).content();
      for (Map.Entry<String, String> entry : taskToPlanJson.entrySet()) {
        if (userContent.contains(entry.getKey())) {
          return new ChatResponse(MODEL, entry.getValue(), "stop", USAGE);
        }
      }
      throw new IllegalStateException("no scripted plan for user content: " + userContent);
    };
  }

  /**
   * A same-package fake {@link PromptCataloguePort} (the {@code PlannerServiceTest} pattern): a
   * fixed policy template and a version whose hash satisfies {@link PromptVersion}'s SHA-256
   * validation, so no real {@code ai.prompt} dependency is needed.
   */
  private static PromptCataloguePort catalogue() {
    PromptVersion version = new PromptVersion("planner", "1", hex64('0'));
    return new PromptCataloguePort() {
      @Override
      public PromptVersion versionOf(String name) {
        return version;
      }

      @Override
      public List<ChatMessage> build(String name, String userContent) {
        List<ChatMessage> messages = new ArrayList<>(2);
        messages.add(new ChatMessage(MessageRole.SYSTEM, PLANNER_TEMPLATE));
        messages.add(new ChatMessage(MessageRole.USER, userContent));
        return messages;
      }
    };
  }

  private static String hex64(char digit) {
    return new String(new char[64]).replace('\0', digit);
  }
}
