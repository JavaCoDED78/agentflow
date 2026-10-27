package com.example.agentflow.workflowexecution.domain;

/**
 * A run or step could not move to the requested state because the transition is not allowed by the
 * state machine (S05-10): the {@code (from, to)} pair is not an edge of the transition table, or
 * one of the states was {@code null}. Thrown by {@link WorkflowRunState#assertAllowed} and {@link
 * WorkflowStepState#assertAllowed}.
 *
 * <p>It lives in the domain layer so the state machine is the single gate: a controller or Kafka
 * listener can never move a run/step past the domain, and the single transition service (S05-11) is
 * the only path that reaches persistence after a transition has passed this gate. It is a plain
 * runtime error with no Spring or persistence dependency, so it is thrown from the DB-free domain
 * layer rather than being a shared primitive every module would depend on.
 *
 * <p>The transition service (S05-11) decides how the global error handler surfaces it to a client.
 */
public class InvalidStateException extends RuntimeException {

  public InvalidStateException(String message) {
    super(message);
  }
}
