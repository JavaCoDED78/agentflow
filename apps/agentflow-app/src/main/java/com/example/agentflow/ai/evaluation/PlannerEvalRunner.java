package com.example.agentflow.ai.evaluation;

import com.example.agentflow.ai.application.PlanValidationResult;
import com.example.agentflow.ai.application.PlannerPlanDto;
import com.example.agentflow.ai.application.PlannerPlanDto.PlanStepDto;
import com.example.agentflow.ai.application.PlannerPlanParseException;
import com.example.agentflow.ai.application.PlannerPlanResult;
import com.example.agentflow.ai.application.PlannerService;
import com.example.agentflow.ai.application.ValidationContext;
import com.example.agentflow.ai.application.ValidationGate;
import com.example.agentflow.ai.domain.plan.PlanLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Planner baseline eval runner (S09-14): it drives the production {@link PlannerService}
 * through a fixed set of {@link PlannerEvalCase cases} and scores every proposed plan on the four
 * structural metrics — schema validity, tool allowlist conformance, expected shape, limits — so a
 * prompt or model change can be compared against the baseline on identical tasks and expectations
 * (the S09-14 acceptance).
 *
 * <p>The runner measures the <em>real</em> path, not a parallel one: each case goes through {@link
 * PlannerService#propose}, including the bounded repair (S09-08) and the pre-call budget guard
 * (S09-10) the production Planner applies, and every produced plan is re-checked by the production
 * {@link ValidationGate} (S09-07) before its metrics are computed. A case whose attempt fails
 * (malformed JSON, schema mismatch, provider refusal, transport failure) is recorded with its
 * {@link com.example.agentflow.ai.application.PlannerFailure} classification and every metric
 * {@code false} — an eval run never rethrows a model fault and never scores a missing plan as
 * passing.
 *
 * <p>The gate's external facts — the closed node vocabulary and the {@link PlanLimits} — are
 * constructor inputs, exactly as the gate's design requires ({@code S02-08}: the {@code ai} module
 * may not reach {@code workflowdefinition}'s node registry, so the caller that owns it supplies the
 * vocabulary as data). The tenant is a fixed, clearly-labelled eval fixture id: the runner serves
 * no request and reads no security context, and tenant scoping is exercised by the gate tests, not
 * re-derived here.
 *
 * <p>This is a plain, deterministic value (no Spring bean, no I/O, reads neither time nor
 * randomness, invariant 10); a caller wires it with the {@link PlannerService} configuration under
 * test. The offline CI run is pinned by {@code PlannerEvalRunnerTest}; an opt-in run against a real
 * provider uses the same runner with a provider-backed {@link PlannerService}.
 */
public final class PlannerEvalRunner {

  /** The only step type that references a tool by {@code toolId} (the gate's contract). */
  private static final String TOOL_CALL = "TOOL_CALL";

  /**
   * The fixed tenant identity the eval validates under. The runner serves no request and reads no
   * security context — this is a labelled fixture, not an authorization source (invariant 1).
   */
  private static final UUID EVAL_TENANT = UUID.fromString("00000000-0000-0000-0000-00000000e9a4");

  private final PlannerService planner;
  private final ValidationGate gate = new ValidationGate();
  private final Set<String> allowedNodeTypes;
  private final PlanLimits limits;

  /**
   * @param planner the production Planner configuration under test; never {@code null}
   * @param allowedNodeTypes the closed node vocabulary the validation gate checks against (the
   *     caller owning the workflow node registry supplies it); never {@code null}
   * @param limits the step / output-size bounds the limits metric scores against; never {@code
   *     null}
   * @throws IllegalArgumentException if any argument is {@code null}
   */
  public PlannerEvalRunner(
      PlannerService planner, Set<String> allowedNodeTypes, PlanLimits limits) {
    if (planner == null) {
      throw new IllegalArgumentException("planner must not be null");
    }
    if (allowedNodeTypes == null) {
      throw new IllegalArgumentException("allowedNodeTypes must not be null");
    }
    if (limits == null) {
      throw new IllegalArgumentException("limits must not be null");
    }
    this.planner = planner;
    this.allowedNodeTypes = Set.copyOf(allowedNodeTypes);
    this.limits = limits;
  }

  /**
   * Run the fixed {@link PlannerEvalCases#baseline()} set.
   *
   * @return the scored report; never {@code null}
   */
  public PlannerEvalReport runBaseline() {
    return run(PlannerEvalCases.baseline());
  }

  /**
   * Score the given cases, in order, one result per case; a case whose model attempt fails is a
   * failed result, never an exception out of the run.
   *
   * @param cases the cases to score; never {@code null}
   * @return the scored report; never {@code null}
   * @throws IllegalArgumentException if {@code cases} is {@code null}
   */
  public PlannerEvalReport run(List<PlannerEvalCase> cases) {
    if (cases == null) {
      throw new IllegalArgumentException("cases must not be null");
    }
    List<PlannerEvalCaseResult> results = new ArrayList<>(cases.size());
    for (PlannerEvalCase evalCase : cases) {
      results.add(runCase(evalCase));
    }
    return new PlannerEvalReport(results);
  }

  private PlannerEvalCaseResult runCase(PlannerEvalCase evalCase) {
    PlannerPlanResult result;
    try {
      result = planner.propose(evalCase.task(), evalCase.allowedTools());
    } catch (PlannerPlanParseException e) {
      // The attempt failed (malformed / schema mismatch / refusal / transport): a classified
      // failure, every metric false, never a rethrown fault out of the eval run.
      return new PlannerEvalCaseResult(
          evalCase.id(),
          false,
          false,
          false,
          false,
          0,
          List.of(),
          e.classification(),
          null,
          null,
          false);
    }

    PlannerPlanDto plan = result.plan();
    ValidationContext context =
        new ValidationContext(
            EVAL_TENANT, allowedNodeTypes, Set.copyOf(evalCase.allowedTools()), limits);
    PlanValidationResult gateResult = gate.validate(plan, context);

    Set<String> referencedTools = referencedToolIds(plan);
    boolean toolsWithinAllowlist = Set.copyOf(evalCase.allowedTools()).containsAll(referencedTools);
    boolean expectedShapeMet =
        plan.steps().size() == evalCase.expected().expectedSteps()
            && referencedTools.equals(evalCase.expected().expectedToolIds());
    boolean withinLimits = plan.steps().size() <= limits.maxSteps();
    boolean schemaValid = gateResult.valid();
    boolean passed = schemaValid && toolsWithinAllowlist && expectedShapeMet && withinLimits;
    return new PlannerEvalCaseResult(
        evalCase.id(),
        schemaValid,
        toolsWithinAllowlist,
        expectedShapeMet,
        withinLimits,
        plan.steps().size(),
        gateResult.violations(),
        null,
        result.model(),
        result.promptVersion(),
        passed);
  }

  private static Set<String> referencedToolIds(PlannerPlanDto plan) {
    return plan.steps().stream()
        .filter(step -> TOOL_CALL.equals(step.type()))
        .map(PlanStepDto::toolId)
        .collect(Collectors.toUnmodifiableSet());
  }
}
