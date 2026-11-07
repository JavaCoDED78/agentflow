package com.example.agentflow.toolexecution.application;

/**
 * The single tool invocation the {@link BoundedToolExecutor} bounds (S08-10).
 *
 * <p>It is the abstraction the bounded executor wraps: the executor does not know <em>which</em>
 * tool it runs (the calculator, the report formatter, a later external call) — it only knows the
 * per-tool {@link java.time.Duration} timeout (from the {@link
 * com.example.agentflow.toolexecution.domain.ToolDescriptor#timeout()}), the global / per-tenant
 * concurrency cap, and that the invocation may run longer than the bound or be rejected under
 * overload. {@code S08-12} wires the real per-tool adapter (resolved by name through the {@link
 * com.example.agentflow.toolexecution.application.ToolRegistry}) into a {@link #invoke()} that
 * performs the permission re-check (S08-09), the input-schema validation (S08-03) and the call.
 *
 * <p>It is deliberately a narrow port — one {@link #invoke()} returning the tool's result — so the
 * executor stays generic over the result type {@code T} and decoupled from every concrete tool. A
 * call may raise its own fault (a {@link
 * com.example.agentflow.toolexecution.domain.CalculationException} / {@link
 * com.example.agentflow.toolexecution.domain.SchemaValidationException}, or any tool-specific
 * error); the executor unwraps and rethrows those so they are classified with the tool's own type,
 * while the executor owns only the two bounded-outcome faults it introduces ({@link
 * ToolExecutionThrottleException} / {@link java.util.concurrent.TimeoutException}).
 */
@FunctionalInterface
public interface ToolInvocation<T> {

  /**
   * Perform the tool call and return its result.
   *
   * @return the tool's result
   * @throws Exception any tool-specific fault the caller unwraps and rethrows (checked faults are
   *     rewrapped so the executor does not hold a checked exception across the bounded call)
   */
  T invoke() throws Exception;
}
