package com.example.agentflow.ai.application;

import com.example.agentflow.ai.domain.plan.PlanLimits;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The external, context-dependent facts the {@link ValidationGate} cannot derive from the plan
 * alone (S09-07): the verified tenant, the closed node-type vocabulary the Planner may propose, the
 * tool names the caller is allowed to reference, and the system-defined {@link PlanLimits}.
 *
 * <p>The gate is deliberately pure: it owns none of these, so it cannot compile-depend on {@code
 * workflowdefinition}'s node registry, {@code toolexecution}'s tool registry or the {@code
 * identity} security context (the {@code ai} module boundary, {@code S02-08}). The caller — the
 * layer that <em>does</em> have the verified security context and the cross-module registry reach —
 * assembles this value from them and passes it to the gate:
 *
 * <ul>
 *   <li>{@link #tenantId()} — the tenant from the verified security context (invariant 1); the
 *       allowlists are scoped to it, so checking a plan against them is the tenant-scope check;
 *   <li>{@link #allowedNodeTypes()} — the closed node vocabulary the Planner may propose, resolved
 *       from the workflow node registry (a {@code null}/{@code blank} type a step carries is
 *       rejected before this set is consulted);
 *   <li>{@link #allowedToolNames()} — the tool names the caller may reference (the tenant-scoped
 *       allowlist), so a {@code TOOL_CALL} step naming a tool outside it is "unknown" to the gate;
 *   <li>{@link #limits()} — the {@link PlanLimits} step / output-size bounds the backend sets,
 *       never the model;
 *   <li>{@link #toolPolicies()} — the per-tool policy facts (required scopes, human-approval
 *       requirement) the second policy stage re-checks (S11-12): the gate is pure and reaches no
 *       registry ({@code S02-08}), so the caller resolves each allowlisted tool's descriptor into
 *       these facts; empty by default, in which case the stage cannot re-check scopes and the
 *       invocation-time guard stays the mandatory backstop;
 *   <li>{@link #callerScopes()} — the caller's <em>current</em> permission scopes the scope
 *       re-check compares against, never a snapshot from when the catalogue was built; empty by
 *       default, so a scope-requiring tool fails the re-check whenever the caller supplies none.
 * </ul>
 *
 * <p>The tenant and the limits are required (a {@code null} value is a misconfiguration the gate
 * fails fast on, naming it); the allowlist sets, the policy map and the caller scopes default to
 * empty so a caller that supplies none yields a plan that fails every membership / scope check
 * rather than passing it. This is a pure, immutable value type (no Spring / I/O, invariant 10).
 *
 * @param tenantId the verified tenant the plan must stay inside; never {@code null}
 * @param allowedNodeTypes the closed node vocabulary the Planner may propose (empty by policy when
 *     unset, so unknown node types fail)
 * @param allowedToolNames the tenant-scoped tool names the caller may reference (empty by policy
 *     when unset, so any tool reference fails)
 * @param limits the system-defined step / output-size bounds; never {@code null}
 * @param toolPolicies the per-tool policy facts the second policy stage re-checks (empty when
 *     unset, so the scope / approval re-check is skipped and the invocation-time guard remains the
 *     backstop)
 * @param callerScopes the caller's current permission scopes the scope re-check compares against
 *     (empty when unset, so any scope-requiring tool fails the re-check)
 */
public record ValidationContext(
    UUID tenantId,
    Set<String> allowedNodeTypes,
    Set<String> allowedToolNames,
    PlanLimits limits,
    Map<String, ToolGatePolicy> toolPolicies,
    Set<String> callerScopes) {

  /**
   * The four-component form (S09-07): no tool policy facts and no caller scopes. The second policy
   * stage (S11-12) is then inert — the structural checks (node vocabulary, tool allowlist, field
   * contract, DAG, limits) still run, and the invocation-time permission guard remains the
   * mandatory backstop that re-checks the current scopes before any tool runs.
   *
   * @param tenantId the verified tenant the plan must stay inside; never {@code null}
   * @param allowedNodeTypes the closed node vocabulary the Planner may propose
   * @param allowedToolNames the tenant-scoped tool names the caller may reference
   * @param limits the system-defined step / output-size bounds; never {@code null}
   */
  public ValidationContext(
      UUID tenantId,
      Set<String> allowedNodeTypes,
      Set<String> allowedToolNames,
      PlanLimits limits) {
    this(tenantId, allowedNodeTypes, allowedToolNames, limits, null, null);
  }

  /**
   * Canonical constructor. The tenant and the limits are mandatory; the allowlist sets default to
   * empty and are snapshotted unmodifiable (defensively copied, so the caller's mutable set cannot
   * later change a gate decision); the policy map defaults to empty and is snapshotted unmodifiable
   * as well; the caller scopes default to empty and are snapshotted unmodifiable.
   *
   * @param tenantId the verified tenant the plan must stay inside; never {@code null}
   * @param allowedNodeTypes the closed node vocabulary the Planner may propose
   * @param allowedToolNames the tenant-scoped tool names the caller may reference
   * @param limits the system-defined step / output-size bounds; never {@code null}
   * @param toolPolicies the per-tool policy facts the second policy stage re-checks; {@code null}
   *     means none (the stage is inert)
   * @param callerScopes the caller's current permission scopes; {@code null} means none (any
   *     scope-requiring tool fails the re-check)
   * @throws NullPointerException if {@code tenantId} or {@code limits} is {@code null}
   */
  public ValidationContext {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    allowedNodeTypes = Set.copyOf(allowedNodeTypes == null ? Set.of() : allowedNodeTypes);
    allowedToolNames = Set.copyOf(allowedToolNames == null ? Set.of() : allowedToolNames);
    Objects.requireNonNull(limits, "limits must not be null");
    toolPolicies = Map.copyOf(toolPolicies == null ? Map.of() : toolPolicies);
    callerScopes = Set.copyOf(callerScopes == null ? Set.of() : callerScopes);
  }
}
