package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for {@link PlannerFailure} (S09-08): which of the four malformed-output /
 * refusal / transport failure modes a corrective retry can actually fix.
 */
class PlannerFailureTest {

  @Test
  void malformed_json_schema_mismatch_and_transport_are_repairable() {
    // A retry gives the model another chance to produce a usable response.
    assertThat(PlannerFailure.MALFORMED_JSON.isRepairable()).isTrue();
    assertThat(PlannerFailure.SCHEMA_MISMATCH.isRepairable()).isTrue();
    assertThat(PlannerFailure.TRANSPORT_FAILURE.isRepairable()).isTrue();
  }

  @Test
  void provider_refusal_is_not_repairable() {
    // A refusal (or empty completion) will not self-correct by retrying, so a retry would only burn
    // the budget: the repair policy never retries it.
    assertThat(PlannerFailure.PROVIDER_REFUSAL.isRepairable()).isFalse();
  }

  @Test
  void the_repair_decision_covers_exactly_the_four_failure_modes() {
    assertThat(PlannerFailure.values())
        .containsExactlyInAnyOrder(
            PlannerFailure.MALFORMED_JSON,
            PlannerFailure.SCHEMA_MISMATCH,
            PlannerFailure.PROVIDER_REFUSAL,
            PlannerFailure.TRANSPORT_FAILURE);
  }
}
