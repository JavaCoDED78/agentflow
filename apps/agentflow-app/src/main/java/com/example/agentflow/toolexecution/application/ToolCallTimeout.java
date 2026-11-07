package com.example.agentflow.toolexecution.application;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Enforces the per-tool {@link java.time.Duration} timeout on a single {@link ToolInvocation}
 * (S08-10).
 *
 * <p>The call runs on a caller-supplied {@link ExecutorService}; the executor waits at most the
 * tool's {@link ToolDescriptor#timeout()} for it and throws {@link TimeoutException} when it does
 * not answer in time — so a slow tool can never occupy a worker thread indefinitely (the S08-10
 * acceptance: the tool is bounded, the worker is freed). The timeout is read from the descriptor,
 * so every tool carries its own bound in its {@code ToolDescriptor} (S08-01); the executor never
 * invents one.
 *
 * <p><b>Why a {@link TimeoutException}, and why the outcome is ambiguous.</b> On the bound the
 * executor <em>cancels</em> the future to release the pool thread ({@code future.cancel(true)}),
 * but it deliberately does <b>not</b> treat that as "the call stopped and nothing happened": a
 * remote side effect is not undone by interrupting the pool thread, so the call's side effect may
 * still have completed. The {@link TimeoutException} is therefore the honest, classifier-native
 * signal — the workflow retry classifier (S07-11) maps a {@link TimeoutException} to {@code
 * provider_timeout}: retryable and recorded as <em>ambiguous</em> in the attempt history (invariant
 * 6 / {@code ADR-003}: the attempt is retried with bounded backoff, never assumed to have failed,
 * and never treated as exactly-once). Freeing the thread (cancel) and not assuming the side effect
 * is cancelled (ambiguous outcome) are the two halves of the same safe behaviour.
 *
 * <p>A call that finishes inside the bound returns its value; a call that raises its own fault is
 * unwrapped and rethrown by the {@link BoundedToolExecutor}. This class is stateless and pure over
 * its inputs — it reads no time, no randomness, no Spring or I/O — so it is fully testable with an
 * injected pool (invariant 10).
 */
public final class ToolCallTimeout {

  private ToolCallTimeout() {}

  /**
   * Run the invocation on the pool under the timeout.
   *
   * @param pool the executor's thread pool the call runs on; never {@code null}
   * @param timeout the per-tool timeout (strictly positive; the descriptor's bound)
   * @param call the tool call to run
   * @param <T> the tool result type
   * @return the tool's result, when it answers within {@code timeout}
   * @throws TimeoutException when the call does not answer within {@code timeout} — the outcome is
   *     ambiguous (the side effect may still have completed; the future is cancelled to free the
   *     pool thread but interrupt does not undo a remote side effect)
   * @throws InterruptedException when the waiting thread is interrupted while awaiting the result
   * @throws ExecutionException when the call raised its own fault (the cause is the tool's fault)
   */
  public static <T> T enforce(ExecutorService pool, Duration timeout, ToolInvocation<T> call)
      throws InterruptedException, ExecutionException, TimeoutException {
    Future<T> future = pool.submit(() -> call.invoke());
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } finally {
      // Free the pool thread regardless of the outcome. cancel(true) interrupts the pool thread so
      // it is not held past the bound, but it does <em>not</em> cancel a remote side effect: the
      // outcome above is ambiguous, and the caller classifies a TimeoutException as such.
      future.cancel(true);
    }
  }
}
