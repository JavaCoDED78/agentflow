package com.example.agentflow.workflowexecution.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The step-level state machine of {@code workflowexecution} (S05-10).
 *
 * <p>A step is a single node execution within a run (glossary section 5). The full state set, the
 * terminal states, the diagram and the per-transition event/command/condition table are the design
 * recorded in {@code docs/architecture/step-state-machine.md} (S05-02); this enum is the
 * free-standing Java implementation of that design.
 *
 * <p>The canonical states are {@code PENDING -> READY -> RUNNING -> SUCCEEDED}, with {@code
 * RETRY_WAIT}, {@code WAITING_FOR_APPROVAL}, {@code SKIPPED} and {@code CANCELLED} as the
 * alternative/terminal paths. Four terminal states are <b>absorbing</b>: a step never leaves {@code
 * SUCCEEDED}, {@code FAILED}, {@code SKIPPED} or {@code CANCELLED}, so a second transition on a
 * step already in one of them is a domain error.
 *
 * <p>The allowed outgoing edges per state (the transition table, {@code step-state-machine.md}
 * section 3):
 *
 * <pre>
 *   PENDING                -> READY, SKIPPED, CANCELLED
 *   READY                  -> RUNNING, SKIPPED, CANCELLED
 *   RUNNING                -> SUCCEEDED, RETRY_WAIT, WAITING_FOR_APPROVAL, FAILED, CANCELLED
 *   WAITING_FOR_APPROVAL   -> RUNNING, FAILED, CANCELLED
 *   RETRY_WAIT             -> RUNNING
 *   SUCCEEDED, FAILED, SKIPPED, CANCELLED -> (terminal: no outgoing edge)
 * </pre>
 *
 * <p>Every edge is triggered by an explicit event/command with a guard condition. The guard is the
 * transition service's responsibility (S05-11): {@link #canTransitionTo} only checks that the
 * {@code (from, to)} pair is an allowed edge &mdash; it never evaluates the event, the
 * lease/fencing, the attempt budget or the tenant scope. Because the enum exposes no way to set the
 * state, a step status can change only through the single transition service, never via a direct
 * assignment (invariant 4).
 *
 * <p>The allowed successors are held in {@link #ALLOWED_SUCCESSORS}, an {@link EnumMap} keyed by
 * the state with an {@link EnumSet} of successor states. The map is built in a static initializer
 * (not in a per-constant constructor argument) so it can express the cyclic edges &mdash; e.g.
 * {@code RUNNING -> WAITING_FOR_APPROVAL} and {@code WAITING_FOR_APPROVAL -> RUNNING} &mdash; which
 * cannot be written inline because enum constants are initialized in declaration order.
 *
 * <p>This type is pure: no Spring, no persistence, no I/O. It holds only transition validation,
 * never behaviour that mutates state, so it can live in the domain layer and be unit tested without
 * a database. The run-state aggregation (run-state-machine.md section 4) derives the run terminal
 * states from step states; this enum and the run machine are reconciled so the two never disagree
 * (S05-02 / S05-11). See {@link #assertAllowed} for the throwing entry point the transition service
 * calls.
 *
 * @see WorkflowRunState the run-level counterpart, reconciled against this
 * @see step-state-machine.md the design this enum implements (S05-02)
 * @see S05-11 the transition service that applies allowed transitions with the optimistic lock
 */
public enum WorkflowStepState {

  /** The step exists but has not been scheduled; it awaits its upstream dependencies. */
  PENDING,

  /** All dependencies completed and the step is scheduled; it awaits a worker claiming it. */
  READY,

  /** A worker holds the lease and is executing the step. */
  RUNNING,

  /**
   * A {@code HUMAN_APPROVAL} node stopped the step; its lease is released while it awaits a
   * decision.
   */
  WAITING_FOR_APPROVAL,

  /** The step failed with a retryable error; it is in backoff before a re-claim. */
  RETRY_WAIT,

  /** The step completed successfully and its output reference was persisted. */
  SUCCEEDED,

  /** The step failed permanently, its retry budget was exhausted, or a policy check rejected it. */
  FAILED,

  /**
   * The step will not execute: a failed predecessor cascades the skip, or an edge predicate was
   * false.
   */
  SKIPPED,

  /** The step was cancelled while draining. */
  CANCELLED;

  private static final Map<WorkflowStepState, Set<WorkflowStepState>> ALLOWED_SUCCESSORS =
      buildAllowedSuccessors();

  private static Map<WorkflowStepState, Set<WorkflowStepState>> buildAllowedSuccessors() {
    Map<WorkflowStepState, Set<WorkflowStepState>> map = new EnumMap<>(WorkflowStepState.class);
    map.put(PENDING, EnumSet.of(READY, SKIPPED, CANCELLED));
    map.put(READY, EnumSet.of(RUNNING, SKIPPED, CANCELLED));
    map.put(RUNNING, EnumSet.of(SUCCEEDED, RETRY_WAIT, WAITING_FOR_APPROVAL, FAILED, CANCELLED));
    map.put(WAITING_FOR_APPROVAL, EnumSet.of(RUNNING, FAILED, CANCELLED));
    map.put(RETRY_WAIT, EnumSet.of(RUNNING));
    map.put(SUCCEEDED, EnumSet.noneOf(WorkflowStepState.class));
    map.put(FAILED, EnumSet.noneOf(WorkflowStepState.class));
    map.put(SKIPPED, EnumSet.noneOf(WorkflowStepState.class));
    map.put(CANCELLED, EnumSet.noneOf(WorkflowStepState.class));
    return Map.copyOf(map);
  }

  /**
   * @return {@code true} for the four terminal states ({@link #SUCCEEDED}, {@link #FAILED}, {@link
   *     #SKIPPED}, {@link #CANCELLED}), which have no outgoing edge and therefore never transition
   *     again
   */
  public boolean isTerminal() {
    return ALLOWED_SUCCESSORS.get(this).isEmpty();
  }

  /**
   * Checks whether {@code to} is an allowed next state of {@code this} state, per the transition
   * table (step-state-machine.md section 3). Terminal states allow no outgoing edge, including to
   * themselves, so a re-delivered event on an already-terminal step is not an allowed transition
   * here (idempotency is the transition service's concern, S05-11).
   *
   * @param to the candidate next state
   * @return {@code true} only when {@code this -> to} is an edge of the transition table
   */
  public boolean canTransitionTo(WorkflowStepState to) {
    return to != null && ALLOWED_SUCCESSORS.get(this).contains(to);
  }

  /**
   * Asserts that {@code from -> to} is an allowed step-state transition.
   *
   * @param from the current step state
   * @param to the requested next state
   * @throws InvalidStateException if either state is {@code null} or {@code from -> to} is not an
   *     allowed edge (a forbidden transition is a domain error, S05-10)
   */
  public static void assertAllowed(WorkflowStepState from, WorkflowStepState to) {
    if (from == null || to == null) {
      throw new InvalidStateException(
          "step state transition requires both the from and the to state");
    }
    if (!from.canTransitionTo(to)) {
      throw new InvalidStateException(
          "step state transition is not allowed: " + from + " -> " + to);
    }
  }
}
