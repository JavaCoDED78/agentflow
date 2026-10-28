package com.example.agentflow.workflowdefinition.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link WorkflowGraphValidator} structural gate (S05-05): a valid graph with allowed
 * node types and well-formed edges passes, while an empty graph, a blank or duplicated node id, an
 * unknown node type and a dangling / duplicate / self-referencing edge are rejected before the
 * graph is serialized or persisted. Each structural error names the JSON path it comes from.
 */
class WorkflowGraphValidatorTest {

  private WorkflowGraph graphWith(String... nodeTypes) {
    List<WorkflowNode> nodes =
        List.of(nodeTypes).stream()
            .map(type -> new WorkflowNode("n-" + type, type, Map.of("tool", "x")))
            .toList();
    return new WorkflowGraph(nodes, List.of());
  }

  @Test
  void accepts_graph_with_allowed_node_types() {
    WorkflowGraphValidator.validate(
        graphWith("TOOL_CALL", "HUMAN_APPROVAL", "LLM_PLAN", "FINALIZE"));
  }

  @Test
  void rejects_unknown_node_type() {
    WorkflowGraph graph = graphWith("TOOL_CALL", "EVALUATE_BY_HUMAN");

    assertThatThrownBy(() -> WorkflowGraphValidator.validate(graph))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("EVALUATE_BY_HUMAN");
  }

  @Test
  void accepts_graph_with_valid_edges() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("plan-1", "LLM_PLAN", Map.of()),
                new WorkflowNode("tool-1", "TOOL_CALL", Map.of()),
                new WorkflowNode("finalize-1", "FINALIZE", Map.of())),
            List.of(
                new WorkflowEdge("plan-1", "tool-1"), new WorkflowEdge("tool-1", "finalize-1")));

    WorkflowGraphValidator.validate(graph);
  }

  @Test
  void rejects_empty_graph() {
    assertThatThrownBy(
            () -> WorkflowGraphValidator.validate(new WorkflowGraph(List.of(), List.of())))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("at least one node");
  }

  @Test
  void rejects_blank_node_id() {
    WorkflowGraph graph =
        new WorkflowGraph(List.of(new WorkflowNode("  ", "TOOL_CALL", Map.of())), List.of());

    assertThatThrownBy(() -> WorkflowGraphValidator.validate(graph))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("nodes[0]");
  }

  @Test
  void rejects_duplicate_node_id() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("plan-1", "LLM_PLAN", Map.of()),
                new WorkflowNode("plan-1", "FINALIZE", Map.of())),
            List.of());

    assertThatThrownBy(() -> WorkflowGraphValidator.validate(graph))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("nodes[0]")
        .hasMessageContaining("nodes[1]")
        .hasMessageContaining("plan-1");
  }

  @Test
  void rejects_dangling_edge() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("plan-1", "LLM_PLAN", Map.of()),
                new WorkflowNode("finalize-1", "FINALIZE", Map.of())),
            List.of(new WorkflowEdge("plan-1", "does-not-exist")));

    assertThatThrownBy(() -> WorkflowGraphValidator.validate(graph))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("edges[0]")
        .hasMessageContaining("does-not-exist");
  }

  @Test
  void rejects_duplicate_edge() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("plan-1", "LLM_PLAN", Map.of()),
                new WorkflowNode("finalize-1", "FINALIZE", Map.of())),
            List.of(
                new WorkflowEdge("plan-1", "finalize-1"),
                new WorkflowEdge("plan-1", "finalize-1")));

    assertThatThrownBy(() -> WorkflowGraphValidator.validate(graph))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("edges[1]")
        .hasMessageContaining("plan-1")
        .hasMessageContaining("finalize-1");
  }

  @Test
  void rejects_self_referencing_edge() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("plan-1", "LLM_PLAN", Map.of()),
                new WorkflowNode("finalize-1", "FINALIZE", Map.of())),
            List.of(new WorkflowEdge("plan-1", "plan-1")));

    assertThatThrownBy(() -> WorkflowGraphValidator.validate(graph))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("edges[0]")
        .hasMessageContaining("self-reference")
        .hasMessageContaining("plan-1");
  }

  @Test
  void topological_order_of_a_linear_graph_is_node_order() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("n-1", "LLM_PLAN", Map.of()),
                new WorkflowNode("n-2", "TOOL_CALL", Map.of()),
                new WorkflowNode("n-3", "FINALIZE", Map.of())),
            List.of(new WorkflowEdge("n-1", "n-2"), new WorkflowEdge("n-2", "n-3")));

    assertThat(WorkflowGraphValidator.topologicalOrder(graph)).containsExactly("n-1", "n-2", "n-3");
  }

  @Test
  void topological_order_orders_nodes_against_their_definition_order() {
    // 'finalize' is declared first yet must be emitted last.
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("finalize", "FINALIZE", Map.of()),
                new WorkflowNode("plan", "LLM_PLAN", Map.of()),
                new WorkflowNode("tool", "TOOL_CALL", Map.of())),
            List.of(new WorkflowEdge("plan", "tool"), new WorkflowEdge("tool", "finalize")));

    assertThat(WorkflowGraphValidator.topologicalOrder(graph))
        .containsExactly("plan", "tool", "finalize");
  }

  @Test
  void topological_order_of_a_branching_dag_respects_dependency_order() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("root", "RETRIEVE_CONTEXT", Map.of()),
                new WorkflowNode("plan-a", "LLM_PLAN", Map.of()),
                new WorkflowNode("plan-b", "LLM_PLAN", Map.of()),
                new WorkflowNode("merge", "TOOL_CALL", Map.of()),
                new WorkflowNode("finalize", "FINALIZE", Map.of())),
            List.of(
                new WorkflowEdge("root", "plan-a"),
                new WorkflowEdge("root", "plan-b"),
                new WorkflowEdge("plan-a", "merge"),
                new WorkflowEdge("plan-b", "merge"),
                new WorkflowEdge("merge", "finalize")));

    List<String> order = WorkflowGraphValidator.topologicalOrder(graph);

    // Every edge's 'from' precedes its 'to', and the definition-order tie (plan-a before
    // plan-b) is resolved deterministically.
    assertThat(order).containsExactly("root", "plan-a", "plan-b", "merge", "finalize");
    assertThat(order.indexOf("root")).isLessThan(order.indexOf("plan-a"));
    assertThat(order.indexOf("plan-a")).isLessThan(order.indexOf("merge"));
    assertThat(order.indexOf("plan-b")).isLessThan(order.indexOf("merge"));
  }

  @Test
  void topological_order_is_deterministic_for_the_same_graph() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("a", "LLM_PLAN", Map.of()),
                new WorkflowNode("b", "LLM_PLAN", Map.of()),
                new WorkflowNode("c", "LLM_PLAN", Map.of())),
            List.of(
                new WorkflowEdge("a", "b"),
                new WorkflowEdge("a", "c"),
                new WorkflowEdge("b", "c")));

    // A definition-order tie-break (a, b) must always win, so two runs agree on the order.
    assertThat(WorkflowGraphValidator.topologicalOrder(graph))
        .isEqualTo(WorkflowGraphValidator.topologicalOrder(graph));
    assertThat(WorkflowGraphValidator.topologicalOrder(graph)).containsExactly("a", "b", "c");
  }

  @Test
  void rejects_a_graph_with_a_two_node_cycle() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("plan-1", "LLM_PLAN", Map.of()),
                new WorkflowNode("tool-1", "TOOL_CALL", Map.of())),
            List.of(new WorkflowEdge("plan-1", "tool-1"), new WorkflowEdge("tool-1", "plan-1")));

    assertThatThrownBy(() -> WorkflowGraphValidator.validate(graph))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("cycle");
  }

  @Test
  void rejects_a_graph_with_a_three_node_cycle() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("a", "LLM_PLAN", Map.of()),
                new WorkflowNode("b", "LLM_PLAN", Map.of()),
                new WorkflowNode("c", "LLM_PLAN", Map.of())),
            List.of(
                new WorkflowEdge("a", "b"),
                new WorkflowEdge("b", "c"),
                new WorkflowEdge("c", "a")));

    assertThatThrownBy(() -> WorkflowGraphValidator.validate(graph))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("cycle");
  }

  @Test
  void rejects_a_graph_over_the_node_limit() {
    List<WorkflowNode> nodes = new ArrayList<>();
    for (int i = 0; i <= WorkflowGraphValidator.MAX_NODES; i++) {
      nodes.add(new WorkflowNode("node-" + i, "LLM_PLAN", Map.of()));
    }
    WorkflowGraph graph = new WorkflowGraph(nodes, List.of());

    assertThatThrownBy(() -> WorkflowGraphValidator.validate(graph))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining(String.valueOf(WorkflowGraphValidator.MAX_NODES + 1))
        .hasMessageContaining("nodes");
  }

  @Test
  void accepts_a_graph_at_the_node_limit() {
    List<WorkflowNode> nodes = new ArrayList<>();
    for (int i = 0; i < WorkflowGraphValidator.MAX_NODES; i++) {
      nodes.add(new WorkflowNode("node-" + i, "LLM_PLAN", Map.of()));
    }
    WorkflowGraph graph = new WorkflowGraph(nodes, List.of());

    WorkflowGraphValidator.validate(graph); // exactly MAX_NODES nodes, no edges
  }

  @Test
  void collect_issues_is_empty_for_a_valid_graph() {
    List<GraphValidationIssue> issues =
        WorkflowGraphValidator.collectIssues(
            new WorkflowGraph(List.of(new WorkflowNode("a", "FINALIZE", Map.of())), List.of()));

    assertThat(issues).isEmpty();
  }

  @Test
  void collect_issues_reports_the_cycle_without_throwing() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("a", "LLM_PLAN", Map.of()),
                new WorkflowNode("b", "LLM_PLAN", Map.of())),
            List.of(new WorkflowEdge("a", "b"), new WorkflowEdge("b", "a")));

    List<GraphValidationIssue> issues = WorkflowGraphValidator.collectIssues(graph);

    assertThat(issues).extracting(GraphValidationIssue::path).contains("$");
    assertThat(issues).extracting(GraphValidationIssue::message).anyMatch(m -> m.contains("cycle"));
  }

  @Test
  void collect_issues_reports_a_dangling_edge_with_its_json_path() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("plan-1", "LLM_PLAN", Map.of()),
                new WorkflowNode("finalize-1", "FINALIZE", Map.of())),
            List.of(new WorkflowEdge("plan-1", "does-not-exist")));

    List<GraphValidationIssue> issues = WorkflowGraphValidator.collectIssues(graph);

    assertThat(issues)
        .extracting(GraphValidationIssue::path)
        .containsExactlyInAnyOrder("edges[0] field 'to'");
    assertThat(issues)
        .extracting(GraphValidationIssue::message)
        .anyMatch(m -> m.contains("does-not-exist"));
  }
}
