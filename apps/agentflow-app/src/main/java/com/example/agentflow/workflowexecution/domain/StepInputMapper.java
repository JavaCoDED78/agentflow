package com.example.agentflow.workflowexecution.domain;

import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The step input mapping engine (S08-12): resolves a node's {@code input} wiring against the
 * completed predecessor steps' outputs, so a step can consume what its upstream produced — and
 * nothing else.
 *
 * <p>The wiring is the DSL's {@code reference} contract, anchored verbatim to {@code
 * contracts/workflow-graph/workflow-graph.json}: each {@code input} entry is either a literal
 * string, passed through unchanged, or a {@code {node, output}} reference. A reference resolves to
 * the <em>declared</em> output named {@code output} of the completed step {@code node}. The engine
 * looks up exactly that name in the upstream step's persisted output document — there is
 * deliberately <b>no path navigation</b>: {@code output} is a closed name validated against the
 * referenced node's declared outputs, never a free pointer into arbitrary fields of the upstream
 * result (the "no access to arbitrary entity fields" rule). Anything unresolvable is rejected with
 * an {@link InputMappingException} carrying a stable {@code reason()} — before the step's handler
 * and any tool call.
 *
 * <p>Validation happens at two depths (defense in depth):
 *
 * <ul>
 *   <li><b>Activation</b> — the {@code WorkflowGraphValidator} linkage gate rejects a wiring that
 *       references an unknown node or an undeclared output before the version is persisted, so a
 *       broken graph never reaches a run;
 *   <li><b>Execution</b> — this engine re-validates against the <em>runtime</em> facts the
 *       activation gate cannot see: the referenced step's output document must actually exist (the
 *       step completed — the S08-11 readiness gate orders this, the engine does not trust it) and
 *       must carry the declared name with a non-null JSON value.
 * </ul>
 *
 * <p>The engine is pure: it walks already-parsed {@code Map}/{@code List} JSON trees, reads no
 * time, no randomness and no I/O (invariant 10), and depends on no JSON library — the text-to-tree
 * parsing lives at the application edge ({@code StepInputResolver}). The resolved map preserves the
 * wiring's declaration order and is unmodifiable.
 */
public final class StepInputMapper {

  /**
   * Resolves the node's {@code input} wiring.
   *
   * @param node the node whose wiring is resolved; its {@code input()} map holds the wiring
   * @param nodesById the graph's nodes keyed by id (the referenced node must be one of them)
   * @param upstreamOutputs the completed predecessor steps' persisted output documents, keyed by
   *     step key, already parsed into JSON trees; a missing key means the step has no persisted
   *     output
   * @return the resolved inputs, in wiring declaration order; empty when the node declares no
   *     wiring; unmodifiable
   * @throws IllegalArgumentException when {@code node}, {@code nodesById} or {@code
   *     upstreamOutputs} is {@code null}
   * @throws InputMappingException when a wiring value is neither a string literal nor a well-formed
   *     {@code {node, output}} reference to a declared, present, non-null output
   */
  public Map<String, Object> resolve(
      WorkflowNode node,
      Map<String, WorkflowNode> nodesById,
      Map<String, Map<String, Object>> upstreamOutputs) {
    if (node == null) {
      throw new IllegalArgumentException("workflow node must not be null");
    }
    if (nodesById == null) {
      throw new IllegalArgumentException("graph nodes must not be null");
    }
    if (upstreamOutputs == null) {
      throw new IllegalArgumentException("upstream outputs must not be null");
    }
    Map<String, Object> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, Object> wired : node.input().entrySet()) {
      resolved.put(
          wired.getKey(),
          resolveValue(node, wired.getKey(), wired.getValue(), nodesById, upstreamOutputs));
    }
    return Collections.unmodifiableMap(resolved);
  }

  private Object resolveValue(
      WorkflowNode node,
      String name,
      Object value,
      Map<String, WorkflowNode> nodesById,
      Map<String, Map<String, Object>> upstreamOutputs) {
    if (value instanceof String literal) {
      // A literal string passes through unchanged (the contract's oneOf first branch).
      return literal;
    }
    if (!(value instanceof Map<?, ?> reference)) {
      throw new InputMappingException(
          "invalid-literal",
          "step input '"
              + name
              + "' of node '"
              + node.id()
              + "' must be a literal string or a {node, output} reference");
    }
    Object refNode = reference.get("node");
    Object refOutput = reference.get("output");
    if (!(refNode instanceof String refNodeId)
        || refNodeId.isBlank()
        || !(refOutput instanceof String refOutputName)
        || refOutputName.isBlank()) {
      throw new InputMappingException(
          "invalid-reference",
          "step input '"
              + name
              + "' of node '"
              + node.id()
              + "' reference must carry a non-blank string 'node' and 'output'");
    }
    for (Object key : reference.keySet()) {
      if (!"node".equals(key) && !"output".equals(key)) {
        throw new InputMappingException(
            "invalid-reference",
            "step input '"
                + name
                + "' of node '"
                + node.id()
                + "' reference has unknown field '"
                + key
                + "' (only 'node' and 'output' are allowed)");
      }
    }
    WorkflowNode upstream = nodesById.get(refNodeId);
    if (upstream == null) {
      throw new InputMappingException(
          "unknown-node",
          "step input '"
              + name
              + "' of node '"
              + node.id()
              + "' references unknown node '"
              + refNodeId
              + "'");
    }
    if (!upstream.output().containsKey(refOutputName)) {
      throw new InputMappingException(
          "unknown-output",
          "step input '"
              + name
              + "' of node '"
              + node.id()
              + "' references output '"
              + refOutputName
              + "' which node '"
              + refNodeId
              + "' does not declare");
    }
    Map<String, Object> upstreamDocument = upstreamOutputs.get(refNodeId);
    if (upstreamDocument == null) {
      throw new InputMappingException(
          "missing-output",
          "step input '"
              + name
              + "' of node '"
              + node.id()
              + "' references node '"
              + refNodeId
              + "' which has no persisted output");
    }
    if (!upstreamDocument.containsKey(refOutputName)) {
      throw new InputMappingException(
          "missing-output",
          "step input '"
              + name
              + "' of node '"
              + node.id()
              + "' references output '"
              + refOutputName
              + "' which is absent from node '"
              + refNodeId
              + "' output document");
    }
    Object resolved = upstreamDocument.get(refOutputName);
    if (resolved == null) {
      throw new InputMappingException(
          "null-output",
          "step input '"
              + name
              + "' of node '"
              + node.id()
              + "' references output '"
              + refOutputName
              + "' which is null in node '"
              + refNodeId
              + "' output document");
    }
    return resolved;
  }
}
