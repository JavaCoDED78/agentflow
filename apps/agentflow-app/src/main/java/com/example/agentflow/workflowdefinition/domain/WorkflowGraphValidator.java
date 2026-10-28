package com.example.agentflow.workflowdefinition.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validation of a {@link WorkflowGraph} before it is serialized and an activated version is
 * persisted.
 *
 * <p>The structural gate (S05-05) rejects, before a version is persisted, an empty graph, a blank
 * or duplicated node id, a blank/unknown node type, and a dangling, duplicate or self-referencing
 * edge (each naming the offending JSON path). Acyclicity and the node/edge size limits (S05-06) are
 * enforced here too: {@link #validate(WorkflowGraph)} rejects a cyclic graph and a graph over the
 * contract's {@code 200} nodes / {@code 1000} edges, and {@link #topologicalOrder(WorkflowGraph)}
 * returns a deterministic topological order for a valid (acyclic) graph.
 *
 * <p>The closed set of allowed node types is owned by {@link WorkflowNodeTypeRegistry} (S05-07):
 * {@link #validate(WorkflowGraph)} rejects any node whose type is not one of the glossary's allowed
 * types before a version is persisted, so a client or the untrusted planner cannot register its own
 * executable type.
 *
 * <ul>
 *   <li>the graph must exist and hold at least one step;
 *   <li>the node count is within {@link #MAX_NODES} and the edge count within {@link #MAX_EDGES};
 *   <li>every node id is non-blank and <em>unique</em> across the graph;
 *   <li>every node type is non-blank and one of the glossary's allowed node types;
 *   <li>every edge points at an existing node, is not a self-reference and is not duplicated;
 *   <li>the graph is acyclic (a {@link #topologicalOrder(WorkflowGraph)} exists).
 * </ul>
 *
 * <p>Each structural error names the <em>JSON path</em> it comes from ({@code nodes[i]}, {@code
 * nodes[i].id}, {@code edges[j]}, {@code edges[j].from}, {@code edges[j].to}) so a client can
 * locate the offending element. An invalid payload is rejected before it reaches storage, so the
 * application calls {@link #validate(WorkflowGraph)} as a gate before the graph ever reaches the
 * persistence layer.
 */
public final class WorkflowGraphValidator {

  /**
   * The maximum number of nodes a graph may hold. Anchored verbatim to the contract's {@code
   * workflow-graph.json} ({@code nodes.maxItems = 200}, S05-03); a graph over the limit is rejected
   * by the pre-persistence gate so an oversized graph never reaches storage (S05-06).
   */
  public static final int MAX_NODES = 200;

  /**
   * The maximum number of edges a graph may hold. Anchored verbatim to the contract's {@code
   * workflow-graph.json} ({@code edges.maxItems = 1000}, S05-03); a graph over the limit is
   * rejected by the pre-persistence gate (S05-06).
   */
  public static final int MAX_EDGES = 1000;

  private WorkflowGraphValidator() {}

  /**
   * Validates a graph structurally, for the size limits and for acyclicity (S05-06).
   *
   * @param graph the graph to validate; must not be {@code null}
   * @throws InvalidGraphException if the graph is empty, oversized, or any node has a blank id/type
   *     or an unknown node type, an edge is dangling/duplicate/self-referencing, or the graph
   *     contains a cycle
   */
  public static void validate(WorkflowGraph graph) {
    List<GraphValidationIssue> issues = collectIssues(graph);
    if (!issues.isEmpty()) {
      throw new InvalidGraphException(issues.get(0).message());
    }
  }

  /**
   * Collects <em>every</em> structural problem in a graph, for the size limits and acyclicity
   * (S05-06), instead of throwing on the first.
   *
   * <p>Runs the same checks as {@link #validate(WorkflowGraph)} and returns them all as {@link
   * GraphValidationIssue} values (path + message), so a client can see the full set of issues with
   * their JSON paths in one response — including a cycle, which the throwing gate reports only
   * after reporting any dangling edge first. A valid graph returns an empty list. The throwing
   * {@link #validate(WorkflowGraph)} delegate returns the first collected issue, so the two paths
   * never disagree on what is invalid.
   *
   * @param graph the graph to check; {@code null} is reported as an empty-graph issue
   * @return the collected issues, in check order; empty when the graph is structurally valid
   */
  public static List<GraphValidationIssue> collectIssues(WorkflowGraph graph) {
    List<GraphValidationIssue> issues = new ArrayList<>();
    if (graph == null || graph.isEmpty()) {
      issues.add(GraphValidationIssue.wholeGraph("workflow graph must contain at least one node"));
      return issues;
    }
    if (graph.nodes().size() > MAX_NODES) {
      issues.add(
          GraphValidationIssue.wholeGraph(
              "workflow graph has "
                  + graph.nodes().size()
                  + " nodes, which exceeds the limit of "
                  + MAX_NODES));
    }
    if (graph.edges().size() > MAX_EDGES) {
      issues.add(
          GraphValidationIssue.wholeGraph(
              "workflow graph has "
                  + graph.edges().size()
                  + " edges, which exceeds the limit of "
                  + MAX_EDGES));
    }
    collectNodeIds(graph, issues);
    collectEdges(graph, issues);
    if (edgesAllResolved(graph)) {
      collectCycle(graph, issues);
    }
    return issues;
  }

  /**
   * Computes a deterministic topological order of a valid (acyclic) graph's node ids.
   *
   * <p>Uses Kahn's algorithm with a definition-order tie-break: when several nodes are ready at the
   * same time, the one defined earliest in the graph is emitted first, so the order is fully
   * reproducible for a given input (S05-06). The graph is assumed structurally valid (every edge
   * references an existing node id) — {@link #validate(WorkflowGraph)} guarantees that before this
   * is meaningful.
   *
   * @param graph a valid graph; its edges must reference existing node ids
   * @return the node ids in an order where every edge {@code from → to} has {@code from} first
   * @throws InvalidGraphException if the graph contains a cycle
   */
  public static List<String> topologicalOrder(WorkflowGraph graph) {
    int n = graph.nodes().size();
    Map<String, Integer> index = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      index.put(graph.nodes().get(i).id(), i);
    }
    List<List<Integer>> adjacency = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      adjacency.add(new ArrayList<>());
    }
    int[] indegree = new int[n];
    for (WorkflowEdge edge : graph.edges()) {
      int from = index.get(edge.from());
      int to = index.get(edge.to());
      adjacency.get(from).add(to);
      indegree[to]++;
    }
    TreeSet<Integer> ready = new TreeSet<>();
    for (int i = 0; i < n; i++) {
      if (indegree[i] == 0) {
        ready.add(i);
      }
    }
    List<String> order = new ArrayList<>(n);
    while (!ready.isEmpty()) {
      int current = ready.pollFirst();
      order.add(graph.nodes().get(current).id());
      for (int successor : adjacency.get(current)) {
        if (--indegree[successor] == 0) {
          ready.add(successor);
        }
      }
    }
    if (order.size() != n) {
      throw new InvalidGraphException(
          "workflow graph contains a cycle (a workflow graph must be an acyclic DAG)");
    }
    return order;
  }

  /**
   * @return {@code true} when every edge references an existing node id — the precondition for a
   *     meaningful acyclicity check (a dangling endpoint is reported as an edge issue, and {@link
   *     #topologicalOrder(WorkflowGraph)} would dereference it)
   */
  private static boolean edgesAllResolved(WorkflowGraph graph) {
    if (graph.edges().isEmpty()) {
      return true;
    }
    Set<String> nodeIds = new HashSet<>();
    for (WorkflowNode node : graph.nodes()) {
      nodeIds.add(node.id());
    }
    for (WorkflowEdge edge : graph.edges()) {
      if (!nodeIds.contains(edge.from()) || !nodeIds.contains(edge.to())) {
        return false;
      }
    }
    return true;
  }

  private static void collectCycle(WorkflowGraph graph, List<GraphValidationIssue> issues) {
    try {
      topologicalOrder(graph);
    } catch (InvalidGraphException e) {
      issues.add(GraphValidationIssue.wholeGraph(e.getMessage()));
    }
  }

  private static void collectNodeIds(WorkflowGraph graph, List<GraphValidationIssue> issues) {
    Map<String, Integer> firstSeen = new LinkedHashMap<>();
    for (int i = 0; i < graph.nodes().size(); i++) {
      WorkflowNode node = graph.nodes().get(i);
      if (node == null || node.id() == null || node.id().isBlank()) {
        issues.add(
            new GraphValidationIssue(
                "nodes[" + i + "]",
                "workflow graph node id must not be blank at nodes[" + i + "]"));
        continue;
      }
      Integer previous = firstSeen.put(node.id(), i);
      if (previous != null) {
        issues.add(
            new GraphValidationIssue(
                "nodes[" + i + "]",
                "workflow graph node id '"
                    + node.id()
                    + "' is duplicated: first defined at nodes["
                    + previous
                    + "] and again at nodes["
                    + i
                    + "]"));
      }
    }
    for (WorkflowNode node : graph.nodes()) {
      if (node.type() == null || node.type().isBlank()) {
        issues.add(
            new GraphValidationIssue(
                "nodes[" + nodeIndex(graph, node) + "]",
                "workflow graph node type must not be blank"));
      } else if (!WorkflowNodeTypeRegistry.contains(node.type())) {
        issues.add(
            new GraphValidationIssue(
                "nodes[" + nodeIndex(graph, node) + "]",
                "unknown workflow node type: " + node.type()));
      }
    }
  }

  /**
   * @param graph the graph being checked
   * @param node a node whose {@code type} failed validation
   * @return the zero-based index of the node within {@code graph.nodes()} for a readable path
   */
  private static int nodeIndex(WorkflowGraph graph, WorkflowNode node) {
    for (int i = 0; i < graph.nodes().size(); i++) {
      if (graph.nodes().get(i) == node) {
        return i;
      }
    }
    return -1;
  }

  private static void collectEdges(WorkflowGraph graph, List<GraphValidationIssue> issues) {
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
        issues.add(
            new GraphValidationIssue(
                path + " field 'from'",
                "workflow graph edge " + path + " field 'from' must not be blank"));
      } else if (!nodeIds.contains(edge.from())) {
        issues.add(
            new GraphValidationIssue(
                path + " field 'from'",
                "workflow graph edge "
                    + path
                    + " field 'from' references unknown node '"
                    + edge.from()
                    + "'"));
      }
      if (edge.to() == null || edge.to().isBlank()) {
        issues.add(
            new GraphValidationIssue(
                path + " field 'to'",
                "workflow graph edge " + path + " field 'to' must not be blank"));
      } else if (!nodeIds.contains(edge.to())) {
        issues.add(
            new GraphValidationIssue(
                path + " field 'to'",
                "workflow graph edge "
                    + path
                    + " field 'to' references unknown node '"
                    + edge.to()
                    + "'"));
      }
      if (edge.from() != null && edge.to() != null && edge.from().equals(edge.to())) {
        issues.add(
            new GraphValidationIssue(
                path,
                "workflow graph edge "
                    + path
                    + " is a self-reference (from and to are '"
                    + edge.from()
                    + "')"));
      }
      String key = edge.from() + " -> " + edge.to();
      if (!seenEdges.add(key)) {
        issues.add(
            new GraphValidationIssue(
                path, "workflow graph edge " + path + " duplicates an earlier edge (" + key + ")"));
      }
    }
  }
}
