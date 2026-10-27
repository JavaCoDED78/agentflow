package com.example.agentflow.workflowdefinition.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  void node_types_match_the_glossary() {
    // The allowed set is anchored verbatim to the domain glossary "Workflow DSL / graph".
    assertThat(WorkflowGraphValidator.ALLOWED_NODE_TYPES)
        .containsExactlyInAnyOrder(
            "RETRIEVE_CONTEXT",
            "LLM_PLAN",
            "TOOL_CALL",
            "HUMAN_APPROVAL",
            "LLM_EVALUATE",
            "FINALIZE");
  }
}
