package com.example.agentflow.ai.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The validate-before-execute choke point for an AI-proposed plan (S09-07). It is the server-side
 * half of {@code ADR-006} made live: the model <em>proposes</em>, and this service
 * <em>authorizes</em> the plan against the closed node vocabulary, the tool allowlist, the plan's
 * own field contract, the dependency graph and the {@link PlanLimits} bounds <em>before</em> a
 * single validated step is allowed to reach the {@link PlanExecutor}. A hostile or prompt-injected
 * response that names an unknown tool, forms a cycle or exceeds the limits is rejected as one loud
 * {@link PlanValidationException} and is never partially executed.
 *
 * <p>The gate is deterministic and DB-free, so the service needs no time, randomness, database or
 * provider; it only needs the tenant-scoped facts ({@link ValidationContext}) the caller resolves
 * from the verified security context (invariant 1) and the cross-module registries, plus the
 * optional {@link PlanExecutor} port that runs the plan. The port is injected with
 * {@code @Autowired(required = false)} following the existing opt-in, loud-at-call-time pattern
 * (the same one {@link PlannerService} uses for its provider port): a missing executor is a clear,
 * explicit failure at call time, not a boot failure of the whole app, and the gateway guarantee
 * holds regardless of who implements the port.
 */
@Service
public class AiPlanExecutionService {

  /** The policy stage: re-checks the plan against everything the backend approved. */
  private final ValidationGate gate = new ValidationGate();

  /** The executor the validated plan is handed to; optional (opt-in, loud at call time). */
  private final PlanExecutor planExecutor;

  /**
   * @param planExecutor the executor that runs a validated plan; optional, so the service builds
   *     even while the executor is still being wired
   */
  public AiPlanExecutionService(@Autowired(required = false) PlanExecutor planExecutor) {
    this.planExecutor = planExecutor;
  }

  /**
   * Authorize {@code plan} and, only if it passes, hand it to the executor.
   *
   * @param plan the proposed plan; a {@code null} plan is rejected as {@code invalid}
   * @param ctx the tenant-scoped facts the plan must be authorized against (never {@code null})
   * @throws PlanValidationException if the plan is rejected (unknown tool, cycle, over-limit, ...);
   *     nothing is executed in that case
   * @throws IllegalStateException if no {@link PlanExecutor} is configured
   * @throws NullPointerException if {@code ctx} is {@code null}
   */
  public void execute(PlannerPlanDto plan, ValidationContext ctx) {
    // Authorize first: a rejected plan must never reach the executor (S09-07 / ADR-006).
    gate.validateOrThrow(plan, ctx);

    PlanExecutor executor = this.planExecutor;
    if (executor == null) {
      throw new IllegalStateException(
          "no PlanExecutor configured: the validated plan cannot be executed");
    }
    executor.execute(plan, ctx);
  }
}
