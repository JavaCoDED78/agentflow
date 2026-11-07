package com.example.agentflow.toolexecution.application;

/**
 * The outcome of a single tool execution attempt, as decided by the layers that gate a tool call
 * (S08-09 / the invocation policy guard, and the executor that runs the tool, S08-13).
 *
 * <p>It is the vocabulary the {@link ToolInvocationPolicy} invocation guard and the tool executor
 * agree on. It is the {@code toolexecution} counterpart of the workflow's {@code
 * workflowexecution.domain.StepResult} outcome: the workflow state machine decides {@code
 * SUCCEEDED} / {@code FAILED} for a step, while this enum decides whether one <em>tool
 * invocation</em> may run, did run, or was denied.
 *
 * <p>The values are:
 *
 * <ul>
 *   <li>{@link #ALLOWED} — the invocation policy guard re-checked the tool, its risk class and the
 *       caller's <em>current</em> permissions immediately before the call and found the call
 *       permitted;
 *   <li>{@link #DENIED} — the guard re-checked at invocation and refused the call (an unknown tool,
 *       an unknown risk class, or a permission the caller no longer holds — including a permission
 *       that was revoked <em>after</em> the workflow was activated; the S08-09 acceptance);
 *   <li>{@link #EXECUTED} — the guard allowed the call and the tool ran to completion (the executor
 *       sets this, S08-13).
 * </ul>
 *
 * <p>Owner: {@code toolexecution} (the tool permission check, module-map business-rule ownership,
 * "tool permission ... before/after each call").
 */
public enum ToolExecutionOutcome {

  /** The invocation policy guard permitted the call; the tool may run. */
  ALLOWED,

  /** The invocation policy guard refused the call; the tool must not run. */
  DENIED,

  /** The call was allowed by the guard and the tool ran to completion. */
  EXECUTED
}
