package com.example.agentflow.ai.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * S09-05 unit tests for {@link PlannerPlan} (DB-free): the structural well-formedness of a proposed
 * plan — unique step ids, no dangling and no self dependencies — and the fact that a plan is an
 * ordered list of steps, nothing more (no hidden reasoning / chain-of-thought field).
 */
class PlannerPlanTest {

  private static PlanStep step(String id, String type) {
    return new PlanStep(id, type, null, List.of(), Map.of());
  }

  @Test
  void exposes_only_an_ordered_step_list() {
    assertThat(Stream.of(PlannerPlan.class.getRecordComponents()).map(c -> c.getName()))
        .containsExactly("steps");
  }

  @Test
  void exposes_the_steps_in_declared_order() {
    PlannerPlan plan =
        new PlannerPlan(List.of(step("step-0", "RETRIEVE_CONTEXT"), step("step-1", "TOOL_CALL")));

    assertThat(plan.steps()).hasSize(2);
    assertThat(plan.steps().get(0).id()).isEqualTo("step-0");
    assertThat(plan.steps().get(1).id()).isEqualTo("step-1");
  }

  @Test
  void null_steps_defaults_to_empty() {
    assertThat(new PlannerPlan(null).steps()).isEmpty();
  }

  @Test
  void steps_are_returned_unmodifiable() {
    PlannerPlan plan = new PlannerPlan(List.of(step("step-0", "FINALIZE")));

    assertThatThrownBy(() -> plan.steps().add(step("step-1", "FINALIZE")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejects_a_duplicated_step_id() {
    assertThatThrownBy(() -> new PlannerPlan(List.of(step("dup", "TOOL_CALL"), step("dup", "T"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate step id: dup");
  }

  @Test
  void rejects_a_dangling_dependency() {
    PlanStep step = new PlanStep("step-1", "TOOL_CALL", null, List.of("ghost"), Map.of());

    assertThatThrownBy(() -> new PlannerPlan(List.of(step)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown step: ghost");
  }

  @Test
  void rejects_a_self_dependency() {
    PlanStep step = new PlanStep("step-1", "TOOL_CALL", null, List.of("step-1"), Map.of());

    assertThatThrownBy(() -> new PlannerPlan(List.of(step)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot depend on itself");
  }

  @Test
  void accepts_a_forward_reference_for_the_validation_gate_to_cycle_check() {
    // A reference to a declared (later) step id is structurally well formed here; acyclicity is the
    // validation gate's concern (S09-07). Only a dangling or self reference is rejected by the
    // type.
    PlanStep a = new PlanStep("a", "TOOL_CALL", null, List.of("b"), Map.of());
    PlanStep b = new PlanStep("b", "FINALIZE", null, List.of("a"), Map.of());

    assertThat(new PlannerPlan(List.of(a, b)).steps()).hasSize(2);
  }
}
