package com.example.agentflow.workflowexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@link RetryPolicy} value object (S07-10).
 *
 * <p>They verify the acceptance criterion — "delay calculations are reproducible and bounded by max
 * attempts" — directly against the value object: the exponential backoff progression and the {@code
 * maxDelay} cap, the reproducibility of a jittered delay under an injected fixed jitter source, the
 * {@code maxAttempts} bound on {@link #isRetryAllowed(int)}, the {@link #nextAttemptTime(Instant,
 * int)} computation with an injected instant, the constructor validation, and value equality.
 * DB-free (no database, Kafka, model or system clock): time and randomness are injected (invariant
 * 10).
 */
class RetryPolicyTest {

  // A small, deterministic policy: base 100 ms, 2x growth, capped at 1000 ms, no jitter.
  private static final RetryPolicy NO_JITTER =
      RetryPolicy.of(5, Duration.ofMillis(100), 2.0, Duration.ofMillis(1000), false);

  // Same shape but jitter enabled.
  private static final RetryPolicy WITH_JITTER =
      RetryPolicy.of(5, Duration.ofMillis(100), 2.0, Duration.ofMillis(1000), true);

  // ---------------------------------------------------------------------------
  // Exponential backoff progression (no jitter -> fully deterministic)
  // ---------------------------------------------------------------------------

  @Test
  void backoff_grows_exponentially_from_the_base_delay() {
    assertThat(NO_JITTER.delayMillisForAttempt(1)).isEqualTo(100L); // 100 * 2^0
    assertThat(NO_JITTER.delayMillisForAttempt(2)).isEqualTo(200L); // 100 * 2^1
    assertThat(NO_JITTER.delayMillisForAttempt(3)).isEqualTo(400L); // 100 * 2^2
    assertThat(NO_JITTER.delayMillisForAttempt(4)).isEqualTo(800L); // 100 * 2^3
  }

  @Test
  void backoff_is_capped_at_max_delay_and_stays_capped() {
    // 100 * 2^4 = 1600 > 1000 -> capped; every later attempt is also capped.
    assertThat(NO_JITTER.delayMillisForAttempt(5)).isEqualTo(1000L);
    assertThat(NO_JITTER.delayMillisForAttempt(6)).isEqualTo(1000L);
    assertThat(NO_JITTER.delayMillisForAttempt(50)).isEqualTo(1000L); // no overflow, stays at cap
  }

  @Test
  void a_constant_multiplier_keeps_the_delay_at_the_base_value() {
    RetryPolicy constant =
        RetryPolicy.of(10, Duration.ofMillis(100), 1.0, Duration.ofMillis(1000), false);
    assertThat(constant.delayMillisForAttempt(1)).isEqualTo(100L);
    assertThat(constant.delayMillisForAttempt(2)).isEqualTo(100L);
    assertThat(constant.delayMillisForAttempt(7)).isEqualTo(100L);
  }

  // ---------------------------------------------------------------------------
  // Jitter: reproducible under an injected source (the "inject random for tests" seam)
  // ---------------------------------------------------------------------------

  @Test
  void jitter_disabled_returns_the_full_capped_delay() {
    // No jitter argument at all -> the production call uses the shared default, but here we pass a
    // null supplier to assert the non-jittered path returns the whole capped delay.
    RetryPolicy policy =
        RetryPolicy.of(5, Duration.ofMillis(100), 2.0, Duration.ofMillis(1000), true);
    assertThat(policy.delayMillisForAttempt(1, null)).isEqualTo(100L);
    assertThat(policy.delayMillisForAttempt(2, null)).isEqualTo(200L);
  }

  @Test
  void jittered_delay_is_reproducible_under_a_fixed_fraction() {
    Supplier<Double> half = () -> 0.5;
    Supplier<Double> zero = () -> 0.0;
    // full jitter: round(fraction * cappedDelay); cappedDelay for attempt 1 is 100 ms.
    assertThat(WITH_JITTER.delayMillisForAttempt(1, half)).isEqualTo(50L);
    assertThat(WITH_JITTER.delayMillisForAttempt(1, zero)).isEqualTo(0L);
    // The same supplier yields the same value: reproducible.
    assertThat(WITH_JITTER.delayMillisForAttempt(1, half)).isEqualTo(50L);
  }

  @Test
  void full_jitter_never_exceeds_the_capped_delay() {
    // Exercise the real production jitter source over many draws: every value must stay in
    // [0, cappedDelay], never above the cap (the whole point of jittering the cap, not adding to
    // it).
    // The non-jittered call returns the full capped delay to compare against.
    SplittableRandom random = new SplittableRandom(42L);
    Supplier<Double> jitter = random::nextDouble;
    for (int attemptsUsed = 1; attemptsUsed <= 6; attemptsUsed++) {
      long capped = WITH_JITTER.delayMillisForAttempt(attemptsUsed, null);
      for (int draw = 0; draw < 500; draw++) {
        long delayed = WITH_JITTER.delayMillisForAttempt(attemptsUsed, jitter);
        assertThat(delayed).as("draw %d of attempt %d", draw, attemptsUsed).isBetween(0L, capped);
      }
    }
  }

  @Test
  void an_out_of_range_jitter_fraction_is_rejected() {
    assertThatThrownBy(() -> WITH_JITTER.delayMillisForAttempt(1, () -> 1.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("[0, 1)");
    assertThatThrownBy(() -> WITH_JITTER.delayMillisForAttempt(1, () -> -0.1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------------------
  // Bounded by max attempts (the acceptance bound)
  // ---------------------------------------------------------------------------

  @Test
  void a_retry_is_allowed_only_below_max_attempts() {
    RetryPolicy policy =
        RetryPolicy.of(3, Duration.ofMillis(10), 2.0, Duration.ofMillis(1000), false);
    assertThat(policy.isRetryAllowed(0)).isTrue(); // before the first attempt
    assertThat(policy.isRetryAllowed(1)).isTrue(); // after attempt 1 -> retry allowed
    assertThat(policy.isRetryAllowed(2)).isTrue(); // after attempt 2 -> retry allowed
    assertThat(policy.isRetryAllowed(3)).isFalse(); // after the 3rd (final) attempt -> terminal
    assertThat(policy.isRetryAllowed(4)).isFalse();
  }

  @Test
  void max_attempts_bounds_the_total_number_of_attempts() {
    // With maxAttempts = 4 the run performs one initial attempt plus up to three retries.
    // isRetryAllowed(n) asks "after n attempts, may another run?", so it is true for n = 1,2,3 and
    // false from n = 4 on; the initial attempt itself is not counted here.
    RetryPolicy policy =
        RetryPolicy.of(4, Duration.ofMillis(10), 2.0, Duration.ofMillis(1000), false);
    int allowedNextAttempts = 0;
    for (int attemptsUsed = 1; attemptsUsed <= 10; attemptsUsed++) {
      if (policy.isRetryAllowed(attemptsUsed)) {
        allowedNextAttempts++;
      }
    }
    // Exactly maxAttempts - 1 = 3 retries are allowed after the first attempt, i.e. 4 attempts
    // total.
    assertThat(allowedNextAttempts).isEqualTo(3);
  }

  // ---------------------------------------------------------------------------
  // Next attempt time (the "store the next attempt time instead of a busy loop" seam)
  // ---------------------------------------------------------------------------

  @Test
  void next_attempt_time_is_the_reference_instant_plus_the_delay() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    // No jitter: attempt 1 delay is 100 ms.
    assertThat(NO_JITTER.nextAttemptTime(from, 1))
        .isEqualTo(Instant.parse("2026-01-01T00:00:00.1Z"));
    assertThat(NO_JITTER.nextAttemptTime(from, 2))
        .isEqualTo(Instant.parse("2026-01-01T00:00:00.2Z"));
  }

  @Test
  void next_attempt_time_is_reproducible_under_an_injected_instant() {
    Instant from = Instant.parse("2026-06-01T12:00:00Z");
    Supplier<Double> half = () -> 0.5;
    // jittered: round(0.5 * 100) = 50 ms from the injected instant.
    assertThat(WITH_JITTER.nextAttemptTime(from, 1, half))
        .isEqualTo(Instant.parse("2026-06-01T12:00:00.05Z"));
  }

  // ---------------------------------------------------------------------------
  // Construction: defaults, equality and validation
  // ---------------------------------------------------------------------------

  @Test
  void of_defaults_matches_the_documented_production_default() {
    RetryPolicy policy = RetryPolicy.ofDefaults();
    assertThat(policy.maxAttempts()).isEqualTo(3);
    assertThat(policy.baseDelay()).isEqualTo(Duration.ofSeconds(1));
    assertThat(policy.multiplier()).isEqualTo(2.0);
    assertThat(policy.maxDelay()).isEqualTo(Duration.ofSeconds(60));
    assertThat(policy.jitter()).isTrue();
  }

  @Test
  void policies_with_equal_parameters_are_value_equal() {
    RetryPolicy a = RetryPolicy.of(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), true);
    RetryPolicy b = RetryPolicy.of(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), true);
    RetryPolicy c = RetryPolicy.of(4, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), true);
    assertThat(a).isEqualTo(b);
    assertThat(a).isNotEqualTo(c);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void the_constructor_rejects_an_invalid_configuration() {
    assertThatThrownBy(
            () -> RetryPolicy.of(0, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxAttempts");
    assertThatThrownBy(
            () -> RetryPolicy.of(3, Duration.ofSeconds(-1), 2.0, Duration.ofSeconds(60), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseDelay");
    assertThatThrownBy(
            () -> RetryPolicy.of(3, Duration.ofSeconds(1), 0.5, Duration.ofSeconds(60), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("multiplier");
    assertThatThrownBy(
            () -> RetryPolicy.of(3, Duration.ofSeconds(30), 2.0, Duration.ofSeconds(10), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxDelay");
  }
}
