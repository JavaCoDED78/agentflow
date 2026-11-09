package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for {@link PlannerRepairPolicy} (S09-08): the descriptor that bounds how many
 * corrective retries a malformed-plan attempt may take.
 */
class PlannerRepairPolicyTest {

  @Test
  void of_preserves_the_max_number_of_corrective_retries() {
    assertThat(PlannerRepairPolicy.of(2).maxRetries()).isEqualTo(2);
  }

  @Test
  void of_defaults_is_one_corrective_retry() {
    // S09-08: at most one corrective retry, so a malformed plan cannot drive an infinite model
    // loop.
    assertThat(PlannerRepairPolicy.ofDefaults().maxRetries()).isEqualTo(1);
  }

  @Test
  void a_negative_retries_count_is_a_configuration_error() {
    assertThatThrownBy(() -> PlannerRepairPolicy.of(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxRetries must be >= 0");
  }
}
