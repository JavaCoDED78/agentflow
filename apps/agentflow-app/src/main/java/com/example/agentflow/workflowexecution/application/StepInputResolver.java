package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import com.example.agentflow.workflowexecution.domain.InputMappingException;
import com.example.agentflow.workflowexecution.domain.StepInputMapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The application-edge seam of the step input mapping engine (S08-12): owns the text-to-tree JSON
 * parsing the pure {@link StepInputMapper} deliberately does not do, and resolves a node's {@code
 * input} wiring against the completed predecessor steps' persisted output documents ({@code
 * workflow_step_runs.output_inline} strings, loaded tenant-scoped by the worker).
 *
 * <p>Only the documents the wiring actually references are parsed — a node without wiring, or a
 * wiring that touches one upstream output, never pays to parse the whole run's outputs. A document
 * that is not well-formed JSON surfaces as an {@link InputMappingException} with reason {@code
 * invalid-upstream-output}: an upstream step checkpointed output its own contract did not hold, a
 * permanent fault a retry cannot overcome (the classifier maps it to {@code validation}, S07-11).
 *
 * <p>The resolver is stateless; the {@link ObjectMapper} is Jackson 3.x ({@code tools.jackson}),
 * the Boot 4.1.1 graph, constructed locally the same way {@code WorkflowRunController} does — this
 * class is not a Spring bean and holds no tenant or run state.
 */
public class StepInputResolver {

  private static final TypeReference<Map<String, Object>> OUTPUT_TREE = new TypeReference<>() {};

  private final StepInputMapper mapper = new StepInputMapper();
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Resolves the node's {@code input} wiring against the persisted upstream outputs.
   *
   * @param node the node about to execute
   * @param nodesById the graph's nodes keyed by id
   * @param upstreamOutputsJson the completed predecessor steps' persisted output documents, keyed
   *     by step key, as raw JSON text
   * @return the resolved inputs (empty when the node declares no wiring); unmodifiable
   * @throws InputMappingException when the wiring is unresolvable, or a referenced upstream
   *     document is not well-formed JSON
   */
  public Map<String, Object> resolve(
      WorkflowNode node,
      Map<String, WorkflowNode> nodesById,
      Map<String, String> upstreamOutputsJson) {
    if (node == null || nodesById == null || upstreamOutputsJson == null) {
      throw new IllegalArgumentException(
          "step input resolution requires the node, the graph and the upstream outputs");
    }
    if (node.input().isEmpty()) {
      return Map.of();
    }
    Set<String> referencedSteps = referencedSteps(node);
    Map<String, Map<String, Object>> parsed = new HashMap<>();
    for (String stepKey : referencedSteps) {
      String document = upstreamOutputsJson.get(stepKey);
      if (document == null) {
        // The pure mapper reports this as missing-output; no parse needed.
        continue;
      }
      try {
        parsed.put(stepKey, objectMapper.readValue(document, OUTPUT_TREE));
      } catch (JacksonException e) {
        throw new InputMappingException(
            "invalid-upstream-output",
            "step '" + stepKey + "' output document is not well-formed JSON: " + e.getMessage());
      }
    }
    return mapper.resolve(node, nodesById, parsed);
  }

  /**
   * @return the distinct node ids the wiring references (only those documents are parsed)
   */
  private static Set<String> referencedSteps(WorkflowNode node) {
    Set<String> referenced = new HashSet<>();
    for (Object value : node.input().values()) {
      if (value instanceof Map<?, ?> reference) {
        Object refNode = reference.get("node");
        if (refNode instanceof String refNodeId && !refNodeId.isBlank()) {
          referenced.add(refNodeId);
        }
      }
    }
    return referenced;
  }
}
