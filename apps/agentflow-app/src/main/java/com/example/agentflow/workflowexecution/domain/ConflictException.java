package com.example.agentflow.workflowexecution.domain;

/**
 * A run or step could not move to the requested state because a conditional update matched no row:
 * the row is gone, it belongs to another tenant, it is no longer in the expected state, or an
 * optimistic guard is stale — {@code version} on the run ({@code workflow_runs.version}) or the
 * {@code lease_epoch} fence on the step ({@code workflow_step_runs.lease_epoch}). That means
 * another writer won the same transition, so the update is refused rather than silently overwriting
 * the current state (invariant 4 / the run-state-machine conditional-update rule).
 *
 * <p>Mapped to HTTP 409 (conflict) by the transition service (S05-11), the single point where state
 * changes are applied. It lives in the domain layer so the repository can throw it without
 * inverting the persistence/application layering, and so it is not a shared primitive every module
 * would have to depend on (a sibling of {@link InvalidStateException}, rendered HTTP 409 by the
 * global error handler via the owning application layer, S03-11).
 *
 * <p>See the run-state-machine design ({@code docs/architecture/run-state-machine.md} section 4)
 * and the {@code workflowexecution} module AGENTS.md.
 */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }
}
