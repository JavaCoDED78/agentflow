package com.example.agentflow.workflowdefinition.domain;

/**
 * A single execution-order {@link WorkflowGraph edge} of a graph, linking one node id to another.
 *
 * <p>Mirrors one {@code {from, to}} entry of the {@code graph_json} {@code edges} array. The
 * endpoints are node <em>ids</em>, not nodes: {@link WorkflowGraphValidator} resolves them against
 * the graph's nodes before the graph is persisted, so a dangling, duplicate or self-referencing
 * edge is rejected before it reaches storage (S05-05). This is a value object with no behaviour, so
 * it can safely cross the owning module's public API boundary.
 */
public record WorkflowEdge(String from, String to) {}
