package com.example.agentflow.workflowexecution.domain;

import com.example.agentflow.common.ErrorClass;
import java.util.Objects;

/**
 * A per-run hard execution limit was exceeded (S11-13). The worker throws this from the per-step
 * limit gates; each throw is contained to the run: the worker maps it to a documented, controlled
 * {@code FAILED} transition (the run and the current step) rather than letting it propagate as an
 * unhandled failure, and it stops driving the run so no further step executes.
 *
 * <p>The exception carries the {@link #limit() Limit}, the bound and the observed {@link #used()
 * used} value, from which the {@link #errorClass()} ({@link ErrorClass#LIMIT_EXCEEDED}) and a
 * short, <em>safe</em> reason are derived. The safe reason carries no secret, full prompt, document
 * or high-cardinality id (invariant 8 / rule 9): it is a plain {@code used > limit} comparison.
 *
 * @see RunExecutionLimits the bounds checked against
 * @see RunExecutionMonitor the per-run accounting the worker drives
 * @see com.example.agentflow.workflowexecution.application.WorkflowWorker the worker that turns it
 *     into a controlled {@code FAILED} run
 */
public class RunExecutionLimitExceeded extends RuntimeException {

  private final RunExecutionLimits.Limit limit;
  private final long limitValue;
  private final long used;

  /**
   * @param limit the bound that was exceeded
   * @param limitValue the configured bound
   * @param used the observed value that exceeded the bound
   */
  public RunExecutionLimitExceeded(RunExecutionLimits.Limit limit, long limitValue, long used) {
    super("run exceeded " + limit + " limit: " + used + " > " + limitValue);
    this.limit = Objects.requireNonNull(limit, "limit must not be null");
    this.limitValue = limitValue;
    this.used = used;
  }

  /**
   * @return the bound that was exceeded
   */
  public RunExecutionLimits.Limit limit() {
    return limit;
  }

  /**
   * @return the configured bound
   */
  public long limitValue() {
    return limitValue;
  }

  /**
   * @return the observed value that exceeded the bound
   */
  public long used() {
    return used;
  }

  /**
   * @return the canonical error class of a limit breach, {@link ErrorClass#LIMIT_EXCEEDED}
   */
  public String errorClass() {
    return ErrorClass.LIMIT_EXCEEDED.errorClass();
  }

  /**
   * @return a short, safe, redacted description of the breach — the plain {@code used > limit}
   *     comparison, carrying no secret, prompt, document or high-cardinality id (invariant 8 / rule
   *     9)
   */
  public String safeReason() {
    return limit.name().toLowerCase().replace('_', ' ')
        + " limit exceeded: "
        + used
        + " > "
        + limitValue;
  }
}
