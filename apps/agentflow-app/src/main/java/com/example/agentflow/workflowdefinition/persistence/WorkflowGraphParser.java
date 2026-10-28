package com.example.agentflow.workflowdefinition.persistence;

import com.example.agentflow.workflowdefinition.domain.GraphValidationIssue;
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
 * <p>There are two entry points that share this single gate (S05-13). {@link #parse(String)} is the
 * throwing use: it returns the typed graph, or throws {@link InvalidGraphException} on the first
 * problem it finds. {@link #collectIssues(String)} is the validation endpoint's path: it collects
 * <em>every</em> problem as a {@link GraphValidationIssue} (JSON path + message) without creating a
 * version, so a client can confirm validity or fix its payload with no side effect.
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
    List<GraphValidationIssue> issues = collectIssues(jsonText);
    if (!issues.isEmpty()) {
      // First issue carries the same message the old parse() threw, so the 400 body is unchanged.
      throw new InvalidGraphException(issues.get(0).message());
    }
    return analyse(jsonText, issues);
  }

  /**
   * Collects <em>every</em> validation problem in a raw workflow-graph payload (S05-13), instead of
   * throwing on the first.
   *
   * <p>Runs the exact same input gate as {@link #parse(String)} — the schema-version check, the
   * node/edge field-type checks and the domain {@link
   * WorkflowGraphValidator#collectIssues(WorkflowGraph)} gate — and returns all of them as {@link
   * GraphValidationIssue} values (JSON path + message). A valid payload returns an empty list, so
   * the caller can confirm validity without a version being created (no side effect). The throwing
   * {@link #parse(String)} returns the first issue, so the two paths never disagree on what is
   * invalid.
   *
   * @param jsonText the raw graph JSON (client input — treated as untrusted data, never executed)
   * @return the collected issues, in check order; empty when the payload is valid
   */
  public List<GraphValidationIssue> collectIssues(String jsonText) {
    List<GraphValidationIssue> issues = new ArrayList<>();
    analyse(jsonText, issues);
    return issues;
  }

  /**
   * Runs the single input gate (schema version, node/edge field types) plus — only when that found
   * nothing — the structural domain gate, collecting every issue into {@code issues}.
   *
   * @param jsonText the raw graph JSON (client input — treated as untrusted data, never executed)
   * @param issues the collector to fill; must not be {@code null}
   * @return the parsed graph; its nodes/edges are meaningful only when {@code issues} is empty, but
   *     the object is always returned for convenience (the {@link #parse(String)} caller ignores it
   *     when it threw on the first issue)
   */
  private WorkflowGraph analyse(String jsonText, List<GraphValidationIssue> issues) {
    JsonNode root = readRoot(jsonText, issues);
    if (root == null || !root.isObject()) {
      issues.add(GraphValidationIssue.wholeGraph("workflow graph must be a JSON object"));
      return new WorkflowGraph(List.of(), List.of());
    }
    validateSchemaVersion(root, issues);
    List<WorkflowNode> nodes = readNodes(root, issues);
    List<WorkflowEdge> edges = readEdges(root, issues);
    if (issues.isEmpty()) {
      // No input-level problems: run the structural domain gate (node ids/types, edges,
      // acyclicity).
      issues.addAll(WorkflowGraphValidator.collectIssues(new WorkflowGraph(nodes, edges)));
    }
    return new WorkflowGraph(nodes, edges);
  }

  private JsonNode readRoot(String jsonText, List<GraphValidationIssue> issues) {
    if (jsonText == null) {
      issues.add(GraphValidationIssue.wholeGraph("workflow graph body must not be null"));
      return null;
    }
    try {
      return mapper.readTree(jsonText);
    } catch (JacksonException e) {
      issues.add(
          GraphValidationIssue.wholeGraph("workflow graph is not valid JSON: " + e.getMessage()));
      return null;
    }
  }

  private void validateSchemaVersion(JsonNode root, List<GraphValidationIssue> issues) {
    JsonNode versionNode = root.path("schemaVersion");
    if (versionNode.isMissingNode()) {
      issues.add(
          new GraphValidationIssue(
              "schemaVersion", "workflow graph is missing required field 'schemaVersion'"));
    } else if (!versionNode.isString()) {
      issues.add(
          new GraphValidationIssue(
              "schemaVersion", "workflow graph field 'schemaVersion' must be a string"));
    } else {
      String version = versionNode.stringValue();
      if (!SCHEMA_VERSION.equals(version)) {
        issues.add(
            new GraphValidationIssue(
                "schemaVersion",
                "unsupported workflow graph schemaVersion '"
                    + version
                    + "'; expected '"
                    + SCHEMA_VERSION
                    + "'"));
      }
    }
  }

  private List<WorkflowNode> readNodes(JsonNode root, List<GraphValidationIssue> issues) {
    JsonNode nodesNode = root.path("nodes");
    if (nodesNode.isMissingNode()) {
      issues.add(
          new GraphValidationIssue("nodes", "workflow graph is missing required field 'nodes'"));
      return List.of();
    }
    if (!nodesNode.isArray()) {
      issues.add(
          new GraphValidationIssue("nodes", "workflow graph field 'nodes' must be an array"));
      return List.of();
    }
    List<WorkflowNode> nodes = new ArrayList<>(nodesNode.size());
    for (int i = 0; i < nodesNode.size(); i++) {
      nodes.add(readNode(i, nodesNode.get(i), issues));
    }
    return nodes;
  }

  private WorkflowNode readNode(int index, JsonNode node, List<GraphValidationIssue> issues) {
    if (node == null || !node.isObject()) {
      issues.add(
          new GraphValidationIssue(
              "nodes[" + index + "]",
              "workflow graph node at index " + index + " must be a JSON object"));
      return new WorkflowNode(null, null, Map.of());
    }
    String id = readStringField(index, node, "id", "node", issues);
    String type = readStringField(index, node, "type", "node", issues);
    Map<String, Object> config = readConfig(index, node, issues);
    return new WorkflowNode(id, type, config);
  }

  private String readStringField(
      int index, JsonNode node, String field, String entity, List<GraphValidationIssue> issues) {
    JsonNode fieldNode = node.path(field);
    String basePath = entity.equals("node") ? "nodes[" + index + "]" : "edges[" + index + "]";
    if (fieldNode.isMissingNode()) {
      issues.add(
          new GraphValidationIssue(
              basePath + "." + field,
              "workflow graph "
                  + entity
                  + " at index "
                  + index
                  + " is missing required field '"
                  + field
                  + "'"));
      return "";
    }
    if (!fieldNode.isString()) {
      issues.add(
          new GraphValidationIssue(
              basePath + "." + field,
              "workflow graph "
                  + entity
                  + " at index "
                  + index
                  + " field '"
                  + field
                  + "' must be a string"));
      return "";
    }
    return fieldNode.stringValue();
  }

  private List<WorkflowEdge> readEdges(JsonNode root, List<GraphValidationIssue> issues) {
    JsonNode edgesNode = root.path("edges");
    if (edgesNode.isMissingNode() || edgesNode.isNull()) {
      // Absent edges mean a single-node graph with no ordering constraints.
      return List.of();
    }
    if (!edgesNode.isArray()) {
      issues.add(
          new GraphValidationIssue("edges", "workflow graph field 'edges' must be an array"));
      return List.of();
    }
    List<WorkflowEdge> edges = new ArrayList<>(edgesNode.size());
    for (int i = 0; i < edgesNode.size(); i++) {
      edges.add(readEdge(i, edgesNode.get(i), issues));
    }
    return edges;
  }

  private WorkflowEdge readEdge(int index, JsonNode edge, List<GraphValidationIssue> issues) {
    if (edge == null || !edge.isObject()) {
      issues.add(
          new GraphValidationIssue(
              "edges[" + index + "]",
              "workflow graph edge at index " + index + " must be a JSON object"));
      return new WorkflowEdge(null, null);
    }
    String from = readStringField(index, edge, "from", "edge", issues);
    String to = readStringField(index, edge, "to", "edge", issues);
    return new WorkflowEdge(from, to);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readConfig(
      int index, JsonNode node, List<GraphValidationIssue> issues) {
    JsonNode configNode = node.path("config");
    if (configNode.isMissingNode() || configNode.isNull()) {
      return Map.of();
    }
    if (!configNode.isObject()) {
      issues.add(
          new GraphValidationIssue(
              "nodes[" + index + "].config",
              "workflow graph node at index " + index + " field 'config' must be an object"));
      return Map.of();
    }
    return mapper.convertValue(configNode, Map.class);
  }
}
