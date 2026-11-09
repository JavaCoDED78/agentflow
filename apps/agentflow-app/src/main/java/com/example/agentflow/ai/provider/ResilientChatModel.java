package com.example.agentflow.ai.provider;

import com.example.agentflow.ai.application.ChatModelPort;
import com.example.agentflow.ai.application.ChatRequest;
import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.ProviderFailureReason;
import com.example.agentflow.ai.application.ProviderResiliencePolicy;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A resilient {@link ChatModelPort} that wraps a provider call with bounded backoff, an overall
 * timeout and a concurrency cap (S09-09).
 *
 * <p>It is the {@code ai} module's adapter-layer home for provider resilience (ADR-006 keeps the
 * provider SDK behind the {@code ai.provider} package). It implements the {@link ChatModelPort}
 * interface, so callers (the planner, the evaluation node) see exactly one boundary and do not care
 * whether the underlying call was retried, aborted by the overall timeout or throttled by the
 * concurrency cap.
 *
 * <p>Three independent bounds are applied, each of which is independently testable:
 *
 * <ol>
 *   <li><b>Bounded retry.</b> A failed call is retried with exponential backoff + full jitter only
 *       while {@link ProviderResiliencePolicy#isRetryAllowed(int)} holds and the fault is {@link
 *       ProviderFailureReason#isRetryable()}. The failure classification is injected, so the
 *       SDK-specific mapping in {@link ProviderExceptionClassifier} does not leak here.
 *   <li><b>Overall timeout.</b> Each attempt runs on a daemon thread and is bounded by {@link
 *       Future#overAllTimeout(long, TimeUnit)}; an abort fails the attempt as {@link
 *       ProviderTimeoutException} (classified as {@link ProviderFailureReason#TIMEOUT}).
 *   <li><b>Concurrency cap.</b> A {@link Semaphore} of {@link #maxParallelCalls()} permits limits
 *       how many attempts run at once; a call that cannot take a permit fails immediately with
 *       {@link ProviderConcurrencyLimitedException}, which the retry loop treats as a transient,
 *       retryable backpressure signal.
 * </ol>
 *
 * <p><b>Bounded, not exactly-once.</b> An external call is by definition not exactly-once
 * (invariant 6); this wrapper only makes a failed call <em>fail cleanly and in bounded time</em>.
 * It counts attempts only for logging/metrics; it never makes the provider call idempotent.
 *
 * <p>Time, randomness and the call scheduler are injected (invariant 10): the jitter source, the
 * sleeper and the call executor can be supplied so the retry behaviour is reproducible under a
 * fixed seed in tests.
 *
 * @param delegate the wrapped provider port — the call is delegated to it, never bypassed
 * @param policy the bounded backoff descriptor ({@link ProviderResiliencePolicy})
 * @param classifier the function mapping a thrown exception to a failure reason ({@link
 *     ProviderExceptionClassifier#classify(Throwable)} in production)
 * @param overallTimeout the wall-clock bound for a single attempt
 * @param maxParallelCalls the concurrency cap (at least 1)
 * @param jitter the jitter source in {@code [0, 1)} (production: the shared source)
 * @param sleeper the function that pauses between retries (production: {@code Thread::sleep})
 * @param callExecutor the executor running the overall-timeout-bound attempt (production: a daemon
 *     pool)
 */
public class ResilientChatModel implements ChatModelPort {

  private static final Logger log = LoggerFactory.getLogger(ResilientChatModel.class);

  private final ChatModelPort delegate;
  private final ProviderResiliencePolicy policy;
  private final Function<Throwable, ProviderFailureReason> classifier;
  private final Duration overallTimeout;
  private final int maxParallelCalls;
  private final Supplier<Double> jitter;
  private final Consumer<Long> sleeper;
  private final ExecutorService callExecutor;
  private final Semaphore semaphore;

  /**
   * @param delegate the wrapped provider port (non-null)
   * @param policy the bounded backoff descriptor (validated by {@link ProviderResiliencePolicy})
   * @param classifier the exception-to-reason classifier (non-null)
   * @param overallTimeout the wall-clock bound for a single attempt (non-negative)
   * @param maxParallelCalls the concurrency cap (at least 1)
   * @param jitter the jitter source in {@code [0, 1)} (may be null → no jitter)
   * @param sleeper the pause-between-retries function (non-null)
   * @param callExecutor the executor running the bound attempt (non-null)
   */
  public ResilientChatModel(
      ChatModelPort delegate,
      ProviderResiliencePolicy policy,
      Function<Throwable, ProviderFailureReason> classifier,
      Duration overallTimeout,
      int maxParallelCalls,
      Supplier<Double> jitter,
      Consumer<Long> sleeper,
      ExecutorService callExecutor) {
    this.delegate = requireNonNull(delegate, "delegate");
    this.policy = requireNonNull(policy, "policy");
    this.classifier = requireNonNull(classifier, "classifier");
    if (overallTimeout == null || overallTimeout.isNegative()) {
      throw new IllegalArgumentException("overallTimeout must be non-negative");
    }
    this.overallTimeout = overallTimeout;
    if (maxParallelCalls < 1) {
      throw new IllegalArgumentException(
          "maxParallelCalls must be >= 1 but was " + maxParallelCalls);
    }
    this.maxParallelCalls = maxParallelCalls;
    this.jitter = jitter;
    this.sleeper = requireNonNull(sleeper, "sleeper");
    this.callExecutor = requireNonNull(callExecutor, "callExecutor");
    this.semaphore = new Semaphore(maxParallelCalls);
  }

  /**
   * Production factory: supplies the shared jitter source, the {@code Thread::sleep} sleeper and a
   * daemon thread pool for the overall-timeout-bound attempts.
   *
   * @param delegate the wrapped provider port (non-null)
   * @param policy the bounded backoff descriptor
   * @param classifier the exception-to-reason classifier
   * @param overallTimeout the wall-clock bound for a single attempt
   * @param maxParallelCalls the concurrency cap (at least 1)
   * @return a resilient port using the shared production seams
   */
  public static ResilientChatModel of(
      ChatModelPort delegate,
      ProviderResiliencePolicy policy,
      Function<Throwable, ProviderFailureReason> classifier,
      Duration overallTimeout,
      int maxParallelCalls) {
    return new ResilientChatModel(
        delegate,
        policy,
        classifier,
        overallTimeout,
        maxParallelCalls,
        SplittableJitter.DEFAULT,
        ResilientChatModel::defaultSleep,
        Executors.newCachedThreadPool(daemonFactory()));
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  private static void defaultSleep(long delayMillis) {
    if (delayMillis <= 0) {
      return;
    }
    try {
      Thread.sleep(delayMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static ThreadFactory daemonFactory() {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "ai-resilient-call-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * @return the wrapped provider port the resilient layer delegates each attempt to
   */
  ChatModelPort delegate() {
    return delegate;
  }

  /**
   * Perform one chat call with bounded retry, an overall timeout and a concurrency cap.
   *
   * @param request the request to send
   * @return the provider's response
   * @throws ProviderConcurrencyLimitedException if the concurrency cap is reached and no permit is
   *     available (a transient, retryable backpressure signal)
   */
  @Override
  public ChatResponse chat(ChatRequest request) {
    RuntimeException lastError = null;
    for (int used = 1; used <= policy.maxAttempts(); used++) {
      try {
        return performCallWithOverallTimeout(request);
      } catch (RuntimeException e) {
        lastError = e;
        boolean retryable =
            e instanceof ProviderConcurrencyLimitedException || classifier.apply(e).isRetryable();
        if (!retryable || !policy.isRetryAllowed(used)) {
          throw e;
        }
        long backoff = policy.delayMillisForAttempt(used, jitter);
        log.warn(
            "Provider chat attempt {} of {} failed ({}); retrying in {} ms",
            used,
            policy.maxAttempts(),
            e.getClass().getSimpleName(),
            backoff);
        sleeper.accept(backoff);
      }
    }
    // Unreachable in practice: the loop either returns or throws, but the compiler needs a
    // terminal statement. If it is reached, the last error was already classified non-retryable.
    throw lastError != null
        ? lastError
        : new IllegalStateException("resilient chat call exhausted without a result");
  }

  /**
   * Take a permit (or fail with {@link ProviderConcurrencyLimitedException}), run one attempt under
   * the overall timeout, and always release the permit.
   *
   * @param request the request to send
   * @return the provider's response
   * @throws ProviderConcurrencyLimitedException if no permit is available
   */
  private ChatResponse performCallWithOverallTimeout(ChatRequest request) {
    if (!semaphore.tryAcquire()) {
      throw new ProviderConcurrencyLimitedException(maxParallelCalls);
    }
    try {
      return runAttemptWithOverallTimeout(request);
    } finally {
      semaphore.release();
    }
  }

  /**
   * Run one attempt on the call executor, bounded by the overall timeout.
   *
   * @param request the request to send
   * @return the provider's response
   * @throws ProviderTimeoutException if the attempt exceeds the overall timeout
   */
  private ChatResponse runAttemptWithOverallTimeout(ChatRequest request) {
    Future<ChatResponse> future = callExecutor.submit(() -> delegate.chat(request));
    try {
      return future.get(overallTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      future.cancel(true);
      throw new ProviderTimeoutException(overallTimeout, timeout);
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new RuntimeException("resilient chat call interrupted", interrupted);
    } catch (ExecutionException thrown) {
      Throwable cause = thrown.getCause() != null ? thrown.getCause() : thrown;
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new RuntimeException(cause);
    }
  }

  /**
   * Shuts the call executor down. Invoked by Spring when the managed bean is destroyed so the
   * daemon threads do not leak if the context is closed.
   */
  @PreDestroy
  public void close() {
    callExecutor.shutdownNow();
  }

  /**
   * Thrown when the concurrency cap is reached and the call cannot take a permit. It is a
   * transient, retryable backpressure signal (not a provider fault), so the retry loop retries it
   * rather than failing immediately.
   */
  public static class ProviderConcurrencyLimitedException extends RuntimeException {
    private final int maxParallelCalls;

    public ProviderConcurrencyLimitedException(int maxParallelCalls) {
      super(
          "Chat call throttled: the provider concurrency cap of "
              + maxParallelCalls
              + " was reached");
      this.maxParallelCalls = maxParallelCalls;
    }

    /**
     * @return the concurrency cap that was exceeded
     */
    public int maxParallelCalls() {
      return maxParallelCalls;
    }
  }

  /**
   * Thrown when a single attempt exceeds the overall timeout. It is a {@link RuntimeException}, so
   * it flows through the {@link #chat(ChatRequest)} retry loop like every other provider fault and
   * is classified as {@link ProviderFailureReason#TIMEOUT} (retryable) by {@link
   * ProviderExceptionClassifier}.
   */
  public static class ProviderTimeoutException extends RuntimeException {
    private final Duration overallTimeout;

    public ProviderTimeoutException(Duration overallTimeout, Throwable cause) {
      super("Chat call exceeded the overall timeout of " + overallTimeout);
      this.overallTimeout = overallTimeout;
      initCause(cause);
    }

    /**
     * @return the overall timeout that was exceeded
     */
    public Duration getOverallTimeout() {
      return overallTimeout;
    }
  }

  /** Shared, thread-safe production jitter source. */
  private static final class SplittableJitter {
    private static final Supplier<Double> DEFAULT = new SplittableRandom()::nextDouble;
  }
}
