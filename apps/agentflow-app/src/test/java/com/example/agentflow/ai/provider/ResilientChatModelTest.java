package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.ChatMessage;
import com.example.agentflow.ai.application.ChatModelPort;
import com.example.agentflow.ai.application.ChatRequest;
import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.MessageRole;
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
 * Unit tests for the resilient provider port (S09-09).
 *
 * <p>These are the acceptance tests for the task: they prove the wrapper makes a <b>finite</b>
 * number of attempts (never an infinite model-call loop), that the total inter-attempt wait stays
 * <b>within a bounded upper bound</b> ({@link ProviderResiliencePolicy#maxTotalBackoffMillis()}),
 * that a single attempt is aborted by the overall timeout, and that the concurrency cap rejects
 * overload. The time (jitter), the sleep between attempts and the call executor are all injected
 * through the constructor, so every outcome is deterministic and DB-free — no network, model,
 * database, Kafka or system clock (invariant 10). The delegate is a local {@link FakeChatPort}
 * double, exactly like {@code OpenAiChatModelAdapterTest}'s {@code FakeChatModel}.
 */
class ResilientChatModelTest {

  private static final String MODEL = "gpt-4o-mini";

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

  private final ExecutorService callExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "test-resilient-call");
            thread.setDaemon(true);
            return thread;
          });

  @AfterEach
  void shutDownExecutor() {
    callExecutor.shutdownNow();
  }

  private static ChatRequest request() {
    return new ChatRequest(MODEL, List.of(new ChatMessage(MessageRole.USER, "hi")));
  }

  private static ChatResponse response(String content) {
    return new ChatResponse(MODEL, content, "stop", null);
  }

  private static final class FakeChatPort implements ChatModelPort {
    volatile int calls = 0;
    volatile boolean blocks = false;
    volatile long blockMillis = 0;
    CountDownLatch entered;
    CountDownLatch release;

    final ChatResponse ok;
    final List<RuntimeException> perCallFailures;

    FakeChatPort(ChatResponse ok) {
      this.ok = ok;
      this.perCallFailures = new ArrayList<>();
    }

    FakeChatPort failWith(RuntimeException failure) {
      perCallFailures.add(failure);
      return this;
    }

    FakeChatPort blockOn(long blockMillis) {
      this.blocks = true;
      this.blockMillis = blockMillis;
      return this;
    }

    FakeChatPort signal(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
      return this;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
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
        return ok;
      }
      if (index < perCallFailures.size()) {
        throw perCallFailures.get(index);
      }
      return ok;
    }

    private void awaitRelease() {
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void sleepQuietly(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Classifies the fake faults: transient -> retryable, everything else -> permanent. */
  private static Function<Throwable, ProviderFailureReason> classifyTransientAsRateLimit() {
    return fault ->
        fault instanceof TransientFault
            ? ProviderFailureReason.RATE_LIMIT
            : ProviderFailureReason.CLIENT_ERROR;
  }

  private static ProviderResiliencePolicy smallBoundedPolicy() {
    // 3 attempts, 100 ms base, 2x growth, 1000 ms cap, no jitter -> deterministic backoffs 100,
    // 200.
    return ProviderResiliencePolicy.of(
        3, Duration.ofMillis(100), 2.0, Duration.ofMillis(1000), false);
  }

  private ResilientChatModel resilient(FakeChatPort delegate, ProviderResiliencePolicy policy) {
    // No jitter -> the policy returns the full capped delay; a no-op sleeper keeps retries instant.
    return new ResilientChatModel(
        delegate,
        policy,
        classifyTransientAsRateLimit(),
        Duration.ofSeconds(30),
        4,
        null,
        noopSleeper(),
        callExecutor);
  }

  private static Consumer<Long> noopSleeper() {
    return delayMillis -> {
      // no-op: keep the retry-loop tests instant and non-timing-dependent.
    };
  }

  @Test
  void a_clean_first_attempt_returns_without_retrying() {
    FakeChatPort delegate = new FakeChatPort(response("ok"));
    ChatResponse answer = resilient(delegate, smallBoundedPolicy()).chat(request());

    assertThat(answer.content()).isEqualTo("ok");
    assertThat(delegate.calls).isEqualTo(1);
  }

  @Test
  void a_transient_fault_is_retried_until_it_recovers() {
    FakeChatPort delegate =
        new FakeChatPort(response("recovered"))
            .failWith(new TransientFault())
            .failWith(new TransientFault());
    ChatResponse answer = resilient(delegate, smallBoundedPolicy()).chat(request());

    assertThat(answer.content()).isEqualTo("recovered");
    // Attempt 1 + 2 failed, attempt 3 succeeded: exactly three attempts, then a clean return.
    assertThat(delegate.calls).isEqualTo(3);
  }

  @Test
  void a_call_fails_after_the_bounded_number_of_attempts_never_infinite() {
    FakeChatPort delegate =
        new FakeChatPort(response("x"))
            .failWith(new TransientFault())
            .failWith(new TransientFault())
            .failWith(new TransientFault());
    ProviderResiliencePolicy policy = smallBoundedPolicy(); // maxAttempts == 3

    assertThatThrownBy(() -> resilient(delegate, policy).chat(request()))
        .isInstanceOf(TransientFault.class);

    // Every one of the 3 attempts fails, then the terminal attempt throws instead of retrying.
    assertThat(delegate.calls).isEqualTo(3);
    assertThat(policy.maxTotalBackoffMillis()).isEqualTo(2000L); // (3 - 1) * 1000 ms cap
  }

  @Test
  void a_permanent_fault_is_not_retried_and_fails_immediately() {
    FakeChatPort delegate = new FakeChatPort(response("x")).failWith(new PermanentFault());

    assertThatThrownBy(() -> resilient(delegate, smallBoundedPolicy()).chat(request()))
        .isInstanceOf(PermanentFault.class);

    assertThat(delegate.calls).isEqualTo(1);
  }

  @Test
  void the_total_inter_attempt_wait_stays_within_the_bounded_upper_bound() {
    List<Long> recordedDelays = new ArrayList<>();
    ProviderResiliencePolicy policy = smallBoundedPolicy();
    FakeChatPort delegate =
        new FakeChatPort(response("x"))
            .failWith(new TransientFault())
            .failWith(new TransientFault())
            .failWith(new TransientFault());

    ResilientChatModel port =
        new ResilientChatModel(
            delegate,
            policy,
            classifyTransientAsRateLimit(),
            Duration.ofSeconds(30),
            4,
            null,
            recordedDelays::add,
            callExecutor);

    assertThatThrownBy(() -> port.chat(request())).isInstanceOf(TransientFault.class);

    // Two retries after a 3-attempt policy: the recorded waits are the deterministic 100 + 200 ms,
    // and their sum is provably below the policy's maxTotalBackoffMillis() upper bound.
    assertThat(recordedDelays).containsExactly(100L, 200L);
    assertThat((long) recordedDelays.stream().mapToLong(Long::longValue).sum())
        .isLessThanOrEqualTo(policy.maxTotalBackoffMillis());
  }

  @Test
  void a_hanging_attempt_is_bounded_by_the_overall_timeout_across_finite_retries()
      throws Exception {
    // The fake blocks forever, so every attempt exceeds the 200 ms overall timeout and is aborted;
    // TIMEOUT is a retryable reason, so the loop retries up to maxAttempts, all aborted in time.
    FakeChatPort delegate = new FakeChatPort(response("x")).blockOn(TimeUnit.SECONDS.toMillis(5));
    int maxAttempts = smallBoundedPolicy().maxAttempts();
    ResilientChatModel port =
        new ResilientChatModel(
            delegate,
            smallBoundedPolicy(),
            classifierThatMapsTimeouts(),
            Duration.ofMillis(200),
            1,
            null,
            noopSleeper(),
            callExecutor);

    long start = System.nanoTime();
    assertThatThrownBy(() -> port.chat(request()))
        .isInstanceOf(ResilientChatModel.ProviderTimeoutException.class);
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    // The overall timeout aborts each attempt near its 200 ms bound (the fake would block 5 s
    // each),
    // and the loop stops after maxAttempts: a finite number of attempts, each within a time bound.
    assertThat(elapsedMillis).isLessThan(2_000L);
    assertThat(delegate.calls).isEqualTo(maxAttempts);
  }

  @Test
  void the_concurrency_cap_throttles_beyond_max_parallel_calls() throws Exception {
    // A single-permit cap: one in-flight call holds the only permit, the second is rejected at
    // once.
    ProviderResiliencePolicy singleAttempt =
        ProviderResiliencePolicy.of(1, Duration.ofMillis(10), 1.0, Duration.ofMillis(50), false);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    FakeChatPort delegate =
        new FakeChatPort(response("x"))
            .signal(entered, release)
            .blockOn(0); // blocks on the release latch, never on a timer
    ResilientChatModel port =
        new ResilientChatModel(
            delegate,
            singleAttempt,
            classifyTransientAsRateLimit(),
            Duration.ofSeconds(30),
            1,
            null,
            noopSleeper(),
            callExecutor);

    Thread first = new Thread(() -> port.chat(request()), "resilient-first");
    first.start();
    // Wait until the first attempt has taken the permit and is blocked inside the delegate.
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

    // The permit is held, so the second call is throttled immediately (no permit to take).
    assertThatThrownBy(() -> port.chat(request()))
        .isInstanceOf(ResilientChatModel.ProviderConcurrencyLimitedException.class);

    // Release the first call and confirm it then completes.
    release.countDown();
    first.join(2_000);
    assertThat(first.isAlive()).isFalse();
    assertThat(delegate.calls).isEqualTo(1);
  }

  @Test
  void the_constructor_validates_its_arguments() {
    assertThatThrownBy(
            () ->
                new ResilientChatModel(
                    null,
                    smallBoundedPolicy(),
                    classifyTransientAsRateLimit(),
                    Duration.ofSeconds(1),
                    1,
                    null,
                    noopSleeper(),
                    callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResilientChatModel(
                    new FakeChatPort(response("x")),
                    null,
                    classifyTransientAsRateLimit(),
                    Duration.ofSeconds(1),
                    1,
                    null,
                    noopSleeper(),
                    callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResilientChatModel(
                    new FakeChatPort(response("x")),
                    smallBoundedPolicy(),
                    null,
                    Duration.ofSeconds(1),
                    1,
                    null,
                    noopSleeper(),
                    callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResilientChatModel(
                    new FakeChatPort(response("x")),
                    smallBoundedPolicy(),
                    classifyTransientAsRateLimit(),
                    null,
                    1,
                    null,
                    noopSleeper(),
                    callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ResilientChatModel(
                    new FakeChatPort(response("x")),
                    smallBoundedPolicy(),
                    classifyTransientAsRateLimit(),
                    Duration.ofSeconds(1),
                    0,
                    null,
                    noopSleeper(),
                    callExecutor))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Function<Throwable, ProviderFailureReason> classifierThatMapsTimeouts() {
    return fault ->
        fault instanceof ResilientChatModel.ProviderTimeoutException
            ? ProviderFailureReason.TIMEOUT
            : ProviderFailureReason.CLIENT_ERROR;
  }
}
