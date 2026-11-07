package com.example.agentflow.workflowexecution.domain;

import java.util.Map;
import java.util.UUID;

/**
 * The execution context of one step (S07-08, the input the {@link StepHandler} SPI receives).
 *
 * <p>It is tenant-scoped (invariant 1): the tenant, run and step key identify the step whose graph
 * node the worker is about to run, and the worker only ever builds this context for a run it has
 * already verified is owned by {@code tenantId} (tenant isolation flows through the parent run).
 * The {@code config} is the node's free-form parameters, the {@code inputs} are the node's {@code
 * input} wiring resolved against the completed predecessors' outputs (S08-12 — literal strings and
 * {@code {node, output}} references to declared outputs only), and the {@code upstreamOutputs} are
 * those predecessors' raw persisted output documents keyed by step key — all three are
 * <em>untrusted content</em>, never executed (invariant 7 / the DSL is data, not code).
 *
 * @param tenantId the owning tenant (from the verified security context)
 * @param runId the run the step belongs to
 * @param stepKey the graph node the step runs (the node {@code id})
 * @param nodeType the graph node type; the {@link StepHandler} picks itself by this value
 * @param config the node's free-form parameters (untrusted data, never executed)
 * @param inputs the node's {@code input} wiring resolved by the {@link StepInputMapper} (S08-12):
 *     literals passed through, references replaced with the declared upstream outputs; empty when
 *     the node declares no wiring; unmodifiable
 * @param upstreamOutputs the completed predecessor steps' raw persisted output documents keyed by
 *     their step key; empty when the node has no completed predecessors
 */
public record StepExecutionContext(
    UUID tenantId,
    UUID runId,
    String stepKey,
    String nodeType,
    Map<String, Object> config,
    Map<String, Object> inputs,
    Map<String, String> upstreamOutputs) {

  /**
   * Convenience constructor for the pre-S08-12 shape: a step with no wiring receives empty resolved
   * inputs.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs (the node {@code id})
   * @param nodeType the graph node type
   * @param config the node's free-form parameters (untrusted data, never executed)
   * @param upstreamOutputs the completed predecessor steps' raw outputs keyed by step key
   */
  public StepExecutionContext(
      UUID tenantId,
      UUID runId,
      String stepKey,
      String nodeType,
      Map<String, Object> config,
      Map<String, String> upstreamOutputs) {
    this(tenantId, runId, stepKey, nodeType, config, Map.of(), upstreamOutputs);
  }
}
