package com.example.agentflow.workflowdefinition.persistence;

import com.example.agentflow.workflowdefinition.domain.InvalidGraphException;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes a {@link WorkflowGraph} to and from the JSON text stored in the {@code
 * workflow_definition_versions.graph_json} JSONB column (S03-09).
 *
 * <p>This is the round-trip mapping ADR-009 calls for: the application serializes the graph to a
 * string before insert and the JDBC layer reads the JSONB column back into a {@link WorkflowGraph}
 * with {@link WorkflowGraphColumnMapper}. The {@link ObjectMapper} is Jackson 3.x ({@code
 * tools.jackson}) — the Boot 4.1.1 graph — and round-trips {@code record}s and their nested {@code
 * Map} payloads verbatim (verified empirically, no extra module needed).
 *
 * <p>The codec also enforces the payload-size bound: the serialized graph must not exceed the
 * configured {@code agentflow.workflow.graph.max-bytes} (see {@link #serializeForStorage}).
 * Oversized graphs are rejected here, before they are written, so a runaway payload never fills the
 * database.
 */
public class WorkflowGraphCodec {

  private final ObjectMapper mapper;

  public WorkflowGraphCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Serializes a graph to its JSON text form.
   *
   * @param graph the graph to serialize
   * @return the JSON text to store in the {@code graph_json} column
   * @throws InvalidGraphException if the graph cannot be serialized (should not happen after {@link
   *     com.example.agentflow.workflowdefinition.domain.WorkflowGraphValidator} passes)
   */
  public String serialize(WorkflowGraph graph) {
    try {
      return mapper.writeValueAsString(graph);
    } catch (JacksonException e) {
      throw new InvalidGraphException("workflow graph could not be serialized: " + e.getMessage());
    }
  }

  /**
   * Deserializes the stored JSON text back into a {@link WorkflowGraph}.
   *
   * @param json the JSONB column text
   * @return the restored graph
   * @throws InvalidGraphException if the text is not a well-formed graph JSON
   */
  public WorkflowGraph deserialize(String json) {
    try {
      return mapper.readValue(json, WorkflowGraph.class);
    } catch (JacksonException e) {
      throw new InvalidGraphException("stored workflow graph is not valid JSON: " + e.getMessage());
    }
  }

  /**
   * Serializes a graph and enforces the configured payload-size bound in one step.
   *
   * @param graph the graph to serialize
   * @param maxBytes the configured maximum serialized size in bytes
   * @return the JSON text, guaranteed to be {@code <= maxBytes}
   * @throws InvalidGraphException if {@code maxBytes} is misconfigured or the serialized graph
   *     exceeds the bound
   */
  public String serializeForStorage(WorkflowGraph graph, int maxBytes) {
    if (maxBytes < 1) {
      throw new InvalidGraphException("workflow graph max-bytes must be at least 1");
    }
    String json = serialize(graph);
    int size = json.getBytes(StandardCharsets.UTF_8).length;
    if (size > maxBytes) {
      throw new InvalidGraphException(
          "workflow graph serialized size ("
              + size
              + " bytes) exceeds the limit ("
              + maxBytes
              + " bytes)");
    }
    return json;
  }
}
