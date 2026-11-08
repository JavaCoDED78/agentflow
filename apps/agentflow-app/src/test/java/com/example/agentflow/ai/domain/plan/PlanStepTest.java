package com.example.agentflow.ai.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * S09-05 unit tests for {@link PlanStep} (DB-free): the shape of a single planned step. The
 * component set is exactly {@code id / type / toolId / dependsOn / arguments} — there is nowhere
 * for a chain-of-thought field, free-text instructions or an executable payload to live — and the
 * structured {@code arguments} are data, never code.
 */
class PlanStepTest {

  @Test
  void exposes_id_type_toolId_dependsOn_and_arguments() {
    PlanStep step = new PlanStep("step-1", "TOOL_CALL", "calculator", List.of("step-0"), Map.of());

    assertThat(step.id()).isEqualTo("step-1");
    assertThat(step.type()).isEqualTo("TOOL_CALL");
    assertThat(step.toolId()).isEqualTo("calculator");
    assertThat(step.dependsOn()).containsExactly("step-0");
    assertThat(step.arguments()).isEmpty();
  }

  @Test
  void record_components_are_exactly_id_type_toolId_dependsOn_and_arguments() {
    assertThat(
            Stream.of(PlanStep.class.getRecordComponents()).map(component -> component.getName()))
        .containsExactly("id", "type", "toolId", "dependsOn", "arguments");
  }

  @Test
  void null_or_blank_id_is_rejected() {
    assertThatThrownBy(() -> new PlanStep(null, "TOOL_CALL", null, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PlanStep("   ", "TOOL_CALL", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void null_or_blank_type_is_rejected() {
    assertThatThrownBy(() -> new PlanStep("step-1", null, null, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PlanStep("step-1", "   ", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void null_wiring_defaults_to_empty_unmodifiable_containers() {
    PlanStep step = new PlanStep("step-1", "FINALIZE", null, null, null);

    assertThat(step.dependsOn()).isEmpty();
    assertThat(step.arguments()).isEmpty();
    assertThat(step.toolId()).isNull();
    assertThatThrownBy(() -> step.dependsOn().add("step-2"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> step.arguments().put("k", "v"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void external_mutable_inputs_are_defensively_copied() {
    List<String> externalDeps = new ArrayList<>();
    Map<String, Object> externalArgs = new HashMap<>();
    PlanStep step = new PlanStep("step-1", "TOOL_CALL", null, externalDeps, externalArgs);

    // Mutating the caller's containers after construction must not change the stored value.
    externalDeps.add("leak");
    externalArgs.put("leak", "value");

    assertThat(step.dependsOn()).isEmpty();
    assertThat(step.arguments()).isEmpty();
  }
}
