package com.example.agentflow.toolexecution.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The minimal, tenant-scoped context a tool call is executed with (S08-08).
 *
 * <p>It is the call context the executor hands to a tool adapter: everything the tool needs to know
 * <em>who</em> is calling and <em>within what</em> unit of work, and nothing that should not reach
 * a tool. It deliberately carries <b>no secret</b> — no JWT, API key, token, full prompt or full
 * document. The plan task is explicit about this: {@code "do not hand out secrets to every tool
 * adapter"} — a tool adapter must not be able to read the caller's credential or the document it is
 * processing, so the context is the narrow slice {@code (tenant, actor, run, step, correlationId,
 * minimal capabilities)} and no more.
 *
 * <p><b>Where it comes from, and why the model cannot replace it.</b> The context is built
 * <b>server-side</b> from the verified security context (HTTP) or the stored run re-checked against
 * PostgreSQL (worker) — never from the Planner's arguments (invariant 1). It is an immutable value:
 * a tool adapter can read {@link #tenantId()}, {@link #actor()} and the rest, but it cannot change
 * them, and the {@link #capabilities()} set is returned unmodifiable, so even a best-effort attempt
 * to mutate it fails loudly. This is what closes the S08-08 acceptance criterion — a model argument
 * can never swap the actor or tenant the call runs as, because the context is fixed at construction
 * from a trusted source and exposes no way to mutate it.
 *
 * <p>The fields mirror the trusted sources the executor threads explicitly (as {@code
 * com.example.agentflow.identity.application.TenantContext} does for {@code tenantId}/{@code
 * actor}):
 *
 * <ul>
 *   <li>{@link #tenantId()} — the owning tenant, the unit of data separation (invariant 1);
 *       mandatory, a context with no tenant is meaningless, so construction rejects a {@code null}
 *       tenant.
 *   <li>{@link #actor()} — the verified subject ({@code sub}) of the caller, recorded for the audit
 *       trail; may be {@code null} only where the trusted context carried none (see {@code
 *       TenantContext}).
 *   <li>{@link #runId()} — the run the call belongs to; mandatory, because a tool call is never
 *       outside a run.
 *   <li>{@link #stepRunId()} — the step the call belongs to; nullable for a call not tied to a
 *       single step.
 *   <li>{@link #correlationId()} — the correlation label for tracing; nullable (the wiring layer
 *       defaults it to {@code runId}).
 *   <li>{@link #capabilities()} — the minimal permission scopes the caller holds for this call,
 *       used by the invocation permission guard; an empty set grants no side effect.
 * </ul>
 *
 * <p>Like {@link ToolDescriptor} and the other values in {@code toolexecution.domain} it is a pure,
 * dependency-free value that reads neither time nor randomness and carries no Spring or I/O
 * dependency (invariant 10), so it is fully testable in isolation.
 */
public record ToolExecutionContext(
    UUID tenantId,
    String actor,
    UUID runId,
    String stepRunId,
    String correlationId,
    Set<String> capabilities) {

  /**
   * Canonical-constructor validation. The fields that scope a tool call to a verified tenant and
   * run are checked here, so an invalid context can never reach an adapter:
   *
   * <ul>
   *   <li>{@code tenantId} must be present — a context with no tenant cannot scope a call
   *       (invariant 1);
   *   <li>{@code runId} must be present — a tool call is never outside a run;
   *   <li>{@code capabilities} must be present and is stored as an unmodifiable copy so a caller
   *       (including a tool adapter) cannot mutate the capability set after building the context.
   * </ul>
   *
   * {@code actor}, {@code stepRunId} and {@code correlationId} may be {@code null}: the actor may
   * be absent from the trusted context, and the correlation label defaults to the run at the wiring
   * layer.
   */
  public ToolExecutionContext {
    requireNonNull(tenantId, "tenantId");
    requireNonNull(runId, "runId");
    requireNonNull(capabilities, "capabilities");
    capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(capabilities));
  }

  /**
   * @param tenantId the owning tenant, resolved from the verified security context (HTTP) or the
   *     stored run (worker) — never from request input; never {@code null}
   * @param actor the verified subject of the caller, recorded for the audit trail; may be {@code
   *     null} only if the trusted context carried none
   * @param runId the run the call belongs to; never {@code null}
   * @param stepRunId the step the call belongs to; may be {@code null} for a call not tied to a
   *     single step
   * @param correlationId the correlation label for tracing; may be {@code null} (defaults to {@code
   *     runId} at the wiring layer)
   * @param capabilities the minimal permission scopes the caller holds for this call (may be empty;
   *     an empty set grants no side effect)
   * @return a validated, immutable {@link ToolExecutionContext}
   * @throws IllegalArgumentException if {@code tenantId}, {@code runId} or {@code capabilities} is
   *     {@code null}
   */
  public static ToolExecutionContext of(
      UUID tenantId,
      String actor,
      UUID runId,
      String stepRunId,
      String correlationId,
      Set<String> capabilities) {
    return new ToolExecutionContext(tenantId, actor, runId, stepRunId, correlationId, capabilities);
  }

  private static void requireNonNull(Object value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
  }
}
