package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ProviderResiliencePolicy} value object (S09-09).
 *
 * <p>They verify the same invariants as the tool / step-retry policy ({@code
 * toolexecution.domain.RetryPolicy}, {@code workflowexecution.domain.RetryPolicy}) that this {@code
 * ai} module's own copy mirrors: reproducible, bounded backoff; the {@code maxAttempts} bound on
 * retry; a reproducible next-attempt instant under an injected instant/jitter; the maximum total
 * backoff bound; the constructor validation; and value equality. DB-free (no database, Kafka, model
 * or system clock): time and randomness are injected (invariant 10).
 */
class ProviderResiliencePolicyTest {

  // A small, deterministic policy: base 100 ms, 2x growth, capped at 1000 ms, no jitter.
  private static final ProviderResiliencePolicy NO_JITTER =
      ProviderResiliencePolicy.of(5, Duration.ofMillis(100), 2.0, Duration.ofMillis(1000), false);

  private static final ProviderResiliencePolicy WITH_JITTER =
      ProviderResiliencePolicy.of(5, Duration.ofMillis(100), 2.0, Duration.ofMillis(1000), true);

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
    // No overflow for a large attempt count: it saturates at the cap.
    assertThat(NO_JITTER.delayMillisForAttempt(50)).isEqualTo(1000L);
  }

  @Test
  void a_constant_multiplier_keeps_the_delay_at_the_base_value() {
    ProviderResiliencePolicy constant =
        ProviderResiliencePolicy.of(
            10, Duration.ofMillis(100), 1.0, Duration.ofMillis(1000), false);
    assertThat(constant.delayMillisForAttempt(1)).isEqualTo(100L);
    assertThat(constant.delayMillisForAttempt(7)).isEqualTo(100L);
  }

  @Test
  void jitter_delay_is_reproducible_under_a_fixed_fraction() {
    Supplier<Double> half = () -> 0.5;
    // full jitter: round(fraction * cappedDelay); cappedDelay for attempt 1 is 100 ms.
    assertThat(WITH_JITTER.delayMillisForAttempt(1, half)).isEqualTo(50L);
    assertThat(WITH_JITTER.delayMillisForAttempt(1, half)).isEqualTo(50L); // reproducible
  }

  @Test
  void full_jitter_stays_within_the_cap_even_when_the_fraction_is_large() {
    Supplier<Double> nearOne = () -> 0.999;
    // The capped delay is 1000 ms; even at 0.999 the returned value is strictly below the cap.
    long delay = WITH_JITTER.delayMillisForAttempt(1, nearOne);
    assertThat(delay).isLessThan(1000L);
    assertThat(delay).isGreaterThanOrEqualTo(0L);
  }

  @Test
  void a_null_jitter_source_returns_the_full_capped_delay() {
    // null disables jitter and returns the full capped delay (the no-jitter value).
    assertThat(WITH_JITTER.delayMillisForAttempt(3, null)).isEqualTo(400L);
  }

  @Test
  void jitter_outside_0_1_is_rejected() {
    assertThatThrownBy(() -> WITH_JITTER.delayMillisForAttempt(1, () -> 1.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WITH_JITTER.delayMillisForAttempt(1, () -> -0.1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_retry_is_allowed_only_while_the_max_attempts_is_not_exhausted() {
    assertThat(NO_JITTER.isRetryAllowed(1)).isTrue();
    assertThat(NO_JITTER.isRetryAllowed(4)).isTrue(); // attempts 1..4 allowed, attempt 5 is last
    assertThat(NO_JITTER.isRetryAllowed(5)).isFalse(); // the 5th attempt is terminal
  }

  @Test
  void next_attempt_time_is_the_reference_instant_plus_the_backoff() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    assertThat(NO_JITTER.nextAttemptTime(from, 1)).isEqualTo(from.plusMillis(100));
    assertThat(NO_JITTER.nextAttemptTime(from, 2)).isEqualTo(from.plusMillis(200));
  }

  @Test
  void the_max_total_backoff_is_bounded_by_attempts_minus_one_times_the_cap() {
    // (5 - 1) retries * 1000 ms cap = 4000 ms, no matter the observed failures.
    assertThat(NO_JITTER.maxTotalBackoffMillis()).isEqualTo(4000L);
    // The production default: (3 - 1) * 60 s.
    assertThat(ProviderResiliencePolicy.ofDefaults().maxTotalBackoffMillis())
        .isEqualTo(Duration.ofSeconds(120).toMillis());
  }

  @Test
  void the_constructor_rejects_an_invalid_policy() {
    assertThatThrownBy(
            () ->
                ProviderResiliencePolicy.of(
                    0, Duration.ofMillis(10), 2.0, Duration.ofMillis(100), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ProviderResiliencePolicy.of(
                    3, Duration.ofMillis(-1), 2.0, Duration.ofMillis(100), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ProviderResiliencePolicy.of(
                    3, Duration.ofMillis(10), 0.5, Duration.ofMillis(100), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ProviderResiliencePolicy.of(
                    3, Duration.ofMillis(100), 2.0, Duration.ofMillis(50), false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void two_policies_with_the_same_parameters_are_equal() {
    ProviderResiliencePolicy a =
        ProviderResiliencePolicy.of(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), true);
    ProviderResiliencePolicy b =
        ProviderResiliencePolicy.of(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), true);
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }
}
