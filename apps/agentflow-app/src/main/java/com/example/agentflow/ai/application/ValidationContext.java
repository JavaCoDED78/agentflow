package com.example.agentflow.ai.application;

import com.example.agentflow.ai.domain.plan.PlanLimits;
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
 *       never the model.
 * </ul>
 *
 * <p>All components are required (a {@code null} tenant or {@code null} limits is a
 * misconfiguration the gate fails fast on, naming it); the allowlist sets default to empty so a
 * caller that supplies none yields a plan that fails every tool / node-membership check rather than
 * passing them. This is a pure, immutable value type (no Spring / I/O, invariant 10).
 *
 * @param tenantId the verified tenant the plan must stay inside; never {@code null}
 * @param allowedNodeTypes the closed node vocabulary the Planner may propose (empty by policy when
 *     unset, so unknown node types fail)
 * @param allowedToolNames the tenant-scoped tool names the caller may reference (empty by policy
 *     when unset, so any tool reference fails)
 * @param limits the system-defined step / output-size bounds; never {@code null}
 */
public record ValidationContext(
    UUID tenantId, Set<String> allowedNodeTypes, Set<String> allowedToolNames, PlanLimits limits) {

  /**
   * Canonical constructor. The tenant and the limits are mandatory; the allowlist sets default to
   * empty and are snapshotted unmodifiable (defensively copied, so the caller's mutable set cannot
   * later change a gate decision).
   *
   * @param tenantId the verified tenant the plan must stay inside; never {@code null}
   * @param allowedNodeTypes the closed node vocabulary the Planner may propose
   * @param allowedToolNames the tenant-scoped tool names the caller may reference
   * @param limits the system-defined step / output-size bounds; never {@code null}
   * @throws NullPointerException if {@code tenantId} or {@code limits} is {@code null}
   */
  public ValidationContext {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    allowedNodeTypes = Set.copyOf(allowedNodeTypes == null ? Set.of() : allowedNodeTypes);
    allowedToolNames = Set.copyOf(allowedToolNames == null ? Set.of() : allowedToolNames);
    Objects.requireNonNull(limits, "limits must not be null");
  }
}
