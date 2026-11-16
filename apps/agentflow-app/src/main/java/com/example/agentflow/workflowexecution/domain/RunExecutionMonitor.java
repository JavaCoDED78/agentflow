package com.example.agentflow.workflowexecution.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable, thread-safe per-run accounting against {@link RunExecutionLimits} (S11-13). The worker
 * creates one per run and owns it; step handlers report into it through {@link
 * StepExecutionContext#budget()} (tool calls and concurrent-call slots). Because a step's handler
 * may report concurrent parallel slots from more than one thread, all fields are atomic.
 *
 * <p>The counters are <em>reported</em> (the handlers and the worker tell it what happened), never
 * guessed: {@link #recordToolCall()} is called by a handler before each tool call, {@link
 * #beginParallelCall()}/{@link #endParallelCall()} wrap each concurrent call, and {@link
 * #recordRetry()} is called by the worker when a fault is retried. The worker then reads the
 * counters to enforce the per-run budgets at the documented check points (see {@link
 * com.example.agentflow.workflowexecution.application.WorkflowWorker}).
 *
 * @see RunExecutionLimits the bounds checked against
 * @see RunExecutionLimitExceeded the breach reported to the worker
 */
public final class RunExecutionMonitor {

  private final RunExecutionLimits limits;
  private final Instant startedAt;

  private final AtomicLong steps = new AtomicLong();
  private final AtomicLong toolCalls = new AtomicLong();
  private final AtomicLong retries = new AtomicLong();
  private final AtomicInteger activeParallel = new AtomicInteger();
  private final AtomicLong parallelCallsAttempted = new AtomicLong();
  private volatile boolean parallelBudgetExceeded;

  /**
   * @param limits the per-run hard bounds to check against (never {@code null})
   * @param clock the injectable clock (invariant 10) the wall-clock bound is measured against
   */
  public RunExecutionMonitor(RunExecutionLimits limits, Clock clock) {
    this.limits = Objects.requireNonNull(limits, "limits must not be null");
    this.startedAt = Objects.requireNonNull(clock, "clock must not be null").instant();
  }

  /**
   * Package-private constructor with an explicit start instant, so the wall-clock bound can be
   * tested deterministically without advancing a clock.
   */
  RunExecutionMonitor(RunExecutionLimits limits, Instant startedAt) {
    this.limits = Objects.requireNonNull(limits, "limits must not be null");
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
  }

  /**
   * @return the bounds this monitor checks against
   */
  public RunExecutionLimits limits() {
    return limits;
  }

  /**
   * @return the instant the run was started
   */
  public Instant startedAt() {
    return startedAt;
  }

  // -- steps -------------------------------------------------------------

  /**
   * @return the number of steps started on the run so far
   */
  public long stepsUsed() {
    return steps.get();
  }

  /**
   * Records the start of a step and reports whether it stays within the step budget. Increments the
   * step counter first, so a run already at {@code maxSteps} reports the over-budget attempt.
   *
   * @return {@code true} while the run has step budget left, {@code false} once {@code maxSteps} is
   *     reached (the worker turns the {@code false} into a documented {@code FAILED} run)
   */
  public boolean beginStep() {
    long started = steps.incrementAndGet();
    return started <= limits.maxSteps();
  }

  // -- tool calls (reported by the step handler) -------------------------

  /**
   * @return the number of tool calls reported on the run so far
   */
  public long toolCallsUsed() {
    return toolCalls.get();
  }

  /**
   * Records one tool call. Called by a step handler before each tool call, so the run-level
   * tool-call budget is measured on what actually ran.
   */
  public void recordToolCall() {
    toolCalls.incrementAndGet();
  }

  // -- retries (reported by the worker, in the fault path) ---------------

  /**
   * @return the number of retries recorded on the run so far
   */
  public long retriesUsed() {
    return retries.get();
  }

  /**
   * Records a retry and reports whether the run still has retry budget. Increments first, so a run
   * already at {@code maxRetries} reports the over-budget retry.
   *
   * @return {@code true} while the run has retry budget left, {@code false} once {@code maxRetries}
   *     is reached (the worker turns the {@code false} into a documented {@code FAILED} run)
   */
  public boolean recordRetry() {
    long attempted = retries.incrementAndGet();
    return attempted <= limits.maxRetries();
  }

  // -- parallel calls (reported by the step handler) ---------------------

  /**
   * @return {@code true} if a handler ever tried to open more concurrent slots than the bound
   */
  public boolean isParallelBudgetExceeded() {
    return parallelBudgetExceeded;
  }

  /**
   * @return the number of concurrent-call slots the handler attempted to open on the run
   */
  public long parallelCallsAttempted() {
    return parallelCallsAttempted.get();
  }

  /**
   * Opens one concurrent-call slot and reports whether it is allowed under the parallel bound.
   * Increments the attempt counter first, so a run already at {@code maxParallelCalls} reports the
   * over-budget attempt.
   *
   * @return {@code true} if the slot was granted, {@code false} if granting it would exceed {@code
   *     maxParallelCalls} (the handler reopens the slot and reports a {@link
   *     RunExecutionLimitExceeded}; the worker also observes {@link #isParallelBudgetExceeded()})
   */
  public boolean beginParallelCall() {
    long cap = limits.maxParallelCalls();
    int current = activeParallel.incrementAndGet();
    parallelCallsAttempted.incrementAndGet();
    if (current <= cap) {
      return true;
    }
    // Over budget: release the slot and flag the breach for the worker.
    activeParallel.decrementAndGet();
    parallelBudgetExceeded = true;
    return false;
  }

  /**
   * Closes one concurrent-call slot opened by {@link #beginParallelCall()}. Called when the
   * concurrent call completes, so the active count returns toward zero. A {@code null} handler (the
   * deterministic {@code NOOP} / AI steps that make no parallel calls) never calls this.
   */
  public void endParallelCall() {
    activeParallel.decrementAndGet();
  }

  // -- wall clock --------------------------------------------------------

  /**
   * @param now the current instant
   * @return {@code true} while the run's elapsed wall-clock time is within {@code
   *     maxWallClockSeconds}
   */
  public boolean withinWallClock(Instant now) {
    Duration elapsed = Duration.between(startedAt, now);
    return !elapsed.isNegative() && elapsed.getSeconds() <= limits.maxWallClockSeconds();
  }

  /**
   * @param now the current instant
   * @return the run's elapsed wall-clock duration in whole seconds since it started (zero or
   *     positive)
   */
  public long elapsedSeconds(Instant now) {
    Duration elapsed = Duration.between(startedAt, now);
    return elapsed.isNegative() ? 0L : elapsed.getSeconds();
  }
}
