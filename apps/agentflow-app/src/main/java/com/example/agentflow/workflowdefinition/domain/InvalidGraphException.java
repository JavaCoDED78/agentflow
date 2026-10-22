package com.example.agentflow.workflowdefinition.domain;

/**
 * The workflow graph could not be used because it is structurally invalid: an unknown node type, a
 * blank node id or an empty graph. Thrown by {@link WorkflowGraphValidator} before the graph is
 * serialized or persisted, so an invalid payload never reaches storage (S03-09).
 *
 * <p>Mapped to HTTP 400 (validation) by the global error handler (S03-11). It lives in the domain
 * layer so the codec and validator can throw it without inverting the layering. DAG / cycle checks
 * are the DSL validator's job (S09), not this structural gate.
 *
 * <p>See glossary "Workflow DSL / graph" and ADR-009.
 */
public class InvalidGraphException extends RuntimeException {

  public InvalidGraphException(String message) {
    super(message);
  }
}
