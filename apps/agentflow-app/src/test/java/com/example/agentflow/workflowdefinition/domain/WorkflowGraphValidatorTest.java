package com.example.agentflow.workflowdefinition.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link WorkflowGraphValidator} structural gate (S03-09): a valid graph with allowed
 * node types passes, while an unknown node type (or an empty / blank graph) is rejected before the
 * graph is serialized or persisted.
 */
class WorkflowGraphValidatorTest {

  private WorkflowGraph graphWith(String... nodeTypes) {
    List<WorkflowNode> nodes =
        List.of(nodeTypes).stream()
            .map(type -> new WorkflowNode("n-" + type, type, Map.of("tool", "x")))
            .toList();
    return new WorkflowGraph(nodes);
  }

  @Test
  void acceptsGraphWithAllowedNodeTypes() {
    WorkflowGraphValidator.validate(
        graphWith("TOOL_CALL", "HUMAN_APPROVAL", "LLM_PLAN", "FINALIZE"));
  }

  @Test
  void rejectsUnknownNodeType() {
    WorkflowGraph graph = graphWith("TOOL_CALL", "EVALUATE_BY_HUMAN");

    assertThatThrownBy(() -> WorkflowGraphValidator.validate(graph))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("EVALUATE_BY_HUMAN");
  }

  @Test
  void rejectsEmptyGraph() {
    assertThatThrownBy(() -> WorkflowGraphValidator.validate(new WorkflowGraph(List.of())))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("at least one node");
  }

  @Test
  void rejectsBlankNodeId() {
    WorkflowGraph graph = new WorkflowGraph(List.of(new WorkflowNode("  ", "TOOL_CALL", Map.of())));

    assertThatThrownBy(() -> WorkflowGraphValidator.validate(graph))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("node id");
  }

  @Test
  void nodeTypesMatchTheGlossary() {
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
