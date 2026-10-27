package com.example.agentflow.workflowdefinition.domain;

/**
 * The workflow graph could not be used because it is structurally invalid: an empty graph, a blank
 * or duplicated node id, an unknown node type, or a dangling, duplicated or self-referencing edge.
 * Thrown by {@link WorkflowGraphValidator} before the graph is serialized or persisted, so an
 * invalid payload never reaches storage (S05-05).
 *
 * <p>Mapped to HTTP 400 (validation) by the global error handler (S03-11). It lives in the domain
 * layer so the codec and validator can throw it without inverting the layering. The structural gate
 * (S05-05) and the DAG gate (S05-06) — node/edge size limits and acyclicity — are enforced here, so
 * an invalid graph never reaches storage.
 *
 * <p>See glossary "Workflow DSL / graph" and ADR-009.
 */
public class InvalidGraphException extends RuntimeException {

  public InvalidGraphException(String message) {
    super(message);
  }
}
