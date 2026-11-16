package com.example.agentflow.ai.application;

import java.util.Set;

/**
 * The policy facts of one tool, supplied to the {@link ValidationGate} as data (S11-12, the second
 * policy stage): which permission scopes a caller must currently hold to reference the tool in a
 * plan, and whether the tool's action is gated on a human decision.
 *
 * <p>The gate is deliberately pure and compiles against no registry ({@code S02-08}: {@code ai} may
 * not depend on {@code toolexecution}'s tool registry or {@code identity}'s permission set), so the
 * caller — the layer that owns the verified security context and the cross-module registry reach —
 * resolves each allowlisted tool's descriptor into these facts and passes them through {@link
 * ValidationContext}. The gate then re-checks, <em>after</em> the model answered, that the plan
 * references no capability the caller's current permission set does not grant (invariant 7: the
 * model proposes, the backend authorizes).
 *
 * <p>An empty {@code requiredScopes} is meaningful: a read-only tool grants no side effect and is
 * therefore referenceable by any caller the structural allowlist admitted. {@code null} scopes
 * normalize to empty (the immutable value holds no {@code null} set). This is a pure, immutable
 * value type (no Spring / I/O, invariant 10).
 *
 * @param requiredScopes the permission scopes a caller must currently hold to reference the tool;
 *     {@code null} normalizes to empty (no scope required)
 * @param requiresHumanApproval {@code true} when the tool's action is gated on a human decision and
 *     must never be authorized by the automatic gate
 */
public record ToolGatePolicy(Set<String> requiredScopes, boolean requiresHumanApproval) {

  /**
   * Canonical constructor: {@code null} scopes normalize to empty and are snapshotted unmodifiable
   * (defensively copied, so the caller's mutable set cannot later change a gate decision).
   *
   * @param requiredScopes the permission scopes a caller must currently hold; {@code null} means
   *     none
   * @param requiresHumanApproval whether the tool is gated on a human decision
   */
  public ToolGatePolicy {
    requiredScopes = Set.copyOf(requiredScopes == null ? Set.of() : requiredScopes);
  }

  /**
   * @param scope the scope to test; may be {@code null}
   * @return {@code true} when the caller must hold {@code scope} to reference the tool
   */
  public boolean requiresScope(String scope) {
    return requiredScopes.contains(scope);
  }
}
