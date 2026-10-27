package com.example.agentflow.workflowdefinition.persistence;

import com.example.agentflow.workflowdefinition.domain.InvalidGraphException;
import com.example.agentflow.workflowdefinition.domain.WorkflowEdge;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraphValidator;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses a raw workflow-graph JSON body into the {@link WorkflowGraph} typed model, enforcing the
 * {@code graph_json} contract as a controlled gate rather than a blind round-trip (S05-04).
 *
 * <p>This is the sibling of {@link WorkflowGraphCodec}: the codec owns the storage round-trip of an
 * already-valid graph, while this parser owns the <em>input</em> boundary that turns an untrusted
 * client payload into a validated domain object. It is deliberately strict so a malformed,
 * over-future or unsafe graph is rejected with a classified {@link InvalidGraphException} (mapped
 * to HTTP 400 by the global error handler) instead of a raw Jackson exception that would leak
 * parser internals.
 *
 * <p>The parse does four things, in order, before a {@link WorkflowGraph} is returned:
 *
 * <ol>
 *   <li><b>Schema version.</b> {@code schemaVersion} must equal {@link #SCHEMA_VERSION}; an unknown
 *       or missing version is rejected, so an over-future graph fails closed.
 *   <li><b>Field types.</b> every {@code nodes} entry must be a JSON object with a string {@code
 *       id}, a string {@code type} and an optional object {@code config}; a wrong-typed field or a
 *       non-object node is rejected with the offending field/index in the message.
 *   <li><b>Edge fields.</b> every {@code edges} entry must be a JSON object with two strings,
 *       {@code from} and {@code to}; a wrong-typed field, a missing endpoint or a non-object edge
 *       is rejected with the offending edge/index in the message.
 *   <li><b>Node types.</b> the resulting node {@code type}s are validated by the domain {@link
 *       WorkflowGraphValidator} before the graph is returned, so an unknown node type never reaches
 *       storage.
 * </ol>
 *
 * <p>Node {@code config} values are parsed verbatim into a {@code Map} as untrusted <em>data</em> —
 * never executed (invariant 7). The {@link ObjectMapper} is Jackson 3.x ({@code tools.jackson}),
 * the same one the codec uses; tests inject a fresh instance so the parser stays DB-free.
 */
public class WorkflowGraphParser {

  /**
   * The single defined DSL schema version — the {@code schemaVersion} {@code const} of the
   * contract. Anchored verbatim to {@code contracts/workflow-graph/workflow-graph.json}; an unknown
   * version is rejected here before any domain object is created (S05-04).
   */
  public static final String SCHEMA_VERSION = "1.0.0";

  private final ObjectMapper mapper;

  public WorkflowGraphParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Parses and validates a raw workflow-graph JSON body into a {@link WorkflowGraph}.
   *
   * @param jsonText the raw graph JSON (client input — treated as untrusted data, never executed)
   * @return the typed graph, after the schema-version and node-type gates pass
   * @throws InvalidGraphException if the text is not JSON, the schema version is unknown or
   *     missing, a node or edge has a wrong-typed field, a node carries an unknown node type, a
   *     node id is duplicated, or an edge is dangling, duplicated or a self-reference; the message
   *     names the offending field/index so a client can locate the problem
   */
  public WorkflowGraph parse(String jsonText) {
    JsonNode root = readRoot(jsonText);
    if (root == null || !root.isObject()) {
      throw new InvalidGraphException("workflow graph must be a JSON object");
    }

    validateSchemaVersion(root);
    List<WorkflowNode> nodes = readNodes(root);
    List<WorkflowEdge> edges = readEdges(root);

    // Domain gate: rejects an empty graph, a blank or duplicated node id, a blank/unknown node type
    // and any dangling / duplicate / self-referencing edge before the graph is returned, so the
    // domain object is only ever produced from a validated graph.
    WorkflowGraphValidator.validate(new WorkflowGraph(nodes, edges));
    return new WorkflowGraph(nodes, edges);
  }

  private JsonNode readRoot(String jsonText) {
    if (jsonText == null) {
      throw new InvalidGraphException("workflow graph body must not be null");
    }
    try {
      return mapper.readTree(jsonText);
    } catch (JacksonException e) {
      throw new InvalidGraphException("workflow graph is not valid JSON: " + e.getMessage());
    }
  }

  private void validateSchemaVersion(JsonNode root) {
    JsonNode versionNode = root.path("schemaVersion");
    if (versionNode.isMissingNode()) {
      throw new InvalidGraphException("workflow graph is missing required field 'schemaVersion'");
    }
    if (!versionNode.isTextual()) {
      throw new InvalidGraphException("workflow graph field 'schemaVersion' must be a string");
    }
    String version = versionNode.stringValue();
    if (!SCHEMA_VERSION.equals(version)) {
      throw new InvalidGraphException(
          "unsupported workflow graph schemaVersion '"
              + version
              + "'; expected '"
              + SCHEMA_VERSION
              + "'");
    }
  }

  private List<WorkflowNode> readNodes(JsonNode root) {
    JsonNode nodesNode = root.path("nodes");
    if (nodesNode.isMissingNode()) {
      throw new InvalidGraphException("workflow graph is missing required field 'nodes'");
    }
    if (!nodesNode.isArray()) {
      throw new InvalidGraphException("workflow graph field 'nodes' must be an array");
    }
    List<WorkflowNode> nodes = new ArrayList<>(nodesNode.size());
    for (int i = 0; i < nodesNode.size(); i++) {
      nodes.add(readNode(i, nodesNode.get(i)));
    }
    return nodes;
  }

  private WorkflowNode readNode(int index, JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new InvalidGraphException(
          "workflow graph node at index " + index + " must be a JSON object");
    }
    String id = readStringField(index, node, "id", "node");
    String type = readStringField(index, node, "type", "node");
    Map<String, Object> config = readConfig(index, node);
    return new WorkflowNode(id, type, config);
  }

  private String readStringField(int index, JsonNode node, String field, String entity) {
    JsonNode fieldNode = node.path(field);
    if (fieldNode.isMissingNode()) {
      throw new InvalidGraphException(
          "workflow graph "
              + entity
              + " at index "
              + index
              + " is missing required field '"
              + field
              + "'");
    }
    if (!fieldNode.isTextual()) {
      throw new InvalidGraphException(
          "workflow graph "
              + entity
              + " at index "
              + index
              + " field '"
              + field
              + "' must be a string");
    }
    return fieldNode.stringValue();
  }

  private List<WorkflowEdge> readEdges(JsonNode root) {
    JsonNode edgesNode = root.path("edges");
    if (edgesNode.isMissingNode() || edgesNode.isNull()) {
      // Absent edges mean a single-node graph with no ordering constraints.
      return List.of();
    }
    if (!edgesNode.isArray()) {
      throw new InvalidGraphException("workflow graph field 'edges' must be an array");
    }
    List<WorkflowEdge> edges = new ArrayList<>(edgesNode.size());
    for (int i = 0; i < edgesNode.size(); i++) {
      edges.add(readEdge(i, edgesNode.get(i)));
    }
    return edges;
  }

  private WorkflowEdge readEdge(int index, JsonNode edge) {
    if (edge == null || !edge.isObject()) {
      throw new InvalidGraphException(
          "workflow graph edge at index " + index + " must be a JSON object");
    }
    String from = readStringField(index, edge, "from", "edge");
    String to = readStringField(index, edge, "to", "edge");
    return new WorkflowEdge(from, to);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readConfig(int index, JsonNode node) {
    JsonNode configNode = node.path("config");
    if (configNode.isMissingNode() || configNode.isNull()) {
      return Map.of();
    }
    if (!configNode.isObject()) {
      throw new InvalidGraphException(
          "workflow graph node at index " + index + " field 'config' must be an object");
    }
    return mapper.convertValue(configNode, Map.class);
  }
}
