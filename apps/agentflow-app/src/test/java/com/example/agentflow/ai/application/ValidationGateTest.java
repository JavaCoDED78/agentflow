package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.ai.application.PlannerPlanDto.PlanStepDto;
import com.example.agentflow.ai.domain.plan.PlanLimits;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for the {@link ValidationGate} (S09-07): the server-side policy stage that
 * re-checks every proposed plan against the closed node vocabulary, the tool allowlist, the plan's
 * field contract, the dependency graph and the {@link PlanLimits} bounds before any step reaches
 * the executor.
 *
 * <p>The acceptance (unknown tool, cycle and over-limit plan never reach the executor) is pinned
 * directly: {@code unknown_tool_call_step_is_rejected}, {@code cycle_in_the_dependency_graph_is}
 * {@code rejected} and {@code plan_over_the_step_limit_is_rejected}. The rest cover the field
 * contract and the remaining bounds so the gate is exercised on every path.
 */
class ValidationGateTest {

  private static final String RETRIEVE_CONTEXT = "RETRIEVE_CONTEXT";
  private static final String LLM_PLAN = "LLM_PLAN";
  private static final String TOOL_CALL = "TOOL_CALL";
  private static final String HUMAN_APPROVAL = "HUMAN_APPROVAL";
  private static final String LLM_EVALUATE = "LLM_EVALUATE";
  private static final String FINALIZE = "FINALIZE";
  private static final List<String> CLOSED_NODE_TYPES =
      List.of(RETRIEVE_CONTEXT, LLM_PLAN, TOOL_CALL, HUMAN_APPROVAL, LLM_EVALUATE, FINALIZE);

  private final ValidationGate gate = new ValidationGate();

  /**
   * The facts a caller supplies from the verified security context and the cross-module registries.
   * Every test's context carries the full closed vocabulary and a couple of allowed tools, within a
   * generous bound, so a passing plan is a plan that is valid on every axis at once.
   */
  private static ValidationContext context() {
    return new ValidationContext(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        Set.copyOf(CLOSED_NODE_TYPES),
        Set.of("calc", "report"),
        new PlanLimits(50, 8_192));
  }

  private static PlanStepDto step(String id, String type) {
    return new PlanStepDto(id, type, null, List.of(), Map.of());
  }

  private static PlanStepDto toolStep(String id, String toolId) {
    return new PlanStepDto(id, TOOL_CALL, toolId, List.of(), Map.of());
  }

  private static PlannerPlanDto plan(PlanStepDto... steps) {
    return new PlannerPlanDto(List.of(steps));
  }

  // --- acceptance: the three things that must never reach the executor ---

  @Test
  void unknown_tool_call_step_is_rejected() {
    PlannerPlanDto plan = plan(toolStep("s1", "mystery"));

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isFalse();
    assertThat(result.violations())
        .anyMatch(m -> m.contains("unknown tool") && m.contains("mystery"));
  }

  @Test
  void a_tool_reference_outside_the_tenant_allowlist_never_passes() {
    // `report` is registered but outside this caller's allowlist; membership in the registry is not
    // authorization (invariant 7 / ADR-006).
    ValidationContext restrictive =
        new ValidationContext(
            context().tenantId(), context().allowedNodeTypes(), Set.of("calc"), context().limits());
    PlannerPlanDto plan = plan(toolStep("s1", "report"));

    PlanValidationResult result = gate.validate(plan, restrictive);

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).anyMatch(m -> m.contains("unknown tool"));
  }

  @Test
  void cycle_in_the_dependency_graph_is_rejected() {
    PlannerPlanDto plan =
        plan(
            new PlanStepDto("s1", LLM_PLAN, null, List.of("s2"), Map.of()),
            new PlanStepDto("s2", FINALIZE, null, List.of("s1"), Map.of()));

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).anyMatch(m -> m.contains("cycle"));
  }

  @Test
  void self_dependency_in_the_graph_is_rejected() {
    PlannerPlanDto plan = plan(new PlanStepDto("s1", LLM_PLAN, null, List.of("s1"), Map.of()));

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).anyMatch(m -> m.contains("depends on itself"));
  }

  @Test
  void plan_over_the_step_limit_is_rejected() {
    List<PlanStepDto> steps = new java.util.ArrayList<>();
    for (int i = 0; i < 51; i++) {
      steps.add(step("s" + i, FINALIZE));
    }
    PlannerPlanDto plan = new PlannerPlanDto(steps);
    ValidationContext tiny =
        new ValidationContext(
            UUID.randomUUID(), Set.copyOf(CLOSED_NODE_TYPES), Set.of(), new PlanLimits(50, 8_192));

    PlanValidationResult result = gate.validate(plan, tiny);

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).anyMatch(m -> m.contains("exceeds the maximum of 50"));
  }

  @Test
  void plan_over_the_output_size_is_rejected() {
    List<PlanStepDto> steps = new java.util.ArrayList<>();
    for (int i = 0; i < 60; i++) {
      steps.add(step("s" + i, FINALIZE));
    }
    PlannerPlanDto plan = new PlannerPlanDto(steps);
    // A generous step bound isolates the output-size bound: the overrun is size, not step count.
    ValidationContext tiny =
        new ValidationContext(
            UUID.randomUUID(), Set.copyOf(CLOSED_NODE_TYPES), Set.of(), new PlanLimits(500, 200));

    PlanValidationResult result = gate.validate(plan, tiny);

    assertThat(result.valid()).isFalse();
    assertThat(result.violations())
        .anyMatch(m -> m.contains("serialized size") && m.contains("200"));
  }

  // --- field contract ---

  @Test
  void null_id_is_rejected() {
    PlannerPlanDto plan =
        new PlannerPlanDto(List.of(new PlanStepDto(null, FINALIZE, null, List.of(), Map.of())));

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).anyMatch(m -> m.contains("null or blank id"));
  }

  @Test
  void blank_type_is_rejected() {
    PlannerPlanDto plan = plan(new PlanStepDto("s1", "   ", null, List.of(), Map.of()));

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).anyMatch(m -> m.contains("null or blank type"));
  }

  @Test
  void unknown_node_type_is_rejected() {
    PlannerPlanDto plan = plan(new PlanStepDto("s1", "EXECUTE_CODE", null, List.of(), Map.of()));

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isFalse();
    assertThat(result.violations())
        .anyMatch(m -> m.contains("unknown node type") && m.contains("EXECUTE_CODE"));
  }

  @Test
  void a_tool_call_step_without_a_tool_id_is_rejected() {
    PlannerPlanDto plan = plan(new PlanStepDto("s1", TOOL_CALL, null, List.of(), Map.of()));

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).anyMatch(m -> m.contains("TOOL_CALL without a toolId"));
  }

  @Test
  void a_non_tool_step_must_not_carry_a_tool_id() {
    PlannerPlanDto plan = plan(new PlanStepDto("s1", FINALIZE, "calc", List.of(), Map.of()));

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).anyMatch(m -> m.contains("carries a toolId"));
  }

  @Test
  void a_dangling_dependency_target_is_reported() {
    PlannerPlanDto plan = plan(new PlanStepDto("s1", LLM_PLAN, null, List.of("ghost"), Map.of()));

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isFalse();
    assertThat(result.violations())
        .anyMatch(m -> m.contains("unknown step") && m.contains("ghost"));
  }

  // --- happy paths ---

  @Test
  void a_plan_within_all_limits_and_allowlists_passes() {
    PlannerPlanDto plan = plan(new PlanStepDto("s1", FINALIZE, null, List.of(), Map.of()));

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void a_valid_chain_of_tool_steps_passes() {
    PlannerPlanDto plan =
        plan(
            toolStep("s1", "calc"),
            new PlanStepDto("s2", LLM_PLAN, null, List.of("s1"), Map.of()),
            toolStep("s3", "report"));

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void validateOrThrow_passes_through_a_valid_plan() {
    PlannerPlanDto plan = plan(step("s1", FINALIZE));

    PlanValidationResult result = gate.validateOrThrow(plan, context());

    assertThat(result.valid()).isTrue();
  }

  @Test
  void validateOrThrow_throws_with_the_violations() {
    PlannerPlanDto plan = plan(toolStep("s1", "mystery"));

    PlanValidationException ex;
    try {
      gate.validateOrThrow(plan, context());
      throw new AssertionError("expected PlanValidationException");
    } catch (PlanValidationException e) {
      ex = e;
    }

    assertThat(ex.violations()).anyMatch(m -> m.contains("unknown tool") && m.contains("mystery"));
    assertThat(ex.getMessage()).contains("unknown tool");
  }

  @Test
  void an_empty_plan_is_rejected() {
    PlannerPlanDto plan = new PlannerPlanDto(List.of());

    PlanValidationResult result = gate.validate(plan, context());

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).anyMatch(m -> m.contains("no steps"));
  }

  @Test
  void a_null_plan_is_rejected() {
    PlanValidationResult result = gate.validate(null, context());

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).containsExactly("plan is null");
  }
}
