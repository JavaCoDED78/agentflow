package com.example.agentflow.workflowdefinition.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.workflowdefinition.domain.InvalidGraphException;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the {@link WorkflowGraphCodec} JSONB round-trip (S03-09): a serialized graph preserves
 * its meaningful fields (node id, type and config) and an oversized payload is rejected before it
 * is written. DB-free: a fresh {@link ObjectMapper} (Jackson 3.x) backs the codec.
 */
class WorkflowGraphCodecTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final WorkflowGraphCodec codec = new WorkflowGraphCodec(mapper);

  @Test
  void round_trips_preserves_meaningful_fields() {
    WorkflowGraph original =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("n1", "TOOL_CALL", Map.of("tool", "document.search")),
                new WorkflowNode("n2", "HUMAN_APPROVAL", Map.of("reason", "write action"))));

    String json = codec.serialize(original);
    WorkflowGraph restored = codec.deserialize(json);

    assertThat(restored.nodes()).hasSize(2);
    WorkflowNode first = restored.nodes().get(0);
    WorkflowNode second = restored.nodes().get(1);
    assertThat(first.id()).isEqualTo("n1");
    assertThat(first.type()).isEqualTo("TOOL_CALL");
    assertThat(first.config()).containsEntry("tool", "document.search");
    assertThat(second.id()).isEqualTo("n2");
    assertThat(second.type()).isEqualTo("HUMAN_APPROVAL");
    assertThat(second.config()).containsEntry("reason", "write action");
  }

  @Test
  void serialize_for_storage_rejects_graph_larger_than_limit() {
    String padded = "-".repeat(8000); // ~8000 chars
    WorkflowGraph graph =
        new WorkflowGraph(List.of(new WorkflowNode("n1", "TOOL_CALL", Map.of("data", padded))));

    String json = codec.serialize(graph);
    assertThat(json).hasSizeGreaterThan(1000);

    assertThatThrownBy(() -> codec.serializeForStorage(graph, 100))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("exceeds the limit");
  }

  @Test
  void serialize_for_storage_accepts_graph_within_limit() {
    WorkflowGraph graph =
        new WorkflowGraph(List.of(new WorkflowNode("n1", "TOOL_CALL", Map.of("tool", "x"))));

    String json = codec.serializeForStorage(graph, 1_000_000);

    assertThat(codec.deserialize(json)).isEqualTo(graph);
  }

  @Test
  void serialize_for_storage_rejects_misconfigured_limit() {
    WorkflowGraph graph =
        new WorkflowGraph(List.of(new WorkflowNode("n1", "TOOL_CALL", Map.of("tool", "x"))));

    assertThatThrownBy(() -> codec.serializeForStorage(graph, 0))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("at least 1");
  }

  @Test
  void deserialize_rejects_malformed_json() {
    assertThatThrownBy(() -> codec.deserialize("not a graph"))
        .isInstanceOf(InvalidGraphException.class);
  }
}
