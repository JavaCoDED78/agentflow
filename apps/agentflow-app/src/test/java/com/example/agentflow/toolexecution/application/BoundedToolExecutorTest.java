package com.example.agentflow.toolexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.toolexecution.domain.CalculationException;
import com.example.agentflow.toolexecution.domain.JsonSchema;
import com.example.agentflow.toolexecution.domain.RetryPolicy;
import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import com.example.agentflow.toolexecution.domain.ToolExecutionContext;
import com.example.agentflow.toolexecution.domain.ToolInvocationOutcome;
import com.example.agentflow.toolexecution.domain.ToolInvocationRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link BoundedToolExecutor} bounded tool executor (S08-10).
 *
 * <p>They verify the acceptance criteria — <em>a tool never occupies a worker indefinitely</em> and
 * <em>an overload gives a controlled outcome</em> — through the behaviour that makes them hold: the
 * per-tool {@link ToolDescriptor#timeout()} frees the pool thread when the bound elapses, the
 * global / per-tenant caps reject immediately with a stable {@code concurrency_limited} reason, a
 * timeout is reported as the ambiguous {@code tool_timeout} outcome (invariant 6: interrupting the
 * pool thread does not undo a remote side effect), permits never leak, and a tool's own fault is
 * rethrown with the tool's own type. DB-free, no model/Kafka/system-clock dependency: the slow
 * calls sleep on the injected pool, the blocking calls rendezvous on latches, so every outcome is
 * deterministic.
 */
class BoundedToolExecutorTest {

  private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID RUN = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private static final Duration GENEROUS_TIMEOUT = Duration.ofSeconds(30);

  private final ExecutorService pool = Executors.newCachedThreadPool();

  @AfterEach
  void shutDownPool() {
    pool.shutdownNow();
  }

  // ---------------------------------------------------------------------------------------------
  // happy path
  // ---------------------------------------------------------------------------------------------

  @Test
  void returns_result_when_call_answers_within_timeout() {
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 4, 2);

    String result =
        executor.execute(
            context(TENANT_A),
            descriptor(GENEROUS_TIMEOUT),
            () -> {
              Thread.sleep(50);
              return "ok";
            });

    assertThat(result).isEqualTo("ok");
  }

  @Test
  void honours_the_descriptor_timeout_a_longer_bound_lets_a_slow_call_finish() {
    BoundedToolExecutor shortBound = new BoundedToolExecutor(pool, 2, 1);
    BoundedToolExecutor longBound = new BoundedToolExecutor(pool, 2, 1);
    ToolInvocation<String> slow = sleepThenReturn(Duration.ofMillis(300), "slow-ok");

    assertThatThrownBy(
            () -> shortBound.execute(context(TENANT_A), descriptor(Duration.ofMillis(50)), slow))
        .isInstanceOf(ToolExecutionThrottleException.class)
        .extracting(e -> ((ToolExecutionThrottleException) e).reason())
        .isEqualTo(ToolExecutionThrottleException.REASON_TOOL_TIMEOUT);

    assertThat(longBound.execute(context(TENANT_A), descriptor(Duration.ofSeconds(5)), slow))
        .isEqualTo("slow-ok");
  }

  // ---------------------------------------------------------------------------------------------
  // timeout behaviour — the worker is freed, the outcome is ambiguous
  // ---------------------------------------------------------------------------------------------

  @Test
  void a_timed_out_call_frees_the_worker_and_reports_the_ambiguous_outcome() throws Exception {
    // A single pool thread: if the timed-out call still occupied the worker, the second call could
    // not start — this is the "a tool never occupies a worker indefinitely" half of the acceptance.
    ExecutorService single = Executors.newSingleThreadExecutor();
    try {
      BoundedToolExecutor executor = new BoundedToolExecutor(single, 1, 1);

      ToolInvocation<String> hungCall = sleepThenReturn(Duration.ofSeconds(2), "too-late");

      assertThatThrownBy(
              () ->
                  executor.execute(context(TENANT_A), descriptor(Duration.ofMillis(50)), hungCall))
          .isInstanceOf(ToolExecutionThrottleException.class)
          .satisfies(
              e -> {
                ToolExecutionThrottleException throttle = (ToolExecutionThrottleException) e;
                assertThat(throttle.reason())
                    .isEqualTo(ToolExecutionThrottleException.REASON_TOOL_TIMEOUT);
                assertThat(throttle.retryable()).isTrue();
              });

      // The liveness probe has a hard 2-second bound: if the timed-out call still occupied the
      // single worker thread, the probe could not complete and the get() would time out.
      ExecutorService probe = Executors.newSingleThreadExecutor();
      try {
        java.util.concurrent.Future<String> probeResult =
            probe.submit(
                () ->
                    executor.execute(
                        context(TENANT_A), descriptor(GENEROUS_TIMEOUT), () -> "worker-free"));
        assertThat(probeResult.get(2, TimeUnit.SECONDS)).isEqualTo("worker-free");
      } finally {
        probe.shutdownNow();
      }
    } finally {
      single.shutdownNow();
    }
  }

  @Test
  void permits_are_released_after_a_timeout_so_capacity_is_not_lost() {
    ExecutorService single = Executors.newSingleThreadExecutor();
    try {
      BoundedToolExecutor executor = new BoundedToolExecutor(single, 1, 1);

      ToolInvocation<String> hungCall = sleepThenReturn(Duration.ofSeconds(2), "too-late");

      assertThatThrownBy(
              () ->
                  executor.execute(context(TENANT_A), descriptor(Duration.ofMillis(50)), hungCall))
          .isInstanceOf(ToolExecutionThrottleException.class);

      // The same executor admits a fresh call for the same tenant: the timeout path released both
      // the global and the per-tenant permit instead of leaking them.
      assertThat(executor.execute(context(TENANT_A), descriptor(GENEROUS_TIMEOUT), () -> "again"))
          .isEqualTo("again");
    } finally {
      single.shutdownNow();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // concurrency caps — overload is a controlled, classified outcome
  // ---------------------------------------------------------------------------------------------

  @Test
  void the_global_cap_rejects_immediately_with_a_controlled_outcome() throws Exception {
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 1, 8);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<String> firstResult = new AtomicReference<>();
    Thread firstCaller =
        runInThread(() -> firstResult.set(blockedCall(executor, TENANT_A, entered, release)));
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(
              () ->
                  executor.execute(context(TENANT_A), descriptor(GENEROUS_TIMEOUT), () -> "second"))
          .isInstanceOf(ToolExecutionThrottleException.class)
          .satisfies(
              e -> {
                ToolExecutionThrottleException throttle = (ToolExecutionThrottleException) e;
                assertThat(throttle.reason())
                    .isEqualTo(ToolExecutionThrottleException.REASON_CONCURRENCY_LIMITED);
                assertThat(throttle.retryable()).isTrue();
              });

      release.countDown();
      firstCaller.join(5_000);
      assertThat(firstResult.get()).isEqualTo("first-done");
    } finally {
      release.countDown();
      firstCaller.join(5_000);
    }
  }

  @Test
  void the_per_tenant_cap_isolates_tenants_without_blocking_others() throws Exception {
    // Global cap 2, per-tenant cap 1: tenant A holds one slot with a blocked call, so a second A
    // call is rejected while tenant B — under the same global cap — is still admitted.
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<String> firstResult = new AtomicReference<>();
    Thread firstCaller =
        runInThread(() -> firstResult.set(blockedCall(executor, TENANT_A, entered, release)));
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(
              () ->
                  executor.execute(
                      context(TENANT_A), descriptor(GENEROUS_TIMEOUT), () -> "a-second"))
          .isInstanceOf(ToolExecutionThrottleException.class)
          .extracting(e -> ((ToolExecutionThrottleException) e).reason())
          .isEqualTo(ToolExecutionThrottleException.REASON_CONCURRENCY_LIMITED);

      assertThat(executor.execute(context(TENANT_B), descriptor(GENEROUS_TIMEOUT), () -> "b-call"))
          .isEqualTo("b-call");

      release.countDown();
      firstCaller.join(5_000);
      assertThat(firstResult.get()).isEqualTo("first-done");
    } finally {
      release.countDown();
      firstCaller.join(5_000);
    }
  }

  @Test
  void a_rejected_call_consumes_no_capacity_and_later_calls_are_admitted() throws Exception {
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 1, 1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread firstCaller = runInThread(() -> blockedCall(executor, TENANT_A, entered, release));
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(
              () ->
                  executor.execute(
                      context(TENANT_A), descriptor(GENEROUS_TIMEOUT), () -> "rejected"))
          .isInstanceOf(ToolExecutionThrottleException.class);

      release.countDown();
      firstCaller.join(5_000);

      // After the in-flight call finished, the full capacity is available again.
      assertThat(
              executor.execute(context(TENANT_A), descriptor(GENEROUS_TIMEOUT), () -> "admitted"))
          .isEqualTo("admitted");
    } finally {
      release.countDown();
      firstCaller.join(5_000);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // error taxonomy — the tool's own fault keeps its own type
  // ---------------------------------------------------------------------------------------------

  @Test
  void a_tools_unchecked_fault_is_rethrown_unchanged() {
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1);
    IllegalStateException toolFault = new IllegalStateException("calculator blew up");

    assertThatThrownBy(
            () ->
                executor.execute(
                    context(TENANT_A),
                    descriptor(GENEROUS_TIMEOUT),
                    () -> {
                      throw toolFault;
                    }))
        .isSameAs(toolFault);
  }

  @Test
  void a_tools_checked_fault_is_wrapped_so_it_can_cross_the_pool_boundary() {
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1);
    java.io.IOException checked = new java.io.IOException("remote endpoint reset");

    assertThatThrownBy(
            () ->
                executor.execute(
                    context(TENANT_A),
                    descriptor(GENEROUS_TIMEOUT),
                    () -> {
                      throw checked;
                    }))
        .isInstanceOf(ToolExecutionError.class)
        .extracting(e -> ((ToolExecutionError) e).getCause())
        .isSameAs(checked);
  }

  @Test
  void an_interrupted_wait_for_the_result_restores_the_interrupt_flag() throws Exception {
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 1, 1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<Boolean> interrupted = new AtomicReference<>();
    Thread caller =
        new Thread(
            () -> {
              try {
                executor.execute(
                    context(TENANT_A),
                    descriptor(GENEROUS_TIMEOUT),
                    () -> {
                      entered.countDown();
                      hang(30_000);
                      return "unreachable";
                    });
              } catch (Throwable t) {
                failure.set(t);
                interrupted.set(Thread.currentThread().isInterrupted());
              }
            });
    caller.start();
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    caller.interrupt();
    caller.join(5_000);
    release.countDown();

    assertThat(failure.get()).isInstanceOf(ToolExecutionError.class);
    assertThat(((ToolExecutionError) failure.get()).getCause())
        .isInstanceOf(InterruptedException.class);
    assertThat(interrupted.get()).isTrue();
  }

  // ---------------------------------------------------------------------------------------------
  // argument guards
  // ---------------------------------------------------------------------------------------------

  @Test
  void null_arguments_are_rejected() {
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1);
    ToolDescriptor descriptor = descriptor(GENEROUS_TIMEOUT);
    ToolExecutionContext context = context(TENANT_A);
    ToolInvocation<String> call = () -> "ok";

    assertThatThrownBy(() -> executor.execute(null, descriptor, call))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("context");
    assertThatThrownBy(() -> executor.execute(context, null, call))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("descriptor");
    assertThatThrownBy(() -> executor.execute(context, descriptor, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("call");
  }

  @Test
  void invalid_concurrency_caps_are_rejected() {
    assertThatThrownBy(() -> new BoundedToolExecutor(pool, 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("globalMaxConcurrency");
    assertThatThrownBy(() -> new BoundedToolExecutor(pool, 1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("perTenantMaxConcurrency");
    assertThatThrownBy(() -> new BoundedToolExecutor(null, 1, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pool");
  }

  // ---------------------------------------------------------------------------------------------
  // invocation history (S08-13) — every concluded call becomes one metadata record
  // ---------------------------------------------------------------------------------------------

  @Test
  void a_successful_call_is_reported_as_succeeded_with_the_output_reference() {
    RecordingHistory history = new RecordingHistory();
    AtomicLong nanos = new AtomicLong(0);
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1, history, nanos::get);

    String result = executor.execute(context(TENANT_A), descriptor(GENEROUS_TIMEOUT), () -> "ok");

    assertThat(result).isEqualTo("ok");
    assertThat(history.records).hasSize(1);
    ToolInvocationRecord record = history.records.get(0);
    assertThat(record.tenantId()).isEqualTo(TENANT_A);
    assertThat(record.runId()).isEqualTo(RUN);
    assertThat(record.stepRunId()).isEqualTo("step-1");
    assertThat(record.toolName()).isEqualTo("test-tool");
    assertThat(record.toolVersion()).isEqualTo("1");
    assertThat(record.riskClass()).isEqualTo(ToolRiskClass.READ_ONLY);
    assertThat(record.attempt()).isEqualTo(1);
    assertThat(record.outcome()).isEqualTo(ToolInvocationOutcome.SUCCEEDED);
    assertThat(record.latencyMs()).isZero();
    assertThat(record.outputReference()).isEqualTo("step-run:step-1");
    assertThat(record.errorClass()).isNull();
    assertThat(record.errorSummary()).isNull();
  }

  @Test
  void the_latency_is_measured_on_the_injected_clock_and_the_attempt_is_reported() {
    RecordingHistory history = new RecordingHistory();
    // The admission read sees 1 ms on the injected clock; the tool advances it to 6 ms before
    // answering, so the record must carry the measured 5 ms — never wall time (invariant 10).
    AtomicLong nanos = new AtomicLong(1_000_000L);
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1, history, nanos::get);

    executor.execute(
        context(TENANT_A),
        descriptor(GENEROUS_TIMEOUT),
        () -> {
          nanos.set(6_000_000L);
          return "ok";
        },
        3);

    assertThat(history.records).hasSize(1);
    ToolInvocationRecord record = history.records.get(0);
    assertThat(record.latencyMs()).isEqualTo(5);
    assertThat(record.attempt()).isEqualTo(3);
  }

  @Test
  void a_throttled_call_is_reported_without_an_output_reference() throws Exception {
    RecordingHistory history = new RecordingHistory();
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 1, 8, history);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread firstCaller = runInThread(() -> blockedCall(executor, TENANT_A, entered, release));
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(
              () ->
                  executor.execute(
                      context(TENANT_A), descriptor(GENEROUS_TIMEOUT), () -> "rejected"))
          .isInstanceOf(ToolExecutionThrottleException.class);

      assertThat(history.records).hasSize(1);
      ToolInvocationRecord record = history.records.get(0);
      assertThat(record.outcome()).isEqualTo(ToolInvocationOutcome.THROTTLED);
      assertThat(record.errorClass())
          .isEqualTo(ToolExecutionThrottleException.REASON_CONCURRENCY_LIMITED);
      assertThat(record.outputReference()).isNull();
      // The rejected call never started: there is no redacted summary, only the stable reason.
      assertThat(record.errorSummary()).isNull();
    } finally {
      release.countDown();
      firstCaller.join(5_000);
    }
  }

  @Test
  void a_timed_out_call_is_reported_as_the_ambiguous_outcome() {
    RecordingHistory history = new RecordingHistory();
    ExecutorService single = Executors.newSingleThreadExecutor();
    try {
      BoundedToolExecutor executor = new BoundedToolExecutor(single, 1, 1, history);
      ToolInvocation<String> hungCall = sleepThenReturn(Duration.ofSeconds(2), "too-late");

      assertThatThrownBy(
              () ->
                  executor.execute(context(TENANT_A), descriptor(Duration.ofMillis(50)), hungCall))
          .isInstanceOf(ToolExecutionThrottleException.class)
          .extracting(e -> ((ToolExecutionThrottleException) e).reason())
          .isEqualTo(ToolExecutionThrottleException.REASON_TOOL_TIMEOUT);

      assertThat(history.records).hasSize(1);
      ToolInvocationRecord record = history.records.get(0);
      assertThat(record.outcome()).isEqualTo(ToolInvocationOutcome.TIMED_OUT);
      assertThat(record.errorClass()).isEqualTo(ToolExecutionThrottleException.REASON_TOOL_TIMEOUT);
      assertThat(record.outputReference()).isNull();
    } finally {
      single.shutdownNow();
    }
  }

  @Test
  void a_tool_fault_is_reported_with_a_redacted_stable_token_never_the_message() {
    RecordingHistory history = new RecordingHistory();
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1, history);
    CalculationException toolFault = new CalculationException("division-by-zero");

    assertThatThrownBy(
            () ->
                executor.execute(
                    context(TENANT_A),
                    descriptor(GENEROUS_TIMEOUT),
                    () -> {
                      throw toolFault;
                    }))
        .isSameAs(toolFault);

    assertThat(history.records).hasSize(1);
    ToolInvocationRecord record = history.records.get(0);
    assertThat(record.outcome()).isEqualTo(ToolInvocationOutcome.FAULTED);
    // The stable reason code classifies without parsing a message; the message — which can quote
    // untrusted input or a secret — is never stored (invariant 8).
    assertThat(record.errorClass()).isEqualTo("division-by-zero");
    assertThat(record.errorSummary()).isEqualTo("CalculationException(division-by-zero)");
    assertThat(record.errorSummary()).doesNotContain("calculation failed");
  }

  @Test
  void a_history_listener_failure_does_not_change_the_calls_outcome() {
    ToolInvocationListener failing =
        record -> {
          throw new IllegalStateException("history store down");
        };
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1, failing);

    // Success: the result stands even though the report threw.
    assertThat(executor.execute(context(TENANT_A), descriptor(GENEROUS_TIMEOUT), () -> "ok"))
        .isEqualTo("ok");

    // Fault: the tool's own fault still propagates unchanged; the history fault was logged only.
    CalculationException toolFault = new CalculationException("overflow");
    assertThatThrownBy(
            () ->
                executor.execute(
                    context(TENANT_A),
                    descriptor(GENEROUS_TIMEOUT),
                    () -> {
                      throw toolFault;
                    }))
        .isSameAs(toolFault);
  }

  @Test
  void an_attempt_below_one_is_rejected_before_the_call_runs() {
    RecordingHistory history = new RecordingHistory();
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1, history);

    assertThatThrownBy(
            () -> executor.execute(context(TENANT_A), descriptor(GENEROUS_TIMEOUT), () -> "ok", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("attempt");

    // The call never ran and nothing was reported: the guard fires before the bounded section.
    assertThat(history.records).isEmpty();
  }

  @Test
  void the_three_argument_execute_reports_attempt_one() {
    RecordingHistory history = new RecordingHistory();
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1, history);

    executor.execute(context(TENANT_A), descriptor(GENEROUS_TIMEOUT), () -> "ok");

    assertThat(history.records.get(0).attempt()).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  /** In-memory listener capturing every reported record, for the S08-13 history assertions. */
  private static final class RecordingHistory implements ToolInvocationListener {

    private final List<ToolInvocationRecord> records = new ArrayList<>();

    @Override
    public void record(ToolInvocationRecord record) {
      records.add(record);
    }
  }

  private String blockedCall(
      BoundedToolExecutor executor, UUID tenant, CountDownLatch entered, CountDownLatch release) {
    try {
      return executor.execute(
          context(tenant),
          descriptor(GENEROUS_TIMEOUT),
          () -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return "first-done";
          });
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Thread runInThread(ThrowingRunnable body) {
    Thread thread =
        new Thread(
            () -> {
              try {
                body.run();
              } catch (RuntimeException e) {
                throw e;
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    thread.start();
    return thread;
  }

  private static ToolInvocation<String> sleepThenReturn(Duration sleep, String value) {
    return () -> {
      hang(sleep.toMillis());
      return value;
    };
  }

  /** Sleep that stops early on interrupt, mirroring any interruptible blocking a real tool does. */
  private static void hang(long millis) throws InterruptedException {
    Thread.sleep(millis);
  }

  private static ToolDescriptor descriptor(Duration timeout) {
    return ToolDescriptor.of(
        "tool-test-1",
        "test-tool",
        "1",
        "A tool used only by the bounded-executor tests.",
        JsonSchema.of(schema("object")),
        JsonSchema.of(schema("object")),
        Set.of(),
        ToolRiskClass.READ_ONLY,
        timeout,
        RetryPolicy.ofDefaults(),
        false);
  }

  private static ToolExecutionContext context(UUID tenant) {
    return ToolExecutionContext.of(tenant, "alice", RUN, "step-1", "corr-1", Set.of());
  }

  private static Map<String, Object> schema(String type) {
    return Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "type", type);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
