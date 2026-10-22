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
 */
public record WorkflowNode(String id, String type, Map<String, Object> config) {}
