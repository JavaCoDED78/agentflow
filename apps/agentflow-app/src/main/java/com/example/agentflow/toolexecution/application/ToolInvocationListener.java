package com.example.agentflow.toolexecution.application;

import com.example.agentflow.toolexecution.domain.ToolInvocationRecord;

/**
 * The seam the {@code BoundedToolExecutor} reports every concluded call to (S08-13): one {@link
 * ToolInvocationRecord} per call attempt — success, throttle rejection, ambiguous timeout or tool
 * fault alike — so the invocation history is complete, not a success-only log.
 *
 * <p>The executor invokes the listener <em>after</em> the call's outcome is decided and never lets
 * a listener fault change that outcome: history is observability, not business state, and a tool
 * call whose side effect already completed must not fail because a metadata write did.
 * Implementations must not throw; the executor guards the invocation and logs a warning instead.
 */
@FunctionalInterface
public interface ToolInvocationListener {

  /** The no-op listener the executor defaults to when no history is wired. */
  ToolInvocationListener NONE = record -> {};

  /**
   * Report one concluded call attempt.
   *
   * @param record the immutable invocation metadata row; never {@code null}
   */
  void record(ToolInvocationRecord record);
}
