package com.example.agentflow.workflowexecution.domain;

/**
 * The outcome a {@link StepHandler} returns for one executed step (S07-08).
 *
 * <p>The outcome is the step state the worker applies: {@link WorkflowStepState#SUCCEEDED} carries
 * the step's output (persisted as a checkpoint through {@code CheckpointAdapter}, redacted and
 * size-bounded); {@link WorkflowStepState#FAILED} is a permanent failure the worker surfaces on the
 * run (an ambiguous external outcome is represented here as {@code FAILED} and recorded in usage /
 * the event history — an external call is never treated as exactly-once, invariant 6).
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
