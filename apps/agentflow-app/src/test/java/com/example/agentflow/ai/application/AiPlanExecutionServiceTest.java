package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.PlannerPlanDto.PlanStepDto;
import com.example.agentflow.ai.domain.plan.PlanLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Application-level tests for the validate-before-execute choke point ({@link
 * AiPlanExecutionService}, S09-07): it proves the acceptance property that a rejected plan (unknown
 * tool, dependency cycle, over-limit) is <em>never</em> handed to the {@link PlanExecutor}, while a
 * validated plan reaches it exactly once. The executor is a spy so the test is DB-free and needs no
 * real execution pipeline — it asserts the guarantee at the seam the gate guards.
 */
class AiPlanExecutionServiceTest {

  private static final String LLM_PLAN = "LLM_PLAN";
  private static final String FINALIZE = "FINALIZE";

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

  /** A generous context carrying the full closed vocabulary and a couple of allowed tools. */
  private static ValidationContext context() {
    return new ValidationContext(
        TENANT,
        Set.of("RETRIEVE_CONTEXT", LLM_PLAN, "TOOL_CALL", "HUMAN_APPROVAL", FINALIZE),
        Set.of("calc", "report"),
        new PlanLimits(50, 8_192));
  }

  private static PlanStepDto step(String id, String type) {
    return new PlanStepDto(id, type, null, List.of(), Map.of());
  }

  private static PlanStepDto toolStep(String id, String toolId) {
    return new PlanStepDto(id, "TOOL_CALL", toolId, List.of(), Map.of());
  }

  private static PlannerPlanDto plan(PlanStepDto... steps) {
    return new PlannerPlanDto(List.of(steps));
  }

  private static PlannerPlanDto manyFinalizeSteps(int count) {
    List<PlanStepDto> steps = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      steps.add(step("s" + i, FINALIZE));
    }
    return new PlannerPlanDto(steps);
  }

  @Test
  void a_valid_plan_reaches_the_executor_exactly_once() {
    RecordingExecutor executor = new RecordingExecutor();
    AiPlanExecutionService service = new AiPlanExecutionService(executor);

    service.execute(plan(step("s1", FINALIZE)), context());

    assertThat(executor.executed()).hasSize(1);
  }

  @Test
  void an_unknown_tool_never_reaches_the_executor() {
    RecordingExecutor executor = new RecordingExecutor();
    AiPlanExecutionService service = new AiPlanExecutionService(executor);

    assertThatThrownBy(() -> service.execute(plan(toolStep("s1", "mystery")), context()))
        .isInstanceOf(PlanValidationException.class);

    assertThat(executor.executed()).isEmpty();
  }

  @Test
  void a_dependency_cycle_never_reaches_the_executor() {
    RecordingExecutor executor = new RecordingExecutor();
    AiPlanExecutionService service = new AiPlanExecutionService(executor);

    assertThatThrownBy(
            () ->
                service.execute(
                    plan(
                        new PlanStepDto("s1", LLM_PLAN, null, List.of("s2"), Map.of()),
                        new PlanStepDto("s2", FINALIZE, null, List.of("s1"), Map.of())),
                    context()))
        .isInstanceOf(PlanValidationException.class);

    assertThat(executor.executed()).isEmpty();
  }

  @Test
  void a_plan_over_the_limit_never_reaches_the_executor() {
    RecordingExecutor executor = new RecordingExecutor();
    AiPlanExecutionService service = new AiPlanExecutionService(executor);

    assertThatThrownBy(() -> service.execute(manyFinalizeSteps(51), context()))
        .isInstanceOf(PlanValidationException.class);

    assertThat(executor.executed()).isEmpty();
  }

  @Test
  void a_missing_executor_is_a_loud_failure_even_for_a_valid_plan() {
    AiPlanExecutionService service = new AiPlanExecutionService(null);

    assertThatThrownBy(() -> service.execute(plan(step("s1", FINALIZE)), context()))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * The test double for {@link PlanExecutor}: records how many validated plans were handed to it.
   */
  private static final class RecordingExecutor implements PlanExecutor {
    private final List<PlannerPlanDto> executed = new ArrayList<>();

    @Override
    public void execute(PlannerPlanDto plan, ValidationContext ignoredContext) {
      executed.add(plan);
    }

    private List<PlannerPlanDto> executed() {
      return executed;
    }
  }
}
