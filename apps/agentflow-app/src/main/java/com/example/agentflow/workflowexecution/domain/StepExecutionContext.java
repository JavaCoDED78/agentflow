package com.example.agentflow.workflowexecution.domain;

import java.util.Map;
import java.util.UUID;

/**
 * The execution context of one step (S07-08, the input the {@link StepHandler} SPI receives).
 *
 * <p>It is tenant-scoped (invariant 1): the tenant, run and step key identify the step whose graph
 * node the worker is about to run, and the worker only ever builds this context for a run it has
 * already verified is owned by {@code tenantId} (tenant isolation flows through the parent run).
 * The {@code config} is the node's free-form parameters and the {@code upstreamOutputs} are the
 * completed predecessor steps' outputs (the DSL {@code input} wiring) — both are <em>untrusted
 * content</em>, never executed (invariant 7 / the DSL is data, not code).
 *
 * @param tenantId the owning tenant (from the verified security context)
 * @param runId the run the step belongs to
 * @param stepKey the graph node the step runs (the node {@code id})
 * @param nodeType the graph node type; the {@link StepHandler} picks itself by this value
 * @param config the node's free-form parameters (untrusted data, never executed)
 * @param upstreamOutputs the completed predecessor steps' outputs keyed by their step key, so a
 *     downstream node can read what its predecessors produced (the DSL {@code input} wiring); empty
 *     when the node has no completed predecessors
 */
public record StepExecutionContext(
    UUID tenantId,
    UUID runId,
    String stepKey,
    String nodeType,
    Map<String, Object> config,
    Map<String, String> upstreamOutputs) {}
