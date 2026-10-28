package com.example.agentflow.workflowdefinition.domain;

/**
 * Thrown when an operation tries to change an already {@link WorkflowVersionStatus#ACTIVATED}
 * version, or to activate a version whose pinned {@code checksum} does not match the graph.
 *
 * <p>An activated version is immutable (invariant): its {@code version} number, {@code checksum}
 * and graph can never change. The {@code workflowdefinition} application layer maps this to an HTTP
 * {@code 409 Conflict} (see {@code GlobalExceptionHandler}); the domain exception lives here so it
 * never leaks across the module boundary.
 */
public class ImmutableVersionException extends RuntimeException {

  /**
   * @param message the human-readable reason the version cannot be changed (never carries tenant or
   *     run identifiers, per the error taxonomy)
   */
  public ImmutableVersionException(String message) {
    super(message);
  }
}
