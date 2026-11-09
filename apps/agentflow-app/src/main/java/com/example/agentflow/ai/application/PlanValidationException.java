package com.example.agentflow.ai.application;

import java.util.List;

/**
 * Thrown by {@link ValidationGate#validateOrThrow} when a proposed plan fails the policy validation
 * stage (S09-07): an unknown / disallowed tool, a node type outside the closed vocabulary, a
 * dependency cycle, a step that breaks the plan's field contract, or an over-limit plan (too many
 * steps or too large a serialized output).
 *
 * <p>It is the server-side half of {@code ADR-006}: the model <em>proposes</em>, the backend
 * <em>authorizes</em>. A plan the gate rejects is a loud, visible failure, never a silent {@code
 * null} plan or a guessed one, so a hostile or prompt-injected model response can never smuggle a
 * tool, a node type or a capability the backend never approved (invariant 7). The underlying {@link
 * #violations()} are carried on the exception, so the caller reports exactly what failed without
 * re-deriving it.
 *
 * <p>It is unchecked: a rejected plan is a contract-level failure the caller reports, not a checked
 * exception every call site must swallow (the same shape {@link PlannerPlanParseException} takes
 * for a malformed response).
 *
 * @param violations every failing policy check, in check order
 */
public class PlanValidationException extends RuntimeException {

  private final List<String> violations;

  /**
   * @param violations every failing policy check, in check order
   */
  public PlanValidationException(List<String> violations) {
    super(
        "plan failed validation: "
            + (violations == null || violations.isEmpty()
                ? "unknown policy violation"
                : String.join("; ", violations)));
    this.violations = List.copyOf(violations == null ? List.of() : violations);
  }

  /**
   * @return the unmodifiable list of failing policy checks, in check order
   */
  public List<String> violations() {
    return violations;
  }
}
