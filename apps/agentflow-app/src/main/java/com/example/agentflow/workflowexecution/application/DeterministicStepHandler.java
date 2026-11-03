package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.workflowexecution.domain.StepExecutionContext;
import com.example.agentflow.workflowexecution.domain.StepHandler;
import com.example.agentflow.workflowexecution.domain.StepResult;
import org.springframework.stereotype.Service;

/**
 * The MVP {@link StepHandler} (S07-08) — a deterministic executor that proves the worker event loop
 * end to end without pulling in a real node handler. It is registered as a bean so a running worker
 * can execute {@link #NODE_TYPE} steps; the per-module handlers (tool/ai/knowledgebase) that
 * implement a {@link StepHandler} for their node type are added as beans later and are picked up
 * the same way, so the loop stays decoupled from the node types it can run (the {@code StepHandler}
 * runtime SPI).
 *
 * <p>It is deterministic by design: the output is derived from the node's {@code config} with no
 * external side effect, so a step it executes is repeatable and its result is safe to checkpoint.
 * The {@code config} is untrusted content (invariant 7), never executed — a {@code result} value in
 * it is echoed, and a node without one gets a stable default.
 */
@Service
public class DeterministicStepHandler implements StepHandler {

  /**
   * The node type the MVP handler executes; replaced by per-module handlers for real node types.
   */
  public static final String NODE_TYPE = "NOOP";

  /**
   * The MVP output-schema version carried on the step checkpoint (S07-07 reuses this as the key).
   */
  public static final String OUTPUT_SCHEMA_VERSION = "1";

  @Override
  public boolean supports(String nodeType) {
    return NODE_TYPE.equals(nodeType);
  }

  @Override
  public StepResult handle(StepExecutionContext context) {
    Object configured = context.config() == null ? null : context.config().get("result");
    String output =
        (configured != null && !configured.toString().isEmpty())
            ? configured.toString()
            : "done:" + context.stepKey();
    return StepResult.succeeded(output);
  }
}
