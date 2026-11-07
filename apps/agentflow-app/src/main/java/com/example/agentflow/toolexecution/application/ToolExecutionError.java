package com.example.agentflow.toolexecution.application;

/**
 * Wraps a tool's own fault raised while the {@link BoundedToolExecutor} ran the call on the
 * executor's pool thread (S08-10).
 *
 * <p>A call submitted to an {@link java.util.concurrent.ExecutorService} can only surface its fault
 * wrapped in a {@link java.util.concurrent.ExecutionException}; the executor unwraps it and, for a
 * {@link java.lang.RuntimeException}, rethrows it unchanged (preserving the tool's own type — a
 * {@link com.example.agentflow.toolexecution.domain.CalculationException} / {@link
 * com.example.agentflow.toolexecution.domain.SchemaValidationException} / any adapter error), so
 * the {@code TOOL_CALL} {@code StepHandler} classifies the tool's genuine failure with the tool's
 * own type rather than a generic wrapper. A <em>checked</em> fault the adapter throws cannot be
 * rethrown through the unchecked {@link #invoke()} port, so it is rewrapped here — the {@link
 * #cause()} still carries the original, so classification by cause type is unchanged.
 *
 * <p>This is <b>not</b> one of the executor's own bounded outcomes ({@link
 * ToolExecutionThrottleException} / {@link java.util.concurrent.TimeoutException}); it is the
 * underlying tool call failing, and it is intentionally permanent-or-tool-defined rather than a
 * "the bound was exceeded" signal.
 */
public class ToolExecutionError extends RuntimeException {

  /**
   * @param cause the tool's own fault (unchecked faults are rewrapped around themselves by the
   *     executor; a checked fault is rewrapped here so it can cross the pool boundary)
   */
  public ToolExecutionError(Throwable cause) {
    super("tool call failed: " + cause, cause);
  }
}
