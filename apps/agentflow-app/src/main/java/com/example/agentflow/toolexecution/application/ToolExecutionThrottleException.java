package com.example.agentflow.toolexecution.application;

/**
 * A bounded-executor fault: a tool call was <em>not run to completion as the caller asked</em>,
 * either because the global / per-tenant concurrency cap was reached before the call could start
 * ({@link #reason()} {@code concurrency_limited}) or because it exceeded its per-tool {@link
 * java.time.Duration} timeout ({@link #reason()} {@code tool_timeout}).
 *
 * <p>This is the {@code toolexecution} counterpart of the workflow's {@code
 * workflowexecution.domain} failure vocabulary. It carries a stable, machine-readable {@link
 * #reason()} (so the {@link BoundedToolExecutor}'s caller — the {@code TOOL_CALL} {@code
 * StepHandler} that maps it to a step result — classifies without parsing a message) and a {@link
 * #retryable()} flag. Both bounded outcomes are retryable: an overloaded call never started, so a
 * later attempt with spare capacity is safe to make; a timed-out call was not completed within the
 * bound, so it is retried with bounded backoff rather than declared terminal.
 *
 * <p><b>Interrupt does not cancel a remote side effect.</b> A {@link #reason()} of {@code
 * tool_timeout} is deliberately <em>not</em> a clean "nothing happened" failure: the underlying
 * call may still be running when the bound is hit (a remote call is not undone by interrupting the
 * executor's pool thread, and the bounded executor frees the thread rather than waiting for it).
 * The outcome is therefore <b>ambiguous</b> — the side effect <em>may</em> have completed — which
 * is exactly the invariant 6 / {@code ADR-003} case (at-least-once, never exactly-once). The {@code
 * TOOL_CALL} {@code StepHandler} maps {@code tool_timeout} to a retryable {@code provider_timeout}
 * fault recorded as ambiguous in the attempt history, not to a silent success or a decided failure.
 */
public class ToolExecutionThrottleException extends RuntimeException {

  /** The call exceeded its per-tool timeout; the outcome is ambiguous (invariant 6). */
  public static final String REASON_TOOL_TIMEOUT = "tool_timeout";

  /**
   * The global / per-tenant concurrency cap was reached before the call could start; the call was
   * rejected, not started.
   */
  public static final String REASON_CONCURRENCY_LIMITED = "concurrency_limited";

  private final String reason;

  private final boolean retryable;

  /**
   * @param reason the bounded-outcome reason: {@link #REASON_TOOL_TIMEOUT} or {@link
   *     #REASON_CONCURRENCY_LIMITED}
   * @param retryable {@code true} for both bounded outcomes — a later attempt is safe to make
   */
  public ToolExecutionThrottleException(String reason, boolean retryable) {
    super("tool call not executed: " + reason);
    this.reason = reason;
    this.retryable = retryable;
  }

  /**
   * @return the stable reason: {@link #REASON_TOOL_TIMEOUT} or {@link #REASON_CONCURRENCY_LIMITED}
   */
  public String reason() {
    return reason;
  }

  /**
   * @return {@code true} — a bounded-outcome fault is always safe to retry (a later attempt with
   *     spare capacity, or after the bound relaxes)
   */
  public boolean retryable() {
    return retryable;
  }
}
