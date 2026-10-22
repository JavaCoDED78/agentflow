package com.example.agentflow.workflowdefinition.domain;

/**
 * A workflow definition could not be changed because the row in the database no longer matches the
 * expected state: another worker won the lease, an optimistic version (S03-10) was stale, or a
 * uniqueness constraint was violated.
 *
 * <p>Mapped to HTTP 409 (conflict) by the global error handler (S03-11). It lives in the domain
 * layer so the repository can throw it without inverting the persistence/application layering, and
 * so it is not a shared primitive that every module would have to depend on.
 *
 * <p>See ADR-009.
 */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }
}
