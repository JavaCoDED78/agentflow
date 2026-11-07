package com.example.agentflow.toolexecution.domain;

/**
 * The bounded outcome class of one tool call (S08-13) — the closed vocabulary the invocation
 * history stores, so an operator can tell HOW a call concluded without reading the payload.
 *
 * <p>The class is decided by the {@code BoundedToolExecutor}, never by the tool: the tool only
 * raises its own fault, the executor owns the outcome taxonomy.
 *
 * <ul>
 *   <li>{@link #SUCCEEDED} — the call answered within its timeout and returned a value; the
 *       invocation record carries a reference to the checkpoint holding the output (never the
 *       payload itself).
 *   <li>{@link #THROTTLED} — the call was rejected under a concurrency cap before it started;
 *       nothing executed, so a later attempt with spare capacity is safe and the record carries no
 *       output reference.
 *   <li>{@link #TIMED_OUT} — the call did not answer within its per-tool timeout. The outcome is
 *       deliberately <b>ambiguous</b>: interrupting the pool thread freed the worker but did not
 *       undo a remote side effect that may already have completed (invariant 6 / ADR-003 — an
 *       external call is not exactly-once).
 *   <li>{@link #FAULTED} — the tool raised its own fault (a {@link CalculationException}, a {@link
 *       SchemaValidationException}, or any tool-specific error); the record carries the safe
 *       failure token, never the raw error text.
 * </ul>
 */
public enum ToolInvocationOutcome {
  SUCCEEDED,
  THROTTLED,
  TIMED_OUT,
  FAULTED
}
