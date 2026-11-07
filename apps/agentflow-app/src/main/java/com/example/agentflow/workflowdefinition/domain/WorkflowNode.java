package com.example.agentflow.workflowdefinition.domain;

import java.util.Map;

/**
 * A single step in a {@link WorkflowGraph}.
 *
 * <p>Mirrors one node of the {@code graph_json} graph: a stable {@code id}, a {@code type} that
 * must be one of the glossary's allowed node types (validated by {@link WorkflowGraphValidator})
 * and a free-form {@code config} map with the step's parameters. The {@code config} values are
 * untrusted content, not instructions: they are data passed to the step, never executed (invariant:
 * the workflow DSL is a validated typed graph, arbitrary user code is forbidden).
 *
 * <p>The node may also carry the DSL's data wiring, anchored verbatim to {@code
 * contracts/workflow-graph/workflow-graph.json} (S08-12):
 *
 * <ul>
 *   <li>{@code input} — a map of input name to either a literal string or a {@code {node, output}}
 *       reference to a declared output of another node (the {@code reference} $def of the
 *       contract). References are resolved at step execution time against the completed
 *       predecessors' outputs; only a <em>declared</em> output name of the referenced node is
 *       reachable — there is deliberately no free path navigation into arbitrary fields of the
 *       upstream result (the engine of {@code workflowexecution} resolves the wiring, S08-12).
 *   <li>{@code output} — the declaration of this node's named outputs (a map of output name to a
 *       non-blank description); downstream {@code input}s may reference exactly these names, and
 *       the {@link WorkflowGraphValidator} linkage gate (S08-12) rejects a reference to a name a
 *       node does not declare, before the version is persisted.
 * </ul>
 *
 * <p>Both maps are immutable and empty when the node declares no wiring. The three-argument
 * constructor is the convenience form for nodes without wiring; the canonical constructor validates
 * the maps are not {@code null}.
 */
public record WorkflowNode(
    String id,
    String type,
    Map<String, Object> config,
    Map<String, Object> input,
    Map<String, String> output) {

  /**
   * Convenience constructor for a node without {@code input}/{@code output} wiring.
   *
   * @param id the node id (uniqueness is enforced by the {@link WorkflowGraphValidator})
   * @param type the node type (one of the glossary's allowed node types)
   * @param config the node's free-form parameters (untrusted data, never executed)
   */
  public WorkflowNode(String id, String type, Map<String, Object> config) {
    this(id, type, config, Map.of(), Map.of());
  }

  /**
   * @param id the node id; must be non-blank and unique across the graph (validator-enforced)
   * @param type the node type; one of the glossary's allowed node types (validator-enforced)
   * @param config the node's free-form parameters (untrusted data, never executed); a {@code null}
   *     (a graph persisted before this validation existed) defaults to empty
   * @param input the DSL input wiring (input name &rarr; literal or {@code {node, output}}
   *     reference); a {@code null} (a graph persisted before S08-12) defaults to empty
   * @param output the declared named outputs (output name &rarr; non-blank description); a {@code
   *     null} (a graph persisted before S08-12) defaults to empty
   */
  public WorkflowNode {
    // Graphs persisted before S08-12 have no input/output fields, and graphs written directly as
    // JSON (test fixtures, legacy rows) may omit config: Jackson hands nulls to the canonical
    // constructor on deserialize, and an activated version must stay readable (invariant 13) —
    // so absent values default to empty rather than failing the read. The parser's input gate
    // (S05-04) still guarantees every parsed graph declares config as an object.
    if (config == null) {
      config = Map.of();
    }
    if (input == null) {
      input = Map.of();
    }
    if (output == null) {
      output = Map.of();
    }
  }
}
