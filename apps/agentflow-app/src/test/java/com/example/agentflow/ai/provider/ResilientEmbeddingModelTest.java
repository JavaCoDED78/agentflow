package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.ai.application.EmbeddingUsage;
import com.example.agentflow.ai.application.ProviderFailureReason;
import com.example.agentflow.ai.application.ProviderResiliencePolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the resilient embedding port (S10-12).
 *
 * <p>These prove the wrapper applies the S10-12 requirements to an embedding call: a provider rate
 * limit (HTTP 429) is retried with bounded backoff, a permanent client error fails immediately, the
 * total number of attempts is bounded by the policy, a single attempt is aborted by the overall
 * timeout and the concurrency cap rejects overload with a retryable backpressure signal. The time
 * (jitter), the sleep between attempts and the call executor are all injected through the
 * constructor, so every outcome is deterministic and DB-free — no network, model, database, Kafka
 * or system clock (invariant 10). The delegate is a local {@link FakeEmbeddingPort} double.
 */
class ResilientEmbeddingModelTest {

  private static final String MODEL = "nomic-embed-text";

  /** A transient, retryable fault the fake delegate throws. */
  private static final class TransientFault extends RuntimeException {
    TransientFault() {
      super("transient provider fault");
    }
  }

  /** A permanent, non-retryable fault the fake delegate throws. */
  private static final class PermanentFault extends RuntimeException {
    PermanentFault() {
      super("permanent provider fault");
    }
  }

  /** The same classification the production {@code ProviderExceptionClassifier} applies. */
  private static final Function<Throwable, ProviderFailureReason> CLASSIFIER =
      failure ->
          failure instanceof TransientFault
              ? ProviderFailureReason.RATE_LIMIT
              : failure instanceof PermanentFault
                  ? ProviderFailureReason.CLIENT_ERROR
                  : ProviderFailureReason.UNKNOWN;

  private final ExecutorService callExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "test-resilient-embedding");
            thread.setDaemon(true);
            return thread;
          });

  @AfterEach
  void shut_down_executor() {
    callExecutor.shutdownNow();
  }

  private static EmbeddingRequest request() {
    return new EmbeddingRequest(MODEL, List.of("alpha", "beta"));
  }

  private static EmbeddingResponse response(int count) {
    List<float[]> vectors = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      vectors.add(new float[] {0.1f, 0.2f, 0.3f});
    }
    return new EmbeddingResponse(MODEL, vectors, new EmbeddingUsage(6, 3));
  }

  private ResilientEmbeddingModel resilient(
      EmbeddingPort delegate, ProviderResiliencePolicy policy, List<Long> sleepLog) {
    // Null jitter: the retry loop then takes the full capped backoff, so the sleep log asserts
    // the exact computed delays (invariant 10 — randomness injected, here disabled).
    return new ResilientEmbeddingModel(
        delegate, policy, CLASSIFIER, Duration.ofSeconds(1), 1, null, sleepLog::add, callExecutor);
  }

  private static final class FakeEmbeddingPort implements EmbeddingPort {
    volatile int calls = 0;
    volatile boolean blocks = false;
    volatile long blockMillis = 0;
    CountDownLatch entered;
    CountDownLatch release;

    final EmbeddingResponse ok;
    final List<RuntimeException> perCallFailures = new ArrayList<>();

    FakeEmbeddingPort(EmbeddingResponse ok) {
      this.ok = ok;
    }

    FakeEmbeddingPort failWith(RuntimeException failure) {
      perCallFailures.add(failure);
      return this;
    }

    FakeEmbeddingPort blockOn(long blockMillis) {
      this.blocks = true;
      this.blockMillis = blockMillis;
      return this;
    }

    FakeEmbeddingPort signal(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
      return this;
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
      calls++;
      int index = calls - 1;
      if (blocks) {
        if (entered != null) {
          entered.countDown();
        }
        if (release != null) {
          awaitRelease();
        }
        if (blockMillis > 0) {
          sleepQuietly(blockMillis);
        }
      }
      if (index < perCallFailures.size()) {
        throw perCallFailures.get(index);
      }
      return ok;
    }

    private void awaitRelease() {
      try {
        if (!release.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test release latch never opened");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private static void sleepQuietly(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Test
  void a_successful_call_passes_through_on_the_first_attempt() {
    FakeEmbeddingPort delegate = new FakeEmbeddingPort(response(2));
    List<Long> sleepLog = new ArrayList<>();

    EmbeddingResponse result =
        resilient(delegate, ProviderResiliencePolicy.ofDefaults(), sleepLog).embed(request());

    assertThat(result.embeddings()).hasSize(2);
    assertThat(delegate.calls).isEqualTo(1);
    assertThat(sleepLog).isEmpty();
  }

  @Test
  void a_rate_limit_fault_is_retried_until_the_call_succeeds() {
    // The S10-12 headline case: a 429 rate limit must be retried, not propagated.
    FakeEmbeddingPort delegate =
        new FakeEmbeddingPort(response(2))
            .failWith(new TransientFault())
            .failWith(new TransientFault());
    List<Long> sleepLog = new ArrayList<>();

    EmbeddingResponse result =
        resilient(delegate, ProviderResiliencePolicy.ofDefaults(), sleepLog).embed(request());

    assertThat(result.embeddings()).hasSize(2);
    assertThat(delegate.calls).isEqualTo(3);
    assertThat(sleepLog).containsExactly(1000L, 2000L);
  }

  @Test
  void a_permanent_fault_fails_immediately_without_a_retry() {
    FakeEmbeddingPort delegate = new FakeEmbeddingPort(response(2)).failWith(new PermanentFault());
    List<Long> sleepLog = new ArrayList<>();

    assertThatThrownBy(
            () ->
                resilient(delegate, ProviderResiliencePolicy.ofDefaults(), sleepLog)
                    .embed(request()))
        .isInstanceOf(PermanentFault.class);
    assertThat(delegate.calls).isEqualTo(1);
    assertThat(sleepLog).isEmpty();
  }

  @Test
  void an_exhausted_retry_budget_throws_the_last_fault_and_stops() {
    FakeEmbeddingPort delegate =
        new FakeEmbeddingPort(response(2))
            .failWith(new TransientFault())
            .failWith(new TransientFault())
            .failWith(new TransientFault());
    List<Long> sleepLog = new ArrayList<>();
    ProviderResiliencePolicy policy =
        ProviderResiliencePolicy.of(3, Duration.ofMillis(10), 2.0, Duration.ofMillis(100), false);

    assertThatThrownBy(() -> resilient(delegate, policy, sleepLog).embed(request()))
        .isInstanceOf(TransientFault.class);
    assertThat(delegate.calls).isEqualTo(3);
    assertThat(sleepLog).containsExactly(10L, 20L);
  }

  @Test
  void a_single_attempt_is_aborted_by_the_overall_timeout() {
    FakeEmbeddingPort delegate = new FakeEmbeddingPort(response(2)).blockOn(2000);
    ProviderResiliencePolicy noRetry =
        ProviderResiliencePolicy.of(1, Duration.ZERO, 1.0, Duration.ZERO, false);

    ResilientEmbeddingModel resilient =
        new ResilientEmbeddingModel(
            delegate,
            noRetry,
            new ProviderExceptionClassifier()::classify,
            Duration.ofMillis(50),
            1,
            () -> 0.0,
            ignored -> {},
            callExecutor);

    assertThatThrownBy(() -> resilient.embed(request()))
        .isInstanceOfSatisfying(
            ResilientEmbeddingModel.ProviderTimeoutException.class,
            timeout ->
                assertThat(new ProviderExceptionClassifier().classify(timeout))
                    .isEqualTo(ProviderFailureReason.TIMEOUT));
    assertThat(delegate.calls).isEqualTo(1);
  }

  @Test
  void the_concurrency_cap_rejects_overload_with_a_retryable_backpressure_fault() {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    FakeEmbeddingPort delegate =
        new FakeEmbeddingPort(response(2)).blockOn(0).signal(entered, release);
    ResilientEmbeddingModel resilient =
        new ResilientEmbeddingModel(
            delegate,
            ProviderResiliencePolicy.of(2, Duration.ZERO, 1.0, Duration.ZERO, false),
            CLASSIFIER,
            Duration.ofSeconds(5),
            1,
            () -> 0.0,
            ignored -> {},
            callExecutor);

    try {
      // First call occupies the only permit; the second is rejected, retried after the first
      // releases, and then succeeds.
      var waiter = callExecutor.submit(() -> resilient.embed(request()));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> resilient.embed(request()))
          .isInstanceOfSatisfying(
              ResilientEmbeddingModel.ProviderConcurrencyLimitedException.class,
              throttled -> {
                assertThat(throttled.maxParallelCalls()).isEqualTo(1);
                assertThat(throttled).isInstanceOf(RuntimeException.class);
              });
      release.countDown();
      assertThat(waiter.get(5, TimeUnit.SECONDS).embeddings()).hasSize(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void constructor_guards_reject_nulls_and_nonsensical_bounds() {
    EmbeddingPort delegate = new FakeEmbeddingPort(response(2));
    ProviderResiliencePolicy policy = ProviderResiliencePolicy.ofDefaults();
    Function<Throwable, ProviderFailureReason> classifier = CLASSIFIER;
    Duration timeout = Duration.ofSeconds(1);
    Consumer<Long> sleeper = ignored -> {};

    assertThatThrownBy(
            () ->
                new ResilientEmbeddingModel(
                    null, policy, classifier, timeout, 1, () -> 0.0, sleeper, callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResilientEmbeddingModel(
                    delegate, null, classifier, timeout, 1, () -> 0.0, sleeper, callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResilientEmbeddingModel(
                    delegate, policy, null, timeout, 1, () -> 0.0, sleeper, callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResilientEmbeddingModel(
                    delegate, policy, classifier, null, 1, () -> 0.0, sleeper, callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResilientEmbeddingModel(
                    delegate,
                    policy,
                    classifier,
                    Duration.ofSeconds(-1),
                    1,
                    () -> 0.0,
                    sleeper,
                    callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResilientEmbeddingModel(
                    delegate, policy, classifier, timeout, 0, () -> 0.0, sleeper, callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResilientEmbeddingModel(
                    delegate, policy, classifier, timeout, 1, () -> 0.0, null, callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResilientEmbeddingModel(
                    delegate, policy, classifier, timeout, 1, () -> 0.0, sleeper, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
