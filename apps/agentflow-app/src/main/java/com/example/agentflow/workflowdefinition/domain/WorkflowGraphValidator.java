package com.example.agentflow.workflowdefinition.domain;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Structural validation of a {@link WorkflowGraph} before it is serialized and an activated version
 * is persisted (S05-05).
 *
 * <p>The acceptance criterion is that an invalid payload is rejected <em>before</em> saving: the
 * application calls {@link #validate(WorkflowGraph)} as a gate before the graph ever reaches the
 * persistence layer. The checks are cheap and local:
 *
 * <ul>
 *   <li>the graph must exist and hold at least one step;
 *   <li>every node id is non-blank and <em>unique</em> across the graph;
 *   <li>every node type is non-blank and one of the glossary's allowed node types;
 *   <li>every edge points at an existing node, is not a self-reference and is not duplicated.
 * </ul>
 *
 * <p>Each structural error names the <em>JSON path</em> it comes from ({@code nodes[i]}, {@code
 * nodes[i].id}, {@code edges[j]}, {@code edges[j].from}, {@code edges[j].to}) so a client can
 * locate the offending element. Deeper DAG validation (acyclicity) is the DSL validator's
 * responsibility (S05-06), not this structural gate.
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
    checkNodeIdsUniqueAndPresent(graph);
    checkEdges(graph);
  }

  private static void checkNodeIdsUniqueAndPresent(WorkflowGraph graph) {
    Map<String, Integer> firstSeen = new LinkedHashMap<>();
    for (int i = 0; i < graph.nodes().size(); i++) {
      WorkflowNode node = graph.nodes().get(i);
      if (node == null || node.id() == null || node.id().isBlank()) {
        throw new InvalidGraphException(
            "workflow graph node id must not be blank at nodes[" + i + "]");
      }
      Integer previous = firstSeen.put(node.id(), i);
      if (previous != null) {
        throw new InvalidGraphException(
            "workflow graph node id '"
                + node.id()
                + "' is duplicated: first defined at nodes["
                + previous
                + "] and again at nodes["
                + i
                + "]");
      }
    }
    for (WorkflowNode node : graph.nodes()) {
      if (node.type() == null || node.type().isBlank()) {
        throw new InvalidGraphException("workflow graph node type must not be blank");
      }
      if (!ALLOWED_NODE_TYPES.contains(node.type())) {
        throw new InvalidGraphException("unknown workflow node type: " + node.type());
      }
    }
  }

  private static void checkEdges(WorkflowGraph graph) {
    if (graph.edges().isEmpty()) {
      return;
    }
    Set<String> nodeIds = new HashSet<>();
    for (WorkflowNode node : graph.nodes()) {
      nodeIds.add(node.id());
    }
    Set<String> seenEdges = new HashSet<>();
    for (int j = 0; j < graph.edges().size(); j++) {
      WorkflowEdge edge = graph.edges().get(j);
      String path = "edges[" + j + "]";
      if (edge.from() == null || edge.from().isBlank()) {
        throw new InvalidGraphException(
            "workflow graph edge " + path + " field 'from' must not be blank");
      }
      if (edge.to() == null || edge.to().isBlank()) {
        throw new InvalidGraphException(
            "workflow graph edge " + path + " field 'to' must not be blank");
      }
      if (!nodeIds.contains(edge.from())) {
        throw new InvalidGraphException(
            "workflow graph edge "
                + path
                + " field 'from' references unknown node '"
                + edge.from()
                + "'");
      }
      if (!nodeIds.contains(edge.to())) {
        throw new InvalidGraphException(
            "workflow graph edge "
                + path
                + " field 'to' references unknown node '"
                + edge.to()
                + "'");
      }
      if (edge.from().equals(edge.to())) {
        throw new InvalidGraphException(
            "workflow graph edge "
                + path
                + " is a self-reference (from and to are '"
                + edge.from()
                + "')");
      }
      String key = edge.from() + " -> " + edge.to();
      if (!seenEdges.add(key)) {
        throw new InvalidGraphException(
            "workflow graph edge " + path + " duplicates an earlier edge (" + key + ")");
      }
    }
  }
}
