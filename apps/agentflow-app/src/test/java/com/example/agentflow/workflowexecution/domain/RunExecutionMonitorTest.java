package com.example.agentflow.workflowexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the per-run, thread-safe {@link RunExecutionMonitor} accounting (S11-13).
 *
 * <p>They verify each reported counter against its bound directly against the monitor, so the
 * worker's per-step gates are exercised in isolation: the step budget (a run already at {@code
 * maxSteps} reports the over-budget attempt), the tool-call counter, the retry budget (a run
 * already at {@code maxRetries} reports the over-budget retry), the parallel-slot cap (the flag the
 * worker reads after the handler), the zero-bound-permits-nothing bounds, and the deterministic
 * wall-clock bound and elapsed seconds with an injected start instant. DB-free (no database, Kafka,
 * model); injected (invariant 10).
 */
class RunExecutionMonitorTest {

  private static final Instant STARTED_AT = Instant.parse("2026-06-01T12:00:00Z");

  private static RunExecutionMonitor monitor(
      long maxSteps,
      long maxToolCalls,
      long maxRetries,
      long maxWallClockSeconds,
      long maxParallelCalls) {
    RunExecutionLimits limits =
        new RunExecutionLimits(
            maxSteps, maxToolCalls, maxRetries, maxWallClockSeconds, 0L, maxParallelCalls);
    return new RunExecutionMonitor(limits, STARTED_AT);
  }

  // ---------------------------------------------------------------------------
  // Steps
  // ---------------------------------------------------------------------------

  @Test
  void begin_step_increments_the_step_counter() {
    RunExecutionMonitor monitor = monitor(100L, 0L, 0L, 0L, 0L);
    assertThat(monitor.stepsUsed()).isZero();
    assertThat(monitor.beginStep()).isTrue();
    assertThat(monitor.beginStep()).isTrue();
    assertThat(monitor.stepsUsed()).isEqualTo(2L);
  }

  @Test
  void begin_step_is_allowed_up_to_max_steps_then_reports_the_over_budget_attempt() {
    RunExecutionMonitor monitor = monitor(3L, 0L, 0L, 0L, 0L);
    assertThat(monitor.beginStep()).isTrue();
    assertThat(monitor.beginStep()).isTrue();
    assertThat(monitor.beginStep()).isTrue(); // the 3rd: within the budget
    assertThat(monitor.beginStep()).isFalse(); // the 4th: over the budget, still counted
    assertThat(monitor.beginStep()).isFalse(); // still counted after the breach
    assertThat(monitor.stepsUsed()).isEqualTo(5L);
  }

  // ---------------------------------------------------------------------------
  // Tool calls
  // ---------------------------------------------------------------------------

  @Test
  void record_tool_call_counts_every_reported_call() {
    RunExecutionMonitor monitor = monitor(0L, 0L, 0L, 0L, 0L);
    assertThat(monitor.toolCallsUsed()).isZero();
    monitor.recordToolCall();
    monitor.recordToolCall();
    monitor.recordToolCall();
    assertThat(monitor.toolCallsUsed()).isEqualTo(3L);
  }

  // ---------------------------------------------------------------------------
  // Retries
  // ---------------------------------------------------------------------------

  @Test
  void record_retry_is_allowed_up_to_max_retries_then_reports_the_over_budget_retry() {
    RunExecutionMonitor monitor = monitor(0L, 0L, 2L, 0L, 0L);
    assertThat(monitor.recordRetry()).isTrue();
    assertThat(monitor.recordRetry()).isTrue(); // the 2nd retry: within the budget
    assertThat(monitor.recordRetry()).isFalse(); // the 3rd retry: over the budget, still counted
    assertThat(monitor.retriesUsed()).isEqualTo(3L);
  }

  // ---------------------------------------------------------------------------
  // Parallel slots
  // ---------------------------------------------------------------------------

  @Test
  void begin_and_end_parallel_call_track_the_active_slot_count() {
    RunExecutionMonitor monitor = monitor(0L, 0L, 0L, 0L, 4L); // cap = 4 concurrent calls
    assertThat(monitor.beginParallelCall()).isTrue();
    assertThat(monitor.beginParallelCall()).isTrue();
    assertThat(monitor.beginParallelCall()).isTrue();
    monitor.endParallelCall();
    // Two slots still open; within the cap — no breach.
    assertThat(monitor.beginParallelCall()).isTrue();
    assertThat(monitor.isParallelBudgetExceeded()).isFalse();
  }

  @Test
  void begin_parallel_call_flags_the_breach_when_the_cap_is_exceeded() {
    RunExecutionMonitor monitor = monitor(0L, 0L, 0L, 0L, 2L); // cap = 2 concurrent calls
    assertThat(monitor.beginParallelCall()).isTrue();
    assertThat(monitor.beginParallelCall()).isTrue(); // fills the cap
    // The third concurrent call is over the cap: the slot is reopened and the flag is set.
    assertThat(monitor.beginParallelCall()).isFalse();
    assertThat(monitor.isParallelBudgetExceeded()).isTrue();
    // The active count is left at the cap: the over-cap slot was not held open.
    assertThat(monitor.beginParallelCall()).isFalse(); // still over the cap
  }

  @Test
  void a_zero_parallel_bound_allows_no_concurrent_call() {
    RunExecutionMonitor monitor = monitor(0L, 0L, 0L, 0L, 0L); // 0 concurrent calls allowed
    // The first concurrent call is already over the cap: the slot is reopened and the breach flag
    // is
    // set, so the worker closes the run.
    assertThat(monitor.beginParallelCall()).isFalse();
    assertThat(monitor.isParallelBudgetExceeded()).isTrue();
    assertThat(monitor.parallelCallsAttempted()).isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // Wall clock (deterministic, under an injected start instant)
  // ---------------------------------------------------------------------------

  @Test
  void within_wall_clock_is_true_up_to_the_bound_then_false() {
    RunExecutionMonitor monitor = monitor(0L, 0L, 0L, 10L, 0L);
    // Within the bound: 5 s elapsed <= 10 s.
    assertThat(monitor.withinWallClock(STARTED_AT.plusSeconds(5))).isTrue();
    // Exactly at the bound: the 10th second is still within.
    assertThat(monitor.withinWallClock(STARTED_AT.plusSeconds(10))).isTrue();
    // Over the bound: the 11th second is not within.
    assertThat(monitor.withinWallClock(STARTED_AT.plusSeconds(11))).isFalse();
  }

  @Test
  void elapsed_seconds_reports_the_whole_seconds_since_the_start_instant() {
    RunExecutionMonitor monitor = monitor(0L, 0L, 0L, 0L, 0L);
    assertThat(monitor.elapsedSeconds(STARTED_AT)).isZero();
    assertThat(monitor.elapsedSeconds(STARTED_AT.plusSeconds(30))).isEqualTo(30L);
    assertThat(monitor.elapsedSeconds(STARTED_AT.plusSeconds(3661))).isEqualTo(3661L);
  }

  @Test
  void a_negative_elapsed_duration_reports_zero_not_a_negative_value() {
    RunExecutionMonitor monitor = monitor(0L, 0L, 0L, 0L, 0L);
    assertThat(monitor.elapsedSeconds(STARTED_AT.minusSeconds(10))).isZero();
  }

  @Test
  void the_constructor_captures_the_clock_instant_as_the_start_instant() {
    Clock fixed = Clock.fixed(STARTED_AT, ZoneOffset.UTC);
    RunExecutionMonitor monitor = new RunExecutionMonitor(RunExecutionLimits.defaults(), fixed);
    assertThat(monitor.startedAt()).isEqualTo(STARTED_AT);
  }
}
