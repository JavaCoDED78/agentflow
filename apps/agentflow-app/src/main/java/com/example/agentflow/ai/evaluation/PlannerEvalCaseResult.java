package com.example.agentflow.ai.evaluation;

import com.example.agentflow.ai.application.PlannerFailure;
import com.example.agentflow.ai.application.PromptVersion;
import java.util.List;
import java.util.Objects;

/**
 * The scored outcome of one {@link PlannerEvalCase} (S09-14): the four metrics the eval measures —
 * schema validity, tool allowlist conformance, expected shape, and limits — plus the attempt
 * metadata that ties the score to the model and prompt that produced it.
 *
 * <p>The metrics are deliberately structural, never textual (the S09-14 requirement):
 *
 * <ul>
 *   <li>{@link #schemaValid()} — the proposed plan parsed and passed the whole server-side
 *       validation gate (shape, field contract, closed node vocabulary, tool allowlist, dependency
 *       DAG, limits); a classified parse failure (malformed JSON, schema mismatch, refusal,
 *       transport) records {@link #failure()} instead and every metric is {@code false};
 *   <li>{@link #toolsWithinAllowlist()} — every tool the plan references is one the case's
 *       allowlist handed the Planner (a plan sneaking a tool past the model is measured explicitly,
 *       not only inside the gate's combined verdict);
 *   <li>{@link #expectedShapeMet()} — the plan declares exactly the expected step count and
 *       references exactly the expected tool set;
 *   <li>{@link #withinLimits()} — the step count stays inside the {@link
 *       com.example.agentflow.ai.domain.plan.PlanLimits} bound the runner was built with.
 * </ul>
 *
 * <p>A case {@link #passed()} only when all four metrics hold. {@link #model()} and {@link
 * #promptVersion()} are {@code null} when the attempt failed before a plan was produced, and never
 * carry prompt text or task content — the version identity is the only observable surface
 * (invariant 8).
 *
 * @param caseId the scored case's stable id
 * @param schemaValid the plan parsed and passed the validation gate
 * @param toolsWithinAllowlist every referenced tool is in the case's allowlist
 * @param expectedShapeMet the step count and referenced tool set match the expectation exactly
 * @param withinLimits the step count respects the runner's {@code PlanLimits}
 * @param stepCount the proposed plan's step count ({@code 0} when no plan was produced)
 * @param gateViolations the validation gate's violation messages (empty on a clean pass)
 * @param failure the classified Planner failure when no plan was produced; {@code null} otherwise
 * @param model the provider-reported model id; {@code null} when the attempt failed
 * @param promptVersion the prompt version the attempt planned against; {@code null} when the
 *     attempt failed
 * @param passed all four metrics hold
 */
public record PlannerEvalCaseResult(
    String caseId,
    boolean schemaValid,
    boolean toolsWithinAllowlist,
    boolean expectedShapeMet,
    boolean withinLimits,
    int stepCount,
    List<String> gateViolations,
    PlannerFailure failure,
    String model,
    PromptVersion promptVersion,
    boolean passed) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code caseId} or {@code gateViolations} is {@code null}
   */
  public PlannerEvalCaseResult {
    Objects.requireNonNull(caseId, "caseId must not be null");
    gateViolations = List.copyOf(gateViolations == null ? List.of() : gateViolations);
  }
}
