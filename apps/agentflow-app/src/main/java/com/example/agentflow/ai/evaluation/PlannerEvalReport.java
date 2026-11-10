package com.example.agentflow.ai.evaluation;

import java.util.List;

/**
 * The scored outcome of an eval run (S09-14): one {@link PlannerEvalCaseResult} per case, in run
 * order, plus the aggregate that answers "did this Planner configuration hold the baseline".
 *
 * <p>{@link #render()} produces a stable, plain-text rendering of the report — no timestamps, no
 * randomness, case order fixed by the fixture — so two runs of different prompt or model
 * configurations can be diffed directly (the S09-14 acceptance: a prompt/model change can be
 * compared against the baseline). The rendering carries only ids, versions and metric verdicts: no
 * task text, no prompt content, no arguments (invariant 8).
 *
 * @param results the per-case results in run order; {@code null} defaults to empty
 */
public record PlannerEvalReport(List<PlannerEvalCaseResult> results) {

  /** Canonical constructor. */
  public PlannerEvalReport {
    results = List.copyOf(results == null ? List.of() : results);
  }

  /**
   * @return the number of cases in the run
   */
  public int total() {
    return results.size();
  }

  /**
   * @return the number of cases that passed (every metric held)
   */
  public int passedCount() {
    return (int) results.stream().filter(PlannerEvalCaseResult::passed).count();
  }

  /**
   * @return {@code true} only when every case passed; an empty run passes vacuously
   */
  public boolean allPassed() {
    return results.stream().allMatch(PlannerEvalCaseResult::passed);
  }

  /**
   * A deterministic, diffable one-line-per-case rendering of the report, in the form:
   *
   * <pre>{@code
   * planner-eval baseline: 3/4 cases passed
   * PASS calc-then-finalize model=eval-fake-model prompt=planner@1 steps=2 schema=valid tools=allowlist shape=met limits=ok
   * FAIL finalize-only model=- prompt=- steps=0 schema=invalid tools=violation shape=mismatch limits=exceeded failure=MALFORMED_JSON
   * }</pre>
   *
   * <p>Failed cases append their classified failure and, when the plan reached the gate, the gate
   * violations, so a diff names the regression rather than only flagging it. Lines are separated by
   * a fixed {@code \n} (never the platform line separator) so the rendering is byte-identical on
   * every OS a baseline diff may be produced on.
   *
   * @return the stable text rendering; never {@code null}
   */
  public String render() {
    StringBuilder out = new StringBuilder();
    out.append("planner-eval baseline: ").append(passedCount()).append('/').append(total());
    out.append(" cases passed\n");
    for (PlannerEvalCaseResult result : results) {
      out.append(result.passed() ? "PASS " : "FAIL ").append(result.caseId());
      out.append(" model=").append(result.model() == null ? "-" : result.model());
      out.append(" prompt=").append(result.promptVersion() == null ? "-" : promptRef(result));
      out.append(" steps=").append(result.stepCount());
      out.append(" schema=").append(result.schemaValid() ? "valid" : "invalid");
      out.append(" tools=").append(result.toolsWithinAllowlist() ? "allowlist" : "violation");
      out.append(" shape=").append(result.expectedShapeMet() ? "met" : "mismatch");
      out.append(" limits=").append(result.withinLimits() ? "ok" : "exceeded");
      if (result.failure() != null) {
        out.append(" failure=").append(result.failure());
      }
      if (!result.gateViolations().isEmpty()) {
        out.append(" violations=").append(String.join(" | ", result.gateViolations()));
      }
      out.append('\n');
    }
    return out.toString();
  }

  private static String promptRef(PlannerEvalCaseResult result) {
    return result.promptVersion().name() + "@" + result.promptVersion().version();
  }
}
