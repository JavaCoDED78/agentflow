package com.example.agentflow.ai.domain.plan;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Planner's proposed plan: an ordered list of {@link PlanStep steps} forming a typed graph
 * (S09-05).
 *
 * <p>This is the structured response schema the Planner must fill: a non-empty, ordered sequence of
 * typed steps. The plan is a <em>proposal</em>, never an execution — the model only ever proposes
 * ({@code ADR-006}), and every step is re-authorized and validated server-side before it runs
 * (S09-07).
 *
 * <p>The canonical constructor enforces the plan's own structural well-formedness so a {@code
 * PlannerPlan} is always internally consistent:
 *
 * <ul>
 *   <li>the step {@code id}s are unique (a plan cannot name two steps the same);
 *   <li>every {@code dependsOn} reference points at a step the plan actually declares (no dangling
 *       dependency);
 *   <li>a step does not depend on itself.
 * </ul>
 *
 * The further, context-dependent gates — the node type and tool id against the closed registries, a
 * node cycle, the step count against {@link PlanLimits}, the budget and the tenant scope, and the
 * rejection of unknown/extra fields — are the job of the server-side validation gate (S09-07),
 * which has the cross-module registry and permission context the value type deliberately lacks.
 *
 * <p>{@code null} steps (the deserializer in S09-06 omits an empty list) defaults to an empty,
 * unmodifiable list. This is a pure, immutable value type (no Spring / I/O, reads neither time nor
 * randomness, invariant 10).
 *
 * @param steps the ordered proposed steps; may be {@code null} (defaults to empty)
 * @throws NullPointerException if {@code steps} is {@code null}
 * @throws IllegalArgumentException if a step id is duplicated, a {@code dependsOn} entry is not a
 *     declared step id, or a step depends on itself
 */
public record PlannerPlan(List<PlanStep> steps) {

  /**
   * Canonical constructor.
   *
   * <p>A {@code null} list defaults to an empty, unmodifiable one; the steps list is then
   * snapshotted unmodifiable. The step {@code id}s must be unique and every {@code dependsOn} entry
   * must reference a declared step id, and no step may depend on itself.
   *
   * @throws NullPointerException if {@code steps} is {@code null}
   * @throws IllegalArgumentException if a step id is duplicated, a {@code dependsOn} entry is not a
   *     declared step id, or a step depends on itself
   */
  public PlannerPlan {
    if (steps == null) {
      steps = List.of();
    } else {
      steps = List.copyOf(steps);
    }

    final Set<String> ids = new HashSet<>();
    for (PlanStep step : steps) {
      if (!ids.add(step.id())) {
        throw new IllegalArgumentException("duplicate step id: " + step.id());
      }
    }
    for (PlanStep step : steps) {
      for (String dependency : step.dependsOn()) {
        if (dependency.equals(step.id())) {
          throw new IllegalArgumentException("step " + step.id() + " cannot depend on itself");
        }
        if (!ids.contains(dependency)) {
          throw new IllegalArgumentException(
              "step " + step.id() + " depends on unknown step: " + dependency);
        }
      }
    }
  }
}
