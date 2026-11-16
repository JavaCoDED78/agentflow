package com.example.agentflow.workflowexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure, validated {@link RunExecutionLimits} value object (S11-13).
 *
 * <p>They verify the acceptance criterion — a per-run set of hard bounds that the worker reads once
 * and stops the run the moment a limit is reached — directly against the value object: the six
 * getters returning the configured bounds, the documented defaults, the constructor validation, and
 * that a bound of {@code 0} is a legitimate (disabling) value rather than an invalid one. DB-free
 * (no database, Kafka, model or clock).
 */
class RunExecutionLimitsTest {

  @Test
  void the_defaults_are_the_documented_production_bounds() {
    RunExecutionLimits defaults = RunExecutionLimits.defaults();
    assertThat(defaults.maxSteps()).isEqualTo(1000L);
    assertThat(defaults.maxToolCalls()).isEqualTo(100L);
    assertThat(defaults.maxRetries()).isEqualTo(10L);
    assertThat(defaults.maxWallClockSeconds()).isEqualTo(3600L);
    assertThat(defaults.maxOutputBytes()).isEqualTo(10_000_000L);
    assertThat(defaults.maxParallelCalls()).isEqualTo(4L);
  }

  @Test
  void the_getters_return_the_configured_bounds() {
    RunExecutionLimits limits = new RunExecutionLimits(7L, 3L, 2L, 60L, 500L, 1L);
    assertThat(limits.maxSteps()).isEqualTo(7L);
    assertThat(limits.maxToolCalls()).isEqualTo(3L);
    assertThat(limits.maxRetries()).isEqualTo(2L);
    assertThat(limits.maxWallClockSeconds()).isEqualTo(60L);
    assertThat(limits.maxOutputBytes()).isEqualTo(500L);
    assertThat(limits.maxParallelCalls()).isEqualTo(1L);
  }

  @Test
  void every_limit_is_configured_independently() {
    // Six independent bounds: the step, tool-call, retry, wall-clock, output and parallel limits,
    // each a distinct {@link Limit}.
    assertThat(RunExecutionLimits.Limit.values())
        .containsExactlyInAnyOrder(
            RunExecutionLimits.Limit.STEPS,
            RunExecutionLimits.Limit.TOOL_CALLS,
            RunExecutionLimits.Limit.RETRIES,
            RunExecutionLimits.Limit.WALL_CLOCK,
            RunExecutionLimits.Limit.OUTPUT_SIZE,
            RunExecutionLimits.Limit.PARALLEL_CALLS);
  }

  @Test
  void a_limit_of_zero_allows_nothing_of_that_kind() {
    // A bound of zero is a literal maximum of zero (the zero-sentinel convention): the value object
    // accepts it (zero is not negative) and exposes it like any other bound — it is the worker, not
    // the value object, that stops the run on it.
    RunExecutionLimits zero = new RunExecutionLimits(0L, 0L, 0L, 0L, 0L, 0L);
    assertThat(zero.maxSteps()).isZero();
    assertThat(zero.maxToolCalls()).isZero();
    assertThat(zero.maxRetries()).isZero();
    assertThat(zero.maxWallClockSeconds()).isZero();
    assertThat(zero.maxOutputBytes()).isZero();
    assertThat(zero.maxParallelCalls()).isZero();
  }

  @Test
  void the_constructor_rejects_negative_limits() {
    assertThatThrownBy(() -> new RunExecutionLimits(-1L, 0L, 0L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxSteps");
    assertThatThrownBy(() -> new RunExecutionLimits(0L, -1L, 0L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxToolCalls");
    assertThatThrownBy(() -> new RunExecutionLimits(0L, 0L, -1L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxRetries");
    assertThatThrownBy(() -> new RunExecutionLimits(0L, 0L, 0L, -1L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxWallClockSeconds");
    assertThatThrownBy(() -> new RunExecutionLimits(0L, 0L, 0L, 0L, -1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxOutputBytes");
    assertThatThrownBy(() -> new RunExecutionLimits(0L, 0L, 0L, 0L, 0L, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxParallelCalls");
  }

  @Test
  void the_limits_argument_must_not_be_null() {
    // The package-private (limits, Instant) constructor: a null bound set is rejected before the
    // start instant is read.
    assertThatThrownBy(
            () -> new RunExecutionMonitor((RunExecutionLimits) null, java.time.Instant.now()))
        .isInstanceOf(NullPointerException.class);
  }
}
