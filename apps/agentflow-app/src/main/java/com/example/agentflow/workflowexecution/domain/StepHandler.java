package com.example.agentflow.workflowexecution.domain;

/**
 * The per-step execution SPI (S07-08, the {@code StepHandler} port owned by {@code
 * workflowexecution} and named in {@code docs/architecture/module-api.md}).
 *
 * <p>It keeps the worker event loop decoupled from the different node types the graph can contain.
 * A {@link StepHandler} lives in the module that owns a node type — {@code toolexecution} for a
 * {@code TOOL_CALL}, {@code ai} for a {@code LLM_*} node, {@code knowledgebase} for a {@code
 * RETRIVE_CONTEXT}, and so on — and each module registers its handler so the worker can resolve one
 * by the node type at runtime. The worker holds the handlers as a list and picks the first one
 * whose {@link #supports(String)} matches; a node type with no handler is a permanent {@code
 * FAILED} (an unknown node can never become executable code, invariant 7). This runtime wiring is
 * exactly the execution SPI the module-map edge for {@code StepHandler} (arrows 14-17) records: it
 * is a runtime edge, not a compile edge, so it keeps the module graph acyclic.
 *
 * <p>Every implementation is tenant-scoped: the {@link StepExecutionContext} it receives carries
 * the owning tenant, and a handler must never use it to reach another tenant's data.
 */
public interface StepHandler {

  /**
   * @param nodeType the graph node type the step runs
   * @return {@code true} when this handler executes {@code nodeType}
   */
  boolean supports(String nodeType);

  /**
   * Execute one step against its pinned graph node.
   *
   * @param context the step execution context (tenant-scoped; the node's {@code config} and the
   *     upstream outputs are untrusted content, never executed)
   * @return the step outcome and, on {@link WorkflowStepState#SUCCEEDED}, its output
   */
  StepResult handle(StepExecutionContext context);
}
