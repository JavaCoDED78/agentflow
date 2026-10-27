package com.example.agentflow.workflowdefinition.domain;

import java.util.List;

/**
 * The declarative, typed DAG stored in the {@code workflow_definition_versions.graph_json} column
 * (glossary "Workflow DSL / graph").
 *
 * <p>It is the domain model the {@code workflowdefinition} module validates and round-trips through
 * JSONB (S03-09). It is deliberately small: a graph is an ordered list of {@link WorkflowNode typed
 * steps}, each carrying an id, a node type (one of the glossary's allowed node types) and a
 * free-form {@code config} map with the step's parameters. The graph is a value object — no
 * behaviour that mutates state — so it can safely cross the owning module's public API boundary.
 *
 * <p>It is <em>not</em> the API contract: the client-facing DTO is a separate type introduced with
 * the create/activate endpoints (S03-13) and validated against the node-type/structure rules by the
 * DSL validator (S09). This record is the persistence-facing representation only.
 */
public record WorkflowGraph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {

  /**
   * @param nodes the ordered steps of the graph; never {@code null}
   * @param edges the execution-order edges of the graph; never {@code null}
   */
  public WorkflowGraph {
    if (nodes == null) {
      throw new IllegalArgumentException("workflow graph nodes must not be null");
    }
    if (edges == null) {
      throw new IllegalArgumentException("workflow graph edges must not be null");
    }
  }

  /**
   * @return {@code true} when the graph has no steps (an empty graph is not a runnable workflow)
   */
  public boolean isEmpty() {
    return nodes.isEmpty();
  }
}
