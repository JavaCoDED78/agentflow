package com.example.agentflow.workflowexecution.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The run-level state machine of {@code workflowexecution} (S05-10).
 *
 * <p>A run is one execution of a pinned workflow version (glossary section 5). The full state set,
 * the terminal states, the diagram and the per-transition event/command/condition table are the
 * design recorded in {@code docs/architecture/run-state-machine.md} (S05-01); this enum is the
 * free-standing Java implementation of that design.
 *
 * <p>The canonical states are {@code QUEUED -> RUNNING -> SUCCEEDED / FAILED / CANCELLED}, with
 * {@code WAITING_FOR_APPROVAL} (human-approval pause), {@code RETRY_WAIT} (retry backoff) and
 * {@code CANCEL_REQUESTED} (cancellation in flight) as intermediate states. Three terminal states
 * are <b>absorbing</b>: a run never leaves {@code SUCCEEDED}, {@code FAILED} or {@code CANCELLED},
 * so a second transition on a run already in one of them is a domain error.
 *
 * <p>The allowed outgoing edges per state (the transition table, {@code run-state-machine.md}
 * section 3):
 *
 * <pre>
 *   QUEUED                 -> RUNNING, CANCEL_REQUESTED
 *   RUNNING                -> WAITING_FOR_APPROVAL, RETRY_WAIT, SUCCEEDED, FAILED, CANCEL_REQUESTED
 *   WAITING_FOR_APPROVAL   -> RUNNING, FAILED, CANCEL_REQUESTED
 *   RETRY_WAIT             -> RUNNING, CANCEL_REQUESTED
 *   CANCEL_REQUESTED       -> CANCELLED
 *   SUCCEEDED, FAILED, CANCELLED -> (terminal: no outgoing edge)
 * </pre>
 *
 * <p>Every edge is triggered by an explicit event/command with a guard condition. The guard is the
 * transition service's responsibility (S05-11): {@link #canTransitionTo} only checks that the
 * {@code (from, to)} pair is an allowed edge &mdash; it never evaluates the event, the lease, the
 * attempt budget or the tenant scope. Because the enum exposes no way to set the state, a run
 * status can change only through the single transition service, never via a direct assignment
 * (invariant 4).
 *
 * <p>The allowed successors are held in {@link #ALLOWED_SUCCESSORS}, an {@link EnumMap} keyed by
 * the state with an {@link EnumSet} of successor states. The map is built in a static initializer
 * (not in a per-constant constructor argument) so it can express the cyclic edges &mdash; e.g.
 * {@code RUNNING -> WAITING_FOR_APPROVAL} and {@code WAITING_FOR_APPROVAL -> RUNNING} &mdash; which
 * cannot be written inline because enum constants are initialized in declaration order.
 *
 * <p>This type is pure: no Spring, no persistence, no I/O. It holds only transition validation,
 * never behaviour that mutates state, so it can live in the domain layer and be unit tested without
 * a database. See {@link #assertAllowed} for the throwing entry point the transition service calls.
 *
 * @see WorkflowStepState the step-level counterpart
 * @see run-state-machine.md the design this enum implements (S05-01)
 * @see S05-11 the transition service that applies allowed transitions with the optimistic lock
 */
public enum WorkflowRunState {

  /** The run was created and awaits a worker to claim it. */
  QUEUED,

  /** A worker holds the lease and is executing steps. */
  RUNNING,

  /** A {@code HUMAN_APPROVAL} node stopped the run; the worker lease is released while it waits. */
  WAITING_FOR_APPROVAL,

  /** A step failed with a retryable error; the run is in backoff before it is retried. */
  RETRY_WAIT,

  /** Every step completed successfully. */
  SUCCEEDED,

  /** A step failed permanently, a retry budget was exhausted, or an approval was rejected. */
  FAILED,

  /** Cancellation was requested; the run moves to {@link #CANCELLED} once draining completes. */
  CANCEL_REQUESTED,

  /** The run was cancelled. */
  CANCELLED;

  private static final Map<WorkflowRunState, Set<WorkflowRunState>> ALLOWED_SUCCESSORS =
      buildAllowedSuccessors();

  private static Map<WorkflowRunState, Set<WorkflowRunState>> buildAllowedSuccessors() {
    Map<WorkflowRunState, Set<WorkflowRunState>> map = new EnumMap<>(WorkflowRunState.class);
    map.put(QUEUED, EnumSet.of(RUNNING, CANCEL_REQUESTED));
    map.put(
        RUNNING, EnumSet.of(WAITING_FOR_APPROVAL, RETRY_WAIT, SUCCEEDED, FAILED, CANCEL_REQUESTED));
    map.put(WAITING_FOR_APPROVAL, EnumSet.of(RUNNING, FAILED, CANCEL_REQUESTED));
    map.put(RETRY_WAIT, EnumSet.of(RUNNING, CANCEL_REQUESTED));
    map.put(SUCCEEDED, EnumSet.noneOf(WorkflowRunState.class));
    map.put(FAILED, EnumSet.noneOf(WorkflowRunState.class));
    map.put(CANCEL_REQUESTED, EnumSet.of(CANCELLED));
    map.put(CANCELLED, EnumSet.noneOf(WorkflowRunState.class));
    return Map.copyOf(map);
  }

  /**
   * @return {@code true} for the three terminal states ({@link #SUCCEEDED}, {@link #FAILED}, {@link
   *     #CANCELLED}), which have no outgoing edge and therefore never transition again
   */
  public boolean isTerminal() {
    return ALLOWED_SUCCESSORS.get(this).isEmpty();
  }

  /**
   * Checks whether {@code to} is an allowed next state of {@code this} state, per the transition
   * table (run-state-machine.md section 3). Terminal states allow no outgoing edge, including to
   * themselves, so a re-delivered event on an already-terminal run is not an allowed transition
   * here (idempotency is the transition service's concern, S05-11).
   *
   * @param to the candidate next state
   * @return {@code true} only when {@code this -> to} is an edge of the transition table
   */
  public boolean canTransitionTo(WorkflowRunState to) {
    return to != null && ALLOWED_SUCCESSORS.get(this).contains(to);
  }

  /**
   * Asserts that {@code from -> to} is an allowed run-state transition.
   *
   * @param from the current run state
   * @param to the requested next state
   * @throws InvalidStateException if either state is {@code null} or {@code from -> to} is not an
   *     allowed edge (a forbidden transition is a domain error, S05-10)
   */
  public static void assertAllowed(WorkflowRunState from, WorkflowRunState to) {
    if (from == null || to == null) {
      throw new InvalidStateException(
          "run state transition requires both the from and the to state");
    }
    if (!from.canTransitionTo(to)) {
      throw new InvalidStateException("run state transition is not allowed: " + from + " -> " + to);
    }
  }
}
