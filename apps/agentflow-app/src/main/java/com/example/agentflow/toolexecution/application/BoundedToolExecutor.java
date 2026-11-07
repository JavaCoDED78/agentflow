package com.example.agentflow.toolexecution.application;

import com.example.agentflow.toolexecution.domain.CalculationException;
import com.example.agentflow.toolexecution.domain.SchemaValidationException;
import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import com.example.agentflow.toolexecution.domain.ToolExecutionContext;
import com.example.agentflow.toolexecution.domain.ToolInvocationOutcome;
import com.example.agentflow.toolexecution.domain.ToolInvocationRecord;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bounded tool executor (S08-10): every tool call runs under a per-tool {@link
 * java.time.Duration} timeout and a global / per-tenant concurrency cap, so a tool can never occupy
 * a worker indefinitely and an overload degrades into a controlled, classified outcome instead of
 * an unbounded queue. Since S08-13 it also reports every concluded call — success, throttle
 * rejection, ambiguous timeout or tool fault — to the {@link ToolInvocationListener}, so the
 * invocation history records <em>which tool executed and how its call concluded</em> without ever
 * carrying the arguments or the payload.
 *
 * <p><b>The two bounds.</b>
 *
 * <ul>
 *   <li><b>Per-tool timeout</b> — read from the call's {@link ToolDescriptor#timeout()} (the bound
 *       S08-01 made part of every descriptor), enforced by {@link ToolCallTimeout}: the call runs
 *       on the executor's pool and, when it does not answer within the bound, the future is
 *       cancelled so the pool thread is freed. A call that answers in time returns its value.
 *   <li><b>Concurrency cap</b> — a global {@link Semaphore} bounds the calls the whole process runs
 *       at once, and a per-tenant {@link Semaphore} (keyed by the verified {@link
 *       ToolExecutionContext#tenantId()}) bounds what one tenant may occupy, so a single noisy
 *       tenant cannot starve the rest (invariant 1 is about isolation; the cap is its
 *       resource-level counterpart). Admission is {@code tryAcquire}, never blocking: when a cap is
 *       reached the call is <em>rejected immediately</em> with {@link
 *       ToolExecutionThrottleException#REASON_CONCURRENCY_LIMITED} — an overload outcome the caller
 *       classifies and retries with backoff, not a growing wait queue.
 * </ul>
 *
 * <p><b>Why a timeout outcome is ambiguous, never "cleanly failed".</b> Interrupting the pool
 * thread ({@code future.cancel(true)}) frees the worker, but it does <em>not</em> undo a remote
 * side effect the call may already have triggered — an external call is not exactly-once (invariant
 * 6 / {@code ADR-003}). The executor therefore reports a timed-out call as {@link
 * ToolExecutionThrottleException#REASON_TOOL_TIMEOUT} with {@code retryable() == true}: the caller
 * (the {@code TOOL_CALL} {@code StepHandler}) records an <em>ambiguous</em> attempt and retries
 * with bounded backoff rather than declaring the call failed or succeeded. The two halves are
 * deliberate: the worker is freed (cancel), and the outcome is not lied about (ambiguous).
 *
 * <p><b>The invocation history (S08-13).</b> Every call — including a rejection that never started
 * — becomes exactly one {@link ToolInvocationRecord}: the tool's {@code (name, version)} identity,
 * the caller-supplied 1-based attempt number, the wall time the call occupied (measured on an
 * injectable monotonic time source, invariant 10), the bounded {@link ToolInvocationOutcome}, a
 * {@code step-run:<uuid>} reference to the checkpoint holding the output on success (never the
 * payload itself), and a safe failure token — a throttle reason, or the faulting type plus its
 * stable reason code, bounded and redacted: an exception <em>message</em> can quote a secret, so it
 * is never stored (invariant 8). The listener runs after the outcome is decided and its failure
 * never changes that outcome: history is observability, not business state.
 *
 * <p><b>Capacity accounting.</b> A rejected call never acquires anything; a call that is admitted
 * releases both permits in a {@code finally}, including on timeout and on the tool's own fault — so
 * permits cannot leak and a burst of slow calls cannot permanently shrink the executor. Note the
 * honest consequence of freeing the worker on timeout: the orphaned pool task may still be running
 * while its permits are already released; that is the accepted trade-off of bounding the worker
 * (the alternative — holding the permit until the task actually ends — lets a hung remote call
 * occupy a slot forever, which is the failure mode this task exists to remove).
 *
 * <p><b>Error taxonomy.</b> The executor introduces exactly the two bounded-outcome faults ({@link
 * ToolExecutionThrottleException} with {@code concurrency_limited} / {@code tool_timeout}); a
 * tool's own fault is <em>not</em> one of them — an unchecked fault is rethrown unchanged (the
 * {@code TOOL_CALL} {@code StepHandler} classifies it with the tool's own type, e.g. a {@link
 * CalculationException}), and a checked fault is rewrapped in {@link ToolExecutionError} so it can
 * cross the pool boundary.
 *
 * <p>The class is a plain value-bound service: it reads no randomness and no I/O beyond the
 * submitted call, its clock is an injected {@link LongSupplier} (monotonic nanotime), and its pool
 * is supplied by the caller (the Spring wiring builds it from {@code agentflow.tools.executor.*}),
 * so it is fully testable with an injected pool and time source (invariant 10). The per-tenant
 * permit map is in-memory coordination only — a tenant id is never published as a metric label
 * (invariant 9).
 */
public final class BoundedToolExecutor {

  private static final Logger log = LoggerFactory.getLogger(BoundedToolExecutor.class);

  /** The bound the history keeps a redacted failure summary to; never a secret-bearing message. */
  private static final int ERROR_SUMMARY_MAX = 200;

  private final ExecutorService pool;

  private final Semaphore globalPermits;

  private final int perTenantMaxConcurrency;

  private final ConcurrentMap<UUID, Semaphore> tenantPermits = new ConcurrentHashMap<>();

  private final ToolInvocationListener listener;

  private final LongSupplier nanoTime;

  /**
   * @param pool the thread pool the calls run on; never {@code null}. Its size is a configuration
   *     concern (the wiring sizes it from {@code agentflow.tools.executor.pool-size}); the executor
   *     never sizes it from the caps.
   * @param globalMaxConcurrency the maximum number of tool calls this process runs at once; at
   *     least {@code 1} — {@code 0} or negative would reject every call, which is a configuration
   *     error, not an outcome
   * @param perTenantMaxConcurrency the maximum number of tool calls a single tenant may occupy at
   *     once; at least {@code 1} for the same reason
   */
  public BoundedToolExecutor(
      ExecutorService pool, int globalMaxConcurrency, int perTenantMaxConcurrency) {
    this(
        pool,
        globalMaxConcurrency,
        perTenantMaxConcurrency,
        ToolInvocationListener.NONE,
        System::nanoTime);
  }

  /**
   * @param pool the thread pool the calls run on; never {@code null}
   * @param globalMaxConcurrency the maximum number of tool calls this process runs at once; at
   *     least {@code 1}
   * @param perTenantMaxConcurrency the maximum number of tool calls a single tenant may occupy at
   *     once; at least {@code 1}
   * @param listener the invocation-history seam (S08-13) every concluded call is reported to; never
   *     {@code null} — pass {@link ToolInvocationListener#NONE} when no history is wired
   */
  public BoundedToolExecutor(
      ExecutorService pool,
      int globalMaxConcurrency,
      int perTenantMaxConcurrency,
      ToolInvocationListener listener) {
    this(pool, globalMaxConcurrency, perTenantMaxConcurrency, listener, System::nanoTime);
  }

  /**
   * @param pool the thread pool the calls run on; never {@code null}
   * @param globalMaxConcurrency the maximum number of tool calls this process runs at once; at
   *     least {@code 1}
   * @param perTenantMaxConcurrency the maximum number of tool calls a single tenant may occupy at
   *     once; at least {@code 1}
   * @param listener the invocation-history seam (S08-13) every concluded call is reported to; never
   *     {@code null} — pass {@link ToolInvocationListener#NONE} when no history is wired
   * @param nanoTime the monotonic time source the latency measurement reads; never {@code null}
   *     (inject a fixed source in tests, invariant 10)
   */
  public BoundedToolExecutor(
      ExecutorService pool,
      int globalMaxConcurrency,
      int perTenantMaxConcurrency,
      ToolInvocationListener listener,
      LongSupplier nanoTime) {
    if (pool == null) {
      throw new IllegalArgumentException("pool must not be null");
    }
    if (globalMaxConcurrency < 1) {
      throw new IllegalArgumentException(
          "globalMaxConcurrency must be at least 1 but was " + globalMaxConcurrency);
    }
    if (perTenantMaxConcurrency < 1) {
      throw new IllegalArgumentException(
          "perTenantMaxConcurrency must be at least 1 but was " + perTenantMaxConcurrency);
    }
    if (listener == null) {
      throw new IllegalArgumentException("listener must not be null");
    }
    if (nanoTime == null) {
      throw new IllegalArgumentException("nanoTime must not be null");
    }
    this.pool = pool;
    this.globalPermits = new Semaphore(globalMaxConcurrency);
    this.perTenantMaxConcurrency = perTenantMaxConcurrency;
    this.listener = listener;
    this.nanoTime = nanoTime;
  }

  /**
   * Run a single tool call under the per-tool timeout and the concurrency caps, reporting it to the
   * invocation history as attempt {@code 1}.
   *
   * @param context the verified, tenant-scoped call context (S08-08); its {@link
   *     ToolExecutionContext#tenantId()} selects the per-tenant permit; never {@code null}
   * @param descriptor the resolved tool descriptor; its {@link ToolDescriptor#timeout()} is the
   *     bound for this call; never {@code null}
   * @param call the invocation to run (S08-12 wires the permission re-check and the adapter into
   *     it); never {@code null}
   * @param <T> the tool result type
   * @return the tool's result, when the call answers within {@link ToolDescriptor#timeout()}
   * @throws ToolExecutionThrottleException with {@code concurrency_limited} when a cap is reached
   *     before the call can start (the call was rejected, not started — a later attempt with spare
   *     capacity is safe), or with {@code tool_timeout} when the call did not answer within the
   *     bound (the outcome is ambiguous: the side effect may have completed; the pool thread is
   *     freed either way)
   * @throws ToolExecutionError when the tool raised a <em>checked</em> fault (the original is the
   *     {@link ToolExecutionError#getCause()})
   * @throws RuntimeException the tool's own unchecked fault, rethrown unchanged
   */
  public <T> T execute(
      ToolExecutionContext context, ToolDescriptor descriptor, ToolInvocation<T> call) {
    return execute(context, descriptor, call, 1);
  }

  /**
   * Run a single tool call and report it to the invocation history as the given attempt.
   *
   * @param context the verified, tenant-scoped call context; never {@code null}
   * @param descriptor the resolved tool descriptor; never {@code null}
   * @param call the invocation to run; never {@code null}
   * @param attempt the per-call attempt number (1-based) the caller's retry loop is on; at least
   *     {@code 1} — the history keys retries by it
   * @param <T> the tool result type
   * @return the tool's result, when the call answers within {@link ToolDescriptor#timeout()}
   * @throws ToolExecutionThrottleException with {@code concurrency_limited} when a cap is reached
   *     before the call can start, or with {@code tool_timeout} when the call did not answer within
   *     the bound (the outcome is ambiguous — invariant 6)
   * @throws ToolExecutionError when the tool raised a <em>checked</em> fault
   * @throws RuntimeException the tool's own unchecked fault, rethrown unchanged
   */
  public <T> T execute(
      ToolExecutionContext context,
      ToolDescriptor descriptor,
      ToolInvocation<T> call,
      int attempt) {
    if (context == null) {
      throw new IllegalArgumentException("context must not be null");
    }
    if (descriptor == null) {
      throw new IllegalArgumentException("descriptor must not be null");
    }
    if (call == null) {
      throw new IllegalArgumentException("call must not be null");
    }
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be at least 1 but was " + attempt);
    }
    long startNanos = nanoTime.getAsLong();
    try {
      T result = executeBounded(context, descriptor, call);
      notifyHistory(
          context,
          descriptor,
          attempt,
          ToolInvocationOutcome.SUCCEEDED,
          elapsedMs(startNanos),
          outputReferenceOf(context),
          null,
          null);
      return result;
    } catch (ToolExecutionThrottleException throttle) {
      // concurrency_limited: rejected before the call started; tool_timeout: the ambiguous
      // outcome (invariant 6). Both carry the stable reason as the safe failure token.
      ToolInvocationOutcome outcome =
          ToolExecutionThrottleException.REASON_TOOL_TIMEOUT.equals(throttle.reason())
              ? ToolInvocationOutcome.TIMED_OUT
              : ToolInvocationOutcome.THROTTLED;
      notifyHistory(
          context,
          descriptor,
          attempt,
          outcome,
          elapsedMs(startNanos),
          null,
          throttle.reason(),
          null);
      throw throttle;
    } catch (RuntimeException fault) {
      // A ToolExecutionError wraps a checked tool fault; any other RuntimeException is the tool's
      // own unchecked fault — both are reported as FAULTED with a redacted, stable token.
      Throwable cause = fault instanceof ToolExecutionError error ? error.getCause() : fault;
      notifyHistory(
          context,
          descriptor,
          attempt,
          ToolInvocationOutcome.FAULTED,
          elapsedMs(startNanos),
          null,
          stableTokenOf(cause),
          redactedSummaryOf(cause));
      throw fault;
    }
  }

  /** The bounded call itself: admission, the timeout bound, the pool-boundary fault unwrapping. */
  private <T> T executeBounded(
      ToolExecutionContext context, ToolDescriptor descriptor, ToolInvocation<T> call) {
    Semaphore tenant =
        tenantPermits.computeIfAbsent(
            context.tenantId(), key -> new Semaphore(perTenantMaxConcurrency));
    if (!globalPermits.tryAcquire()) {
      throw new ToolExecutionThrottleException(
          ToolExecutionThrottleException.REASON_CONCURRENCY_LIMITED, true);
    }
    boolean tenantAcquired = false;
    try {
      if (!tenant.tryAcquire()) {
        throw new ToolExecutionThrottleException(
            ToolExecutionThrottleException.REASON_CONCURRENCY_LIMITED, true);
      }
      tenantAcquired = true;
      try {
        return ToolCallTimeout.enforce(pool, descriptor.timeout(), call);
      } catch (TimeoutException e) {
        // The bound elapsed: the pool thread is freed (ToolCallTimeout cancelled the future), but
        // interrupting the thread does not undo a remote side effect — report the ambiguous
        // outcome, never a decided failure or a silent success (invariant 6 / ADR-003).
        throw new ToolExecutionThrottleException(
            ToolExecutionThrottleException.REASON_TOOL_TIMEOUT, true);
      } catch (ExecutionException e) {
        // The tool raised its own fault on the pool thread: unwrap it so the caller classifies
        // with the tool's own type; only a checked fault needs the pool-boundary rewrap.
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof RuntimeException runtime) {
          throw runtime;
        }
        if (cause instanceof Error error) {
          throw error;
        }
        throw new ToolExecutionError(cause);
      } catch (InterruptedException e) {
        // The wait for the result was interrupted (e.g. a shutdown drain): restore the flag so
        // upstream code sees it, and report the call as not completed.
        Thread.currentThread().interrupt();
        throw new ToolExecutionError(e);
      }
    } finally {
      if (tenantAcquired) {
        tenant.release();
      }
      globalPermits.release();
    }
  }

  /**
   * Report the concluded call to the history. A listener fault is logged and swallowed on purpose:
   * the call's outcome is already decided — history is observability, not business state, and a
   * metadata write must never change what the caller observes.
   */
  private void notifyHistory(
      ToolExecutionContext context,
      ToolDescriptor descriptor,
      int attempt,
      ToolInvocationOutcome outcome,
      long latencyMs,
      String outputReference,
      String errorClass,
      String errorSummary) {
    try {
      listener.record(
          ToolInvocationRecord.recorded(
              context.tenantId(),
              context.runId(),
              context.stepRunId(),
              descriptor.name(),
              descriptor.version(),
              descriptor.riskClass(),
              attempt,
              outcome,
              latencyMs,
              outputReference,
              errorClass,
              errorSummary));
    } catch (RuntimeException historyFailure) {
      log.warn("tool invocation history write failed; the call outcome stands", historyFailure);
    }
  }

  private long elapsedMs(long startNanos) {
    return Math.max(0, (nanoTime.getAsLong() - startNanos) / 1_000_000);
  }

  /**
   * @return the {@code step-run:<uuid>} pointer to the checkpoint holding the output, or {@code
   *     null} when the context carries no step run
   */
  private static String outputReferenceOf(ToolExecutionContext context) {
    return context.stepRunId() == null ? null : "step-run:" + context.stepRunId();
  }

  /**
   * The safe failure token: a stable reason code when the fault carries one (the calculator and
   * schema validators, a nested throttle), otherwise the faulting type's simple name. Never raw
   * error text — a message can quote a secret (invariant 8).
   */
  private static String stableTokenOf(Throwable cause) {
    String stable = stableReasonOf(cause);
    return stable != null ? stable : cause.getClass().getSimpleName();
  }

  private static String stableReasonOf(Throwable cause) {
    if (cause instanceof CalculationException calculation) {
      return calculation.reason();
    }
    if (cause instanceof SchemaValidationException validation) {
      return validation.reason();
    }
    if (cause instanceof ToolExecutionThrottleException throttle) {
      return throttle.reason();
    }
    return null;
  }

  /**
   * The redacted summary: the faulting type plus its stable reason code, bounded — never the
   * exception message.
   */
  private static String redactedSummaryOf(Throwable cause) {
    String stable = stableReasonOf(cause);
    String summary = cause.getClass().getSimpleName() + (stable == null ? "" : "(" + stable + ")");
    return summary.length() <= ERROR_SUMMARY_MAX
        ? summary
        : summary.substring(0, ERROR_SUMMARY_MAX);
  }
}
