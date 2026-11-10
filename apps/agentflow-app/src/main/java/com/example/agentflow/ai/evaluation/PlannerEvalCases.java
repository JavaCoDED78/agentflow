package com.example.agentflow.ai.evaluation;

import java.util.List;
import java.util.Set;

/**
 * The fixed baseline eval set for the Planner (S09-14): the small, stable catalogue of {@link
 * PlannerEvalCase cases} every prompt or model change is scored against, so two Planner
 * configurations are always compared on the same tasks with the same expectations.
 *
 * <p>The set exercises the plan shapes the Planner must produce reliably, one case per shape:
 *
 * <ul>
 *   <li>{@code calc-then-finalize} — the minimal tool pipeline: one {@code TOOL_CALL} followed by a
 *       {@code FINALIZE} that depends on it;
 *   <li>{@code calc-report-finalize} — a two-tool chain: calculator, then report formatter on the
 *       calculator's output, then finalize;
 *   <li>{@code finalize-only} — a no-tool answer: a single {@code FINALIZE} step against an empty
 *       allowlist, proving the model does not invent a tool it was not given;
 *   <li>{@code parallel-calc-finalize} — a fork: two independent calculator steps feeding one
 *       {@code FINALIZE}, exercising the dependency graph beyond a linear chain.
 * </ul>
 *
 * <p>The tool ids are the built-in registry names ({@code calculator}, {@code report}) so a scored
 * plan lines up with the tool vocabulary the platform actually ships; the cases themselves are
 * plain data and carry no registry dependency (the {@code ai} module may not compile against {@code
 * toolexecution}'s registry — the boundary gate, {@code S02-08}).
 *
 * <p>The list is returned unmodifiable and the cases are immutable, so the baseline a report was
 * produced against can never change underneath the report.
 */
public final class PlannerEvalCases {

  /** The built-in calculator tool id (the registry name the platform ships). */
  public static final String CALCULATOR = "calculator";

  /** The built-in report formatter tool id (the registry name the platform ships). */
  public static final String REPORT = "report";

  private PlannerEvalCases() {}

  /**
   * The fixed baseline set, in stable report order.
   *
   * @return the unmodifiable baseline cases; never {@code null}
   */
  public static List<PlannerEvalCase> baseline() {
    return List.of(
        new PlannerEvalCase(
            "calc-then-finalize",
            "Compute 2 + 2 with the calculator, then finalize with the result.",
            List.of(CALCULATOR),
            new PlannerEvalCase.ExpectedPlanShape(2, Set.of(CALCULATOR))),
        new PlannerEvalCase(
            "calc-report-finalize",
            "Compute 19% of 250 with the calculator, format the result as a markdown report,"
                + " then finalize.",
            List.of(CALCULATOR, REPORT),
            new PlannerEvalCase.ExpectedPlanShape(3, Set.of(CALCULATOR, REPORT))),
        new PlannerEvalCase(
            "finalize-only",
            "No tool is needed for this: answer with a fixed summary and finalize.",
            List.of(),
            new PlannerEvalCase.ExpectedPlanShape(1, Set.of())),
        new PlannerEvalCase(
            "parallel-calc-finalize",
            "Compute 2 + 2 and 3 * 3 in two independent calculator steps, then finalize with"
                + " both results.",
            List.of(CALCULATOR),
            new PlannerEvalCase.ExpectedPlanShape(3, Set.of(CALCULATOR))));
  }
}
