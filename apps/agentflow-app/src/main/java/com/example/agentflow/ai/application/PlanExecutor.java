package com.example.agentflow.ai.application;

/**
 * The seam the {@link AiPlanExecutionService} hands a <em>validated</em> plan to before any step is
 * allowed to run (S09-07). The {@code ai} module owns this port, so the gate can stand between a
 * proposed plan and its execution without compiling a dependency on the executor's module: another
 * module provides the executor by implementing {@code PlanExecutor} and registering it as a Spring
 * bean, reaching {@code ai} through its public {@code application} API (the {@code ai} module
 * boundary, {@code S02-08}).
 *
 * <p>The gate in {@link AiPlanExecutionService} rejects an unknown tool, a dependency cycle or an
 * over-limit plan <em>before</em> this method is ever invoked, so a call to {@link #execute} means
 * the plan is authorized. The real adapter dispatches the validated steps to the run's executor (a
 * cross-module step that is documented as a future, ADR-governed edge): this contract is
 * deliberately narrow so the gate's guarantee holds regardless of how the steps are run.
 */
public interface PlanExecutor {

  /**
   * Execute the already-authorized plan.
   *
   * @param plan the validated plan; never a plan the gate rejected
   * @param ctx the tenant-scoped facts the validated plan was authorized against
   */
  void execute(PlannerPlanDto plan, ValidationContext ctx);
}
