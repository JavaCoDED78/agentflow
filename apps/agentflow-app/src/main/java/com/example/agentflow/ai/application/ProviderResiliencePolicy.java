package com.example.agentflow.ai.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.Supplier;

/**
 * Immutable retry policy for a text-generation call to the provider (S09-09).
 *
 * <p>A pure, dependency-free value object that captures <em>how</em> a failed provider call is
 * retried: the maximum number of attempts, the base delay, the exponential growth multiplier, the
 * maximum (capped) delay and whether full jitter is applied. It is the descriptor half of the
 * provider-resilience model — the failure classification that decides <em>whether</em> a fault is
 * retryable lives in {@code ai.provider.ProviderExceptionClassifier} and reads this policy to
 * schedule the next attempt.
 *
 * <p>It is a pure, dependency-free value (no Spring, no I/O) with value equality, so it can be
 * compared, cached and passed around like a constant. Two policies with the same parameters are
 * equal; a parameter change produces a new value.
 *
 * <p><b>No busy loop.</b> The policy never sleeps and never holds mutable state. It exposes the
 * delay before the next attempt ({@link #delayMillisForAttempt(int)}) and the instant that attempt
 * may start ({@link #nextAttemptTime(Instant, int)}); the resilient wrapper ({@code
 * ai.provider.ResilientChatModel}) stores that instant and simply checks {@code now >=
 * nextAttemptTime} between attempts rather than spinning.
 *
 * <p><b>Bounded.</b> A retry is allowed only while fewer than {@link #maxAttempts()} attempts have
 * run ({@link #isRetryAllowed(int)}); the {@code maxAttempts}-th failure is terminal, so a provider
 * call cannot retry forever. The total wait across every retry is bounded by {@link
 * #maxTotalBackoffMillis()}.
 *
 * <p><b>Testable.</b> The delay computation takes the jitter source as a parameter ({@link
 * #delayMillisForAttempt(int, Supplier)}) and {@link #nextAttemptTime(Instant, int)} takes the
 * reference instant, so time and randomness are injected rather than read from the system clock or
 * {@link Math#random()} (invariant 10) and the calculations are reproducible under a fixed seed.
 *
 * <p>This is the {@code ai} module's own retry policy, owned by {@code ai.application}. It mirrors
 * the {@code workflowexecution}/{@code toolexecution} step/tool retry policy because the three
 * share a shape, but the three live in different modules: the architecture gate forbids importing
 * the other modules' {@code domain.RetryPolicy}, so each module owns its own copy.
 *
 * @param maxAttempts the total number of attempts allowed (initial attempt plus retries), at least
 *     1
 * @param baseDelay the delay before the first retry ({@code multiplier^0}); never negative
 * @param multiplier the exponential growth factor per attempt, at least 1.0 (1.0 = constant delay)
 * @param maxDelay the upper bound every backoff is capped to; never negative and never below {@code
 *     baseDelay}
 * @param jitter whether full jitter is applied to the computed backoff (a random fraction of the
 *     capped delay)
 */
public record ProviderResiliencePolicy(
    int maxAttempts, Duration baseDelay, double multiplier, Duration maxDelay, boolean jitter) {

  /** Shared, thread-safe jitter source for the no-argument delay call (production). */
  private static final Supplier<Double> DEFAULT_JITTER = new SplittableRandom()::nextDouble;

  /**
   * Canonical-constructor validation. A policy with {@code maxAttempts < 1}, a negative delay, a
   * {@code multiplier < 1.0}, or a {@code maxDelay} below {@code baseDelay} is a configuration
   * error, so the constructor rejects it rather than producing a policy that retries nothing or
   * grows without bound.
   */
  public ProviderResiliencePolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1 but was " + maxAttempts);
    }
    if (baseDelay == null || baseDelay.isNegative()) {
      throw new IllegalArgumentException("baseDelay must be non-null and non-negative");
    }
    if (multiplier < 1.0) {
      throw new IllegalArgumentException("multiplier must be >= 1.0 but was " + multiplier);
    }
    if (maxDelay == null || maxDelay.isNegative()) {
      throw new IllegalArgumentException("maxDelay must be non-null and non-negative");
    }
    if (maxDelay.compareTo(baseDelay) < 0) {
      throw new IllegalArgumentException("maxDelay must be >= baseDelay");
    }
  }

  /**
   * @param maxAttempts the total number of attempts allowed (at least 1)
   * @param baseDelay the delay before the first retry (non-negative)
   * @param multiplier the exponential growth factor (at least 1.0)
   * @param maxDelay the capped upper bound (non-negative, at least {@code baseDelay})
   * @param jitter whether to apply full jitter
   * @return a validated policy, or {@link IllegalArgumentException} if any parameter is invalid
   */
  public static ProviderResiliencePolicy of(
      int maxAttempts, Duration baseDelay, double multiplier, Duration maxDelay, boolean jitter) {
    return new ProviderResiliencePolicy(maxAttempts, baseDelay, multiplier, maxDelay, jitter);
  }

  /**
   * @return the common production default: up to 3 attempts, a 1 s base delay, 2× growth, capped at
   *     60 s, with full jitter enabled
   */
  public static ProviderResiliencePolicy ofDefaults() {
    return of(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(60), true);
  }

  /**
   * The delay, in milliseconds, before the retry that follows the {@code attemptsUsed}-th completed
   * attempt, applying this policy's configured jitter: full jitter (the shared production source)
   * when {@link #jitter()} is {@code true}, and no jitter when it is {@code false}.
   *
   * @param attemptsUsed the number of attempts already made (the delay applies to the retry after
   *     this one; call it with {@code >= 1} when scheduling a retry)
   * @return the capped, optionally-jittered backoff in milliseconds
   */
  public long delayMillisForAttempt(int attemptsUsed) {
    return delayMillisForAttempt(attemptsUsed, jitter ? DEFAULT_JITTER : null);
  }

  /**
   * Pure, reproducible delay for the retry following the {@code attemptsUsed}-th attempt: {@code
   * min(baseDelay * multiplier^(attemptsUsed-1), maxDelay)}, reduced by full jitter when the
   * supplied source is non-null. This is the injection seam: a fixed supplier yields a
   * deterministic value in tests, and {@code null} returns the full capped delay. It applies the
   * supplier as given (ignoring {@link #jitter()}) so tests can pin any jitter behaviour
   * independently of the policy's flag.
   *
   * <p>Full jitter returns {@code round(random(0,1) * cappedDelay)}, never the full delay, so the
   * maximum wait is still bounded by {@code maxDelay} while the retries are spread out.
   *
   * @param attemptsUsed the number of attempts already made (the delay before the following retry);
   *     values below 1 are treated as the first retry (exponent clamped to 0)
   * @param jitter the jitter source in {@code [0, 1)}; {@code null} disables jitter (returns the
   *     full capped delay)
   * @return the capped, optionally-jittered backoff in milliseconds
   * @throws IllegalArgumentException if {@code jitter} is supplied outside {@code [0, 1)}
   */
  public long delayMillisForAttempt(int attemptsUsed, Supplier<Double> jitter) {
    long capped = cappedBackoffMillis(attemptsUsed);
    if (jitter == null) {
      return capped;
    }
    double fraction = jitter.get();
    if (fraction < 0.0 || fraction >= 1.0) {
      throw new IllegalArgumentException("jitter must be in [0, 1) but was " + fraction);
    }
    return (long) (fraction * capped);
  }

  /**
   * @param attemptsUsed the number of attempts already made
   * @return {@code true} while fewer than {@link #maxAttempts()} attempts have run, i.e. another
   *     attempt may still be scheduled; {@code false} once the last attempt has failed, so the
   *     provider call fails instead of retrying forever
   */
  public boolean isRetryAllowed(int attemptsUsed) {
    return attemptsUsed < maxAttempts;
  }

  /**
   * The earliest instant the retry following the {@code attemptsUsed}-th attempt may start,
   * computed as {@code from + delayMillisForAttempt(attemptsUsed)}.
   *
   * <p>This is the "store the next attempt time instead of a busy loop" seam: the resilient wrapper
   * stores the returned instant and wakes when {@code now} reaches it, rather than sleeping in a
   * loop. {@code from} is the reference instant, so tests inject a fixed instant (invariant 10).
   *
   * @param from the reference instant (an injected fixed instant in tests)
   * @param attemptsUsed the number of attempts already made
   * @return the instant the next attempt may start
   */
  public Instant nextAttemptTime(Instant from, int attemptsUsed) {
    Objects.requireNonNull(from, "from must not be null");
    return from.plusMillis(delayMillisForAttempt(attemptsUsed));
  }

  /**
   * Pure, reproducible variant of {@link #nextAttemptTime(Instant, int)} that injects the jitter
   * source, so a fixed supplier yields a deterministic retry instant in tests (time and randomness
   * both injected, invariant 10).
   *
   * @param from the reference instant (an injected fixed instant in tests)
   * @param attemptsUsed the number of attempts already made
   * @param jitter the jitter source in {@code [0, 1)}; {@code null} disables jitter
   * @return the instant the next attempt may start
   */
  public Instant nextAttemptTime(Instant from, int attemptsUsed, Supplier<Double> jitter) {
    Objects.requireNonNull(from, "from must not be null");
    return from.plusMillis(delayMillisForAttempt(attemptsUsed, jitter));
  }

  /**
   * The maximum total time, in milliseconds, spent sleeping between attempts across the whole call:
   * {@code (maxAttempts - 1) * maxDelay}. A single provider call therefore never waits longer than
   * this between its attempts, independent of the observed failures — this is the upper bound the
   * acceptance test (S09-09) checks.
   *
   * @return the upper bound on the total inter-attempt backoff in milliseconds
   */
  public long maxTotalBackoffMillis() {
    long perRetry = maxDelay.toMillis();
    long attempts = Math.max(0, maxAttempts - 1);
    return perRetry * attempts;
  }

  /**
   * Exponential backoff {@code baseDelay * multiplier^(attemptsUsed-1)} capped at {@code maxDelay},
   * computed by iterative growth so a large {@code attemptsUsed} saturates at the cap instead of
   * overflowing.
   *
   * @param attemptsUsed the number of attempts already made (exponent is {@code max(0,
   *     attemptsUsed-1)})
   * @return the capped backoff in milliseconds
   */
  private long cappedBackoffMillis(int attemptsUsed) {
    long base = baseDelay.toMillis();
    long cap = maxDelay.toMillis();
    long delay = base;
    int exponent = Math.max(0, attemptsUsed - 1);
    for (int i = 0; i < exponent; i++) {
      double grown = (double) delay * multiplier;
      if (grown >= cap || Double.isInfinite(grown)) {
        return cap;
      }
      delay = (long) grown;
      if (delay >= cap) {
        return cap;
      }
    }
    return Math.min(delay, cap);
  }
}
