package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.domain.plan.PlanLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The double policy gate acceptance (S11-12): the model is given only the minimal tool catalogue,
 * and after it answers, the gate re-checks every referenced tool — its id against the allowlist
 * (S09-07), then its required scopes against the caller's <em>current</em> permission set and its
 * human-approval requirement (the second stage). A malicious plan — one that names an allowlisted
 * tool the caller lacks the scope for, or one gated on a human decision — must never reach the
 * {@link PlanExecutor}; only a compliant plan does. A raw provider tool-call payload has no path
 * into execution at all: it deserializes into a plan with no steps and the structural stage refuses
 * it ("plan declares no steps") before the executor is consulted.
 *
 * <p>The suite drives the <em>production</em> path: a real {@link PlannerService} under the
 * same-package fake {@link PromptCataloguePort} (importing {@code ai.prompt} here would reintroduce
 * the {@code ai.application -> ai.prompt} cycle the {@code S02-09} gate guards against) and a
 * scripted {@link ChatModelPort} that <em>complies</em> — it answers with the hostile plan the
 * scenario fixes, so the only thing that can stop the capability is the gate, never a model that
 * happened to refuse. The policy facts and the caller scopes ride through {@link ValidationContext}
 * as data ({@code S02-08}: the gate compiles against no registry). DB-free, no model, no network.
 */
class DoublePolicyGateTest {

  /** The closed node vocabulary the gate checks against. */
  private static final Set<String> CLOSED_NODE_TYPES = Set.of("TOOL_CALL", "FINALIZE");

  /** The minimal catalogue the model is given in the scope scenarios. */
  private static final List<String> CATALOGUE = List.of("calc", "report");

  /** The live policy facts the caller resolved from the registries: `report` is scope-gated. */
  private static final Map<String, ToolGatePolicy> TOOL_POLICIES =
      Map.of(
          "calc", new ToolGatePolicy(Set.of(), false),
          "report", new ToolGatePolicy(Set.of("write:report"), false));

  /** The live policy facts with an approval-gated tool: `deploy` needs a human decision. */
  private static final Map<String, ToolGatePolicy> APPROVAL_POLICIES =
      Map.of(
          "calc", new ToolGatePolicy(Set.of(), false),
          "deploy", new ToolGatePolicy(Set.of(), true));

  private static final PlanLimits LIMITS = PlanLimits.DEFAULT;
  private static final String MODEL = "eval-fake-model";
  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000b122");

  /** The recording executor seam: the plans the double gate let through (empty for a held run). */
  private final List<PlannerPlanDto> executed = new ArrayList<>();

  @Test
  void a_malicious_plan_naming_an_allowlisted_tool_the_caller_lacks_the_scope_for_is_held() {
    // The model complies and proposes `report`; the structural allowlist admits it, but the
    // caller holds no `write:report` scope — the second stage refuses the plan, after the
    // answer, before the executor.
    AiPlanExecutionService execution = execution();

    assertThatThrownBy(
            () ->
                execution.execute(
                    planner("malicious-scope").propose(task(), CATALOGUE).plan(),
                    context(TOOL_POLICIES, Set.of())))
        .isInstanceOf(PlanValidationException.class)
        .satisfies(
            e ->
                assertThat(((PlanValidationException) e).violations())
                    .anyMatch(
                        m ->
                            m.contains("references tool report")
                                && m.contains("misses scopes")
                                && m.contains("write:report")));

    assertThat(executed).isEmpty();
  }

  @Test
  void a_malicious_plan_naming_an_approval_gated_tool_is_held() {
    // `deploy` is in the catalogue and needs no scope, but its action is gated on a human
    // decision — a model-proposed plan can never attest a grant, so the automatic gate refuses
    // it.
    List<String> catalogue = List.of("calc", "deploy");
    AiPlanExecutionService execution = execution();

    assertThatThrownBy(
            () ->
                execution.execute(
                    planner("malicious-approval").propose(task(), catalogue).plan(),
                    context(APPROVAL_POLICIES, Set.of())))
        .isInstanceOf(PlanValidationException.class)
        .satisfies(
            e ->
                assertThat(((PlanValidationException) e).violations())
                    .anyMatch(m -> m.contains("requires human approval") && m.contains("deploy")));

    assertThat(executed).isEmpty();
  }

  @Test
  void a_compliant_plan_reaches_the_executor_exactly_once() {
    // The caller holds every required scope: the double gate passes the plan through, unchanged.
    AiPlanExecutionService execution = execution();

    PlannerPlanDto plan = planner("compliant").propose(task(), CATALOGUE).plan();
    execution.execute(plan, context(TOOL_POLICIES, Set.of("write:report")));

    assertThat(executed).containsExactly(plan);
  }

  @Test
  void a_raw_provider_tool_call_payload_declares_no_plan_and_is_held_by_the_gate() {
    // The provider's native tool-calling shape is not plan JSON: it deserializes into a plan
    // with no steps (the tool_calls property is not a plan field), and the structural stage
    // refuses it ("plan declares no steps") before the executor — a raw provider tool call has
    // no path into execution; the executor is never consulted.
    AiPlanExecutionService execution = execution();

    assertThatThrownBy(
            () ->
                execution.execute(
                    planner("raw-tool-call").propose(task(), CATALOGUE).plan(),
                    context(TOOL_POLICIES, Set.of())))
        .isInstanceOf(PlanValidationException.class)
        .satisfies(
            e ->
                assertThat(((PlanValidationException) e).violations())
                    .containsExactly("plan declares no steps"));

    assertThat(executed).isEmpty();
  }

  private AiPlanExecutionService execution() {
    return new AiPlanExecutionService((plan, ctx) -> executed.add(plan));
  }

  private ValidationContext context(
      Map<String, ToolGatePolicy> toolPolicies, Set<String> callerScopes) {
    return new ValidationContext(
        TENANT,
        CLOSED_NODE_TYPES,
        Set.copyOf(toolPolicies.keySet()),
        LIMITS,
        toolPolicies,
        callerScopes);
  }

  /**
   * A real {@link PlannerService} under the fakes; the scripted provider <em>complies</em>,
   * answering with the payload the scenario fixes.
   *
   * @param scenario the scenario discriminator; never {@code null}
   * @return the production planner; never {@code null}
   */
  private PlannerService planner(String scenario) {
    ChatModelPort port = request -> new ChatResponse(MODEL, responseFor(scenario), "stop", null);
    return new PlannerService(catalogue(), MODEL, port, null);
  }

  private static String task() {
    return "the caller's task";
  }

  private static String responseFor(String scenario) {
    return switch (scenario) {
      case "malicious-approval" ->
          "{\"steps\":[{\"id\":\"s1\",\"type\":\"TOOL_CALL\",\"toolId\":\"deploy\"}]}";
      case "raw-tool-call" ->
          "{\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
              + "\"function\":{\"name\":\"report\",\"arguments\":\"{}\"}}]}";
      default -> "{\"steps\":[{\"id\":\"s1\",\"type\":\"TOOL_CALL\",\"toolId\":\"report\"}]}";
    };
  }

  /**
   * A same-package fake {@link PromptCataloguePort}: a fixed policy template and a version whose
   * hash satisfies {@link PromptVersion}'s SHA-256 validation (mirrors {@code PlannerServiceTest}).
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
        return List.of(
            new ChatMessage(MessageRole.SYSTEM, "PLANNER_POLICY_v1"),
            new ChatMessage(MessageRole.USER, userContent));
      }
    };
  }

  private static String hex64(char digit) {
    return new String(new char[64]).replace('\0', digit);
  }
}
