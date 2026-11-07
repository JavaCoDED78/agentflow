package com.example.agentflow.toolexecution.application;

import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import com.example.agentflow.toolexecution.domain.ToolExecutionContext;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

/**
 * The bounded tool executor (S08-10): every tool call runs under a per-tool {@link
 * java.time.Duration} timeout and a global / per-tenant concurrency cap, so a tool can never occupy
 * a worker indefinitely and an overload degrades into a controlled, classified outcome instead of
 * an unbounded queue.
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
 * (the {@code TOOL_CALL} {@code StepHandler}, wired in S08-12) records an <em>ambiguous</em>
 * attempt and retries with bounded backoff rather than declaring the call failed or succeeded. The
 * two halves are deliberate: the worker is freed (cancel), and the outcome is not lied about
 * (ambiguous).
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
 * com.example.agentflow.toolexecution.domain.CalculationException}), and a checked fault is
 * rewrapped in {@link ToolExecutionError} so it can cross the pool boundary.
 *
 * <p>The class is a plain value-bound service: it reads no time, no randomness and no I/O beyond
 * the submitted call, and its pool is supplied by the caller (the Spring wiring builds it from
 * {@code agentflow.tools.executor.*}), so it is fully testable with an injected pool (invariant
 * 10). The per-tenant permit map is in-memory coordination only — a tenant id is never published as
 * a metric label (invariant 9).
 */
public final class BoundedToolExecutor {

  private final ExecutorService pool;

  private final Semaphore globalPermits;

  private final int perTenantMaxConcurrency;

  private final ConcurrentMap<UUID, Semaphore> tenantPermits = new ConcurrentHashMap<>();

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
    this.pool = pool;
    this.globalPermits = new Semaphore(globalMaxConcurrency);
    this.perTenantMaxConcurrency = perTenantMaxConcurrency;
  }

  /**
   * Run a single tool call under the per-tool timeout and the concurrency caps.
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
    if (context == null) {
      throw new IllegalArgumentException("context must not be null");
    }
    if (descriptor == null) {
      throw new IllegalArgumentException("descriptor must not be null");
    }
    if (call == null) {
      throw new IllegalArgumentException("call must not be null");
    }
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
}
