package com.example.agentflow.ai.evaluation;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One baseline eval case for the Planner (S09-14): a fixed task, the tool allowlist the Planner is
 * handed for it, and the plan shape the case expects the model to propose for that task.
 *
 * <p>A case is pure fixture data: the {@code task} is the untrusted user content the Planner
 * proposes for (it is injected into the {@code USER} turn exactly as a caller-supplied task is),
 * the {@code allowedTools} are the allowlist the backend gives the Planner for this case, and the
 * {@link ExpectedPlanShape} is the <em>structural</em> expectation the scored plan must match — the
 * step count and the exact set of tool ids the plan must reference. The eval deliberately scores
 * structure (schema validity, tool allowlist conformance, step counts, limits), never a
 * text-similarity match: a plan is a typed graph to validate, not prose to eyeball.
 *
 * <p>This is a pure, immutable value type (no Spring / I/O, reads neither time nor randomness,
 * invariant 10). The fixed baseline set lives in {@link PlannerEvalCases}.
 *
 * @param id the stable case id (a slug) used to identify the case in reports and diffs; non-null,
 *     non-blank
 * @param task the fixed task text the Planner proposes for; non-null, non-blank
 * @param allowedTools the tool allowlist the backend hands the Planner for this case; {@code null}
 *     defaults to empty (no tool references permitted)
 * @param expected the expected plan shape this case scores against; never {@code null}
 */
public record PlannerEvalCase(
    String id, String task, List<String> allowedTools, ExpectedPlanShape expected) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code task} or {@code expected} is {@code null}
   * @throws IllegalArgumentException if {@code id} or {@code task} is blank
   */
  public PlannerEvalCase {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("case id must not be null or blank");
    }
    Objects.requireNonNull(task, "task must not be null");
    if (task.isBlank()) {
      throw new IllegalArgumentException("task must not be blank");
    }
    allowedTools = List.copyOf(allowedTools == null ? List.of() : allowedTools);
    Objects.requireNonNull(expected, "expected must not be null");
  }

  /**
   * The structural expectation a scored plan must match: exactly {@code expectedSteps} steps and
   * exactly the {@code expectedToolIds} referenced by the plan's {@code TOOL_CALL} steps.
   *
   * <p>Both expectations are exact — a baseline compares a prompt or model change against a known
   * good answer, so "close enough" is not a passing result. Dependency wiring is intentionally not
   * part of the expectation: it is covered by the schema-validity metric (the validation gate
   * rejects a dangling or cyclic graph), and pinning exact step ids would measure naming taste, not
   * plan quality.
   *
   * @param expectedSteps the exact step count the plan must declare; positive
   * @param expectedToolIds the exact set of tool ids the plan's {@code TOOL_CALL} steps must
   *     reference; {@code null} defaults to empty (the plan must call no tool)
   */
  public record ExpectedPlanShape(int expectedSteps, Set<String> expectedToolIds) {

    /**
     * Canonical constructor.
     *
     * @throws IllegalArgumentException if {@code expectedSteps} is not positive
     */
    public ExpectedPlanShape {
      if (expectedSteps <= 0) {
        throw new IllegalArgumentException("expectedSteps must be positive");
      }
      expectedToolIds = Set.copyOf(expectedToolIds == null ? Set.of() : expectedToolIds);
    }
  }
}
