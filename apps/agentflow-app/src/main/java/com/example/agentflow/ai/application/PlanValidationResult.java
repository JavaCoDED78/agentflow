package com.example.agentflow.ai.application;

import java.util.List;

/**
 * The outcome of a {@link ValidationGate#validate} call: whether the proposed plan passed the whole
 * policy validation stage (S09-07) and, when it did not, the ordered list of concrete violations.
 *
 * <p>This is the gate's single, failure-safe return shape: {@code valid} is {@code true} only when
 * {@code violations} is empty, so a caller never has to read a boolean and a list side by side and
 * guess which one wins. The violations name every failing check (an unknown tool, a node type
 * outside the closed vocabulary, a dependency cycle, an over-limit plan, a step that breaks the
 * plan's field contract), so the first failure is never the only thing the caller sees.
 *
 * <p>{@code valid} forces an empty violations list: a plan that passed is never reported with
 * spurious violations, and an invalid plan always carries a non-empty, unmodifiable one. This is a
 * pure, immutable value type (no Spring / I/O, invariant 10).
 *
 * @param valid {@code true} only when every policy check passed (and {@code violations} is empty)
 * @param violations every failing check, in check order; {@code null} is normalised to empty and a
 *     valid result is always reported with an empty list
 */
public record PlanValidationResult(boolean valid, List<String> violations) {

  /**
   * Canonical constructor. A {@code valid} result is reported with an empty, unmodifiable
   * violations list; an {@code invalid} result snapshots the supplied violations unmodifiable (a
   * {@code null} is treated as empty).
   *
   * @param valid {@code true} only when every policy check passed
   * @param violations the failing checks, in check order
   */
  public PlanValidationResult {
    if (valid) {
      violations = List.of();
    } else {
      violations = List.copyOf(violations == null ? List.of() : violations);
    }
  }

  /**
   * @return a passing result with no violations
   */
  public static PlanValidationResult passing() {
    return new PlanValidationResult(true, List.of());
  }

  /**
   * @param violations the failing checks, in check order
   * @return a failing result carrying those violations; a {@code null} is treated as empty
   */
  public static PlanValidationResult invalid(List<String> violations) {
    return new PlanValidationResult(false, violations);
  }
}
