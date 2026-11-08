package com.example.agentflow.ai.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * S09-05 unit tests for {@link PlanLimits} (DB-free): the step / output-size bounds the plan schema
 * declares and the validation gate enforces (S09-07).
 */
class PlanLimitsTest {

  @Test
  void exposes_max_steps_and_max_output_size() {
    PlanLimits limits = new PlanLimits(10, 2_048);

    assertThat(limits.maxSteps()).isEqualTo(10);
    assertThat(limits.maxOutputSize()).isEqualTo(2_048);
  }

  @Test
  void non_positive_components_normalize_to_the_default() {
    PlanLimits limits = new PlanLimits(0, -1);

    assertThat(limits.maxSteps()).isEqualTo(PlanLimits.DEFAULT_MAX_STEPS);
    assertThat(limits.maxOutputSize()).isEqualTo(PlanLimits.DEFAULT_MAX_OUTPUT_SIZE);
  }

  @Test
  void default_is_a_valid_non_positive_bound() {
    PlanLimits limits = PlanLimits.DEFAULT;

    assertThat(limits.maxSteps()).isGreaterThan(0);
    assertThat(limits.maxOutputSize()).isGreaterThan(0);
  }
}
