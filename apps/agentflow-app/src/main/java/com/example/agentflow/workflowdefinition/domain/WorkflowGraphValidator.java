package com.example.agentflow.workflowdefinition.domain;

import java.util.Set;

/**
 * Structural validation of a {@link WorkflowGraph} before it is serialized and an activated version
 * is persisted (S03-09).
 *
 * <p>The acceptance criterion is that an invalid payload is rejected <em>before</em> saving: the
 * application calls {@link #validate(WorkflowGraph)} as a gate before the graph ever reaches the
 * persistence layer. The checks are cheap and local — the graph must exist and hold at least one
 * step, every step must have a non-blank id and type, and every type must be one of the glossary's
 * allowed node types. Deeper DAG validation (cycles, connectivity, the node graph itself) is the
 * DSL validator's responsibility (S09), not this structural gate.
 */
public final class WorkflowGraphValidator {

  /**
   * The allowed node types in the MVP. Anchored verbatim to the domain glossary "Workflow DSL /
   * graph" (`RETRIEVE_CONTEXT`, `LLM_PLAN`, `TOOL_CALL`, `HUMAN_APPROVAL`, `LLM_EVALUATE`,
   * `FINALIZE`); the DSL validator (S09) is the authority for this set going forward.
   */
  public static final Set<String> ALLOWED_NODE_TYPES =
      Set.of(
          "RETRIEVE_CONTEXT",
          "LLM_PLAN",
          "TOOL_CALL",
          "HUMAN_APPROVAL",
          "LLM_EVALUATE",
          "FINALIZE");

  private WorkflowGraphValidator() {}

  /**
   * Validates a graph structurally.
   *
   * @param graph the graph to validate; must not be {@code null}
   * @throws InvalidGraphException if the graph is empty, or any node has a blank id/type or an
   *     unknown node type
   */
  public static void validate(WorkflowGraph graph) {
    if (graph == null || graph.isEmpty()) {
      throw new InvalidGraphException("workflow graph must contain at least one node");
    }
    for (WorkflowNode node : graph.nodes()) {
      if (node == null || node.id() == null || node.id().isBlank()) {
        throw new InvalidGraphException("workflow graph node id must not be blank");
      }
      if (node.type() == null || node.type().isBlank()) {
        throw new InvalidGraphException("workflow graph node type must not be blank");
      }
      if (!ALLOWED_NODE_TYPES.contains(node.type())) {
        throw new InvalidGraphException("unknown workflow node type: " + node.type());
      }
    }
  }
}
