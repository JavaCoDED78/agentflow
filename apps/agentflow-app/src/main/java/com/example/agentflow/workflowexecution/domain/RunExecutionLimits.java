package com.example.agentflow.workflowexecution.domain;

import org.springframework.util.Assert;

/**
 * Immutable, validated set of per-run hard execution limits (S11-13). The worker reads these once
 * per run and stops the run in a documented, controlled state the moment a limit is reached. A
 * limit of {@code 0} is a literal maximum of zero — the worker stops the run the instant a step, a
 * tool call, a retry, a wall-clock second, an output byte or a concurrent call of that kind is
 * attempted (the zero-sentinel convention: {@code 0} permits nothing of that kind).
 *
 * <p>This is the pure value object the {@link RunExecutionMonitor} checks against. The worker
 * builds one from {@code AgentFlowProperties.Execution}; the constructors take plain primitives so
 * the domain layer never depends on the configuration layer.
 *
 * @see RunExecutionMonitor the per-run, mutable accounting that reports against these bounds
 * @see RunExecutionLimitExceeded the breach the worker turns into a documented {@code FAILED} run
 */
public final class RunExecutionLimits {

  /** The six bounds the engine enforces per run (S11-13). */
  public enum Limit {
    /** The number of steps executed on the run exceeded the step budget. */
    STEPS,
    /** The number of tool calls made on the run exceeded the tool-call budget. */
    TOOL_CALLS,
    /** The number of retries spent on the run exceeded the retry budget. */
    RETRIES,
    /** The run exceeded its wall-clock duration. */
    WALL_CLOCK,
    /** A single step produced more output than the step-output size bound. */
    OUTPUT_SIZE,
    /** A step's handler tried to run more concurrent calls than the parallel bound. */
    PARALLEL_CALLS
  }

  /**
   * The default bounds, mirroring {@code AgentFlowProperties.Execution}: steps unlimited-ish,
   * tool-calls 100, retries 10, wall-clock one hour, output 10 MiB, parallel 4.
   */
  public static final RunExecutionLimits DEFAULTS =
      new RunExecutionLimits(1000L, 100L, 10L, 3600L, 10_000_000L, 4L);

  private final long maxSteps;
  private final long maxToolCalls;
  private final long maxRetries;
  private final long maxWallClockSeconds;
  private final long maxOutputBytes;
  private final long maxParallelCalls;

  /**
   * @param maxSteps maximum steps executed per run ({@code 0} permits nothing of that kind)
   * @param maxToolCalls maximum tool calls per run ({@code 0} permits nothing)
   * @param maxRetries maximum retries per run ({@code 0} permits no retry — the first transient
   *     fault is terminal)
   * @param maxWallClockSeconds maximum wall-clock duration of a run in seconds ({@code 0} permits
   *     no elapsed time)
   * @param maxOutputBytes maximum step-output size in bytes ({@code 0} permits no output)
   * @param maxParallelCalls maximum concurrent calls per step's handler ({@code 0} permits no
   *     concurrent call)
   */
  public RunExecutionLimits(
      long maxSteps,
      long maxToolCalls,
      long maxRetries,
      long maxWallClockSeconds,
      long maxOutputBytes,
      long maxParallelCalls) {
    Assert.isTrue(maxSteps >= 0, "maxSteps must not be negative");
    Assert.isTrue(maxToolCalls >= 0, "maxToolCalls must not be negative");
    Assert.isTrue(maxRetries >= 0, "maxRetries must not be negative");
    Assert.isTrue(maxWallClockSeconds >= 0, "maxWallClockSeconds must not be negative");
    Assert.isTrue(maxOutputBytes >= 0, "maxOutputBytes must not be negative");
    Assert.isTrue(maxParallelCalls >= 0, "maxParallelCalls must not be negative");
    this.maxSteps = maxSteps;
    this.maxToolCalls = maxToolCalls;
    this.maxRetries = maxRetries;
    this.maxWallClockSeconds = maxWallClockSeconds;
    this.maxOutputBytes = maxOutputBytes;
    this.maxParallelCalls = maxParallelCalls;
  }

  /**
   * @return the default bounds (see {@link #DEFAULTS})
   */
  public static RunExecutionLimits defaults() {
    return DEFAULTS;
  }

  /**
   * @return the maximum number of steps executed per run
   */
  public long maxSteps() {
    return maxSteps;
  }

  /**
   * @return the maximum number of tool calls per run
   */
  public long maxToolCalls() {
    return maxToolCalls;
  }

  /**
   * @return the maximum number of retries per run
   */
  public long maxRetries() {
    return maxRetries;
  }

  /**
   * @return the maximum wall-clock duration of a run in seconds
   */
  public long maxWallClockSeconds() {
    return maxWallClockSeconds;
  }

  /**
   * @return the maximum step-output size in bytes
   */
  public long maxOutputBytes() {
    return maxOutputBytes;
  }

  /**
   * @return the maximum number of concurrent calls per step's handler
   */
  public long maxParallelCalls() {
    return maxParallelCalls;
  }

  @Override
  public String toString() {
    return "RunExecutionLimits{"
        + "maxSteps="
        + maxSteps
        + ", maxToolCalls="
        + maxToolCalls
        + ", maxRetries="
        + maxRetries
        + ", maxWallClockSeconds="
        + maxWallClockSeconds
        + ", maxOutputBytes="
        + maxOutputBytes
        + ", maxParallelCalls="
        + maxParallelCalls
        + '}';
  }
}
