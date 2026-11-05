package com.example.agentflow.workflowexecution.domain;

/**
 * The outcome a {@link StepHandler} returns for one executed step (S07-08).
 *
 * <p>The outcome is the step state the worker applies: {@link WorkflowStepState#SUCCEEDED} carries
 * the step's output (persisted as a checkpoint through {@code CheckpointAdapter}, redacted and
 * size-bounded); {@link WorkflowStepState#FAILED} is a *decided* failure — an unambiguous "the side
 * effect did not complete" — that the worker surfaces on the run. It is a distinct concept from an
 * **ambiguous** external outcome: an external call whose side effect *may* have completed but whose
 * response was lost (a {@code provider_timeout}, or the worker crashing mid-attempt). The engine
 * does not treat the external call as exactly-once (invariant 6): an ambiguous attempt is marked in
 * {@code workflow_step_attempt_history} and re-claimed, never assumed to have failed — a {@code
 * RETRY_WAIT} with a safe {@code ErrorClass} (e.g. {@code provider_timeout}) is retried, while a
 * non-idempotent side effect must instead be *resolved*, not blindly retried (see {@code
 * step-state-machine.md: Ambiguous external outcomes}).
 *
 * @param outcome the step outcome the worker transitions the step to
 * @param output the step output, or {@code null} when the step failed / produced no value
 */
public record StepResult(WorkflowStepState outcome, String output) {

  /**
   * @param output the step output (may be {@code null}); persisted as a checkpoint by the worker
   * @return a {@code SUCCEEDED} result carrying the output
   */
  public static StepResult succeeded(String output) {
    return new StepResult(WorkflowStepState.SUCCEEDED, output);
  }

  /**
   * @return a permanent {@code FAILED} result with no output
   */
  public static StepResult failed() {
    return new StepResult(WorkflowStepState.FAILED, null);
  }
}
