package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for {@link PlanValidationResult} (S09-07): the gate's fail-safe result shape,
 * where {@code valid} is {@code true} only when {@code violations} is empty.
 */
class PlanValidationResultTest {

  @Test
  void valid_result_carries_no_violations() {
    PlanValidationResult result = PlanValidationResult.passing();

    assertThat(result.valid()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void invalid_result_snapshots_the_violations_unmodifiable() {
    List<String> source = List.of("a", "b");
    PlanValidationResult result = PlanValidationResult.invalid(source);

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).containsExactly("a", "b");
    assertThatThrownBy(() -> result.violations().add("c"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void a_valid_result_is_never_reported_with_violations() {
    // Even if a caller pairs a valid flag with stray violations, the canonical constructor wins.
    PlanValidationResult result = new PlanValidationResult(true, List.of("stray"));

    assertThat(result.valid()).isTrue();
    assertThat(result.violations()).isEmpty();
  }
}
