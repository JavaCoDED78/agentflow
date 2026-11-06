package com.example.agentflow.toolexecution.domain;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The contract of a registered tool: everything the executor needs to decide <em>whether</em> and
 * <em>how</em> it may call a tool, before the tool is ever invoked (S08-01; glossary "Tool").
 *
 * <p>It is the immutable value that closes the loop between the tool allowlist (which
 * tool/permission a run is allowed to use) and the executor (which runs it). A tool is only
 * callable when it has a {@link #riskClass()} the caller is permitted to use, the caller holds
 * every {@link #requiredScopes()} scope, and the call passes the {@link #inputSchema()} / {@link
 * #outputSchema()} validation — none of which is inferred from the human-readable {@link
 * #description()}.
 *
 * <p><b>Important — the description is not authorization.</b> {@link #description()} is prose the
 * Planner (an untrusted planner, {@code ADR-006}) reads to choose a tool. It is metadata, never a
 * permission: a rich description never grants a scope the caller lacks, and the {@code
 * #requiredScopes()} / {@link #riskClass()} checks are decided server-side from the verified
 * permission context (invariant: the model is not an authorization authority). An empty scope set
 * therefore means "no side effect is permitted regardless of the description", not "the description
 * says it is safe".
 *
 * <p>It is an immutable value object with value equality, so a descriptor can be compared and
 * cached by the registry (S08-02).
 *
 * @param id a stable, unique identifier for this tool definition (the registry dedupes on it)
 * @param name the referenced/callable name the Planner uses to refer to the tool (never {@code
 *     null} or blank)
 * @param version the descriptor version; the pair {@code (name, version)} identifies a tool version
 *     (never {@code null} or blank)
 * @param description human-readable prose the Planner reads to decide whether to call the tool; it
 *     is metadata only and is <em>not</em> an authorization mechanism
 * @param inputSchema the machine-readable JSON Schema the executor validates a call's arguments
 *     against (required; never {@code null})
 * @param outputSchema the machine-readable JSON Schema the executor validates the tool's result
 *     against (required; never {@code null})
 * @param requiredScopes the permission scopes this call needs (e.g. {@code tool:calculator}); an
 *     unmodifiable, non-null set — an empty set means no side-effect scope is granted
 * @param riskClass the blast radius of the tool (required; never {@code null}); an unknown risk
 *     class is denied by default ({@code S11-01})
 * @param timeout the per-call timeout (required; strictly positive)
 * @param retryPolicy how a failed call is retried (required; never {@code null})
 * @param requiresHumanApproval whether this call must pause for a human decision first (e.g. a
 *     {@code WRITE}/{@code EXTERNAL_SIDE_EFFECT} that changes external state)
 */
public record ToolDescriptor(
    String id,
    String name,
    String version,
    String description,
    JsonSchema inputSchema,
    JsonSchema outputSchema,
    Set<String> requiredScopes,
    ToolRiskClass riskClass,
    Duration timeout,
    RetryPolicy retryPolicy,
    boolean requiresHumanApproval) {

  /**
   * Canonical-constructor validation. Every field that the executor needs to make a safe decision
   * is checked here, so an invalid descriptor can never reach a call:
   *
   * <ul>
   *   <li>{@code id}, {@code name} and {@code version} must be non-blank (a tool is identified by a
   *       name + version);
   *   <li>{@code inputSchema} and {@code outputSchema} must be present (a tool without a
   *       machine-readable schema cannot be validated, so it is not registered);
   *   <li>{@code requiredScopes} must be present and is stored as an unmodifiable copy so a caller
   *       cannot mutate the descriptor's allowlist after building it;
   *   <li>{@code riskClass} and {@code retryPolicy} must be present;
   *   <li>{@code timeout} must be strictly positive (a non-positive timeout would not bound the
   *       call, contrary to S08-10).
   * </ul>
   */
  public ToolDescriptor {
    requireNonBlank(id, "id");
    requireNonBlank(name, "name");
    requireNonBlank(version, "version");
    requireNonNull(description, "description");
    requireNonNull(inputSchema, "inputSchema");
    requireNonNull(outputSchema, "outputSchema");
    requireNonNull(requiredScopes, "requiredScopes");
    requireNonNull(riskClass, "riskClass");
    requireNonNull(retryPolicy, "retryPolicy");
    if (timeout == null || !timeout.isPositive()) {
      throw new IllegalArgumentException("timeout must be strictly positive but was " + timeout);
    }
    requiredScopes = Collections.unmodifiableSet(new LinkedHashSet<>(requiredScopes));
  }

  /**
   * @param id a stable, unique identifier for this tool definition
   * @param name the referenced/callable name the Planner uses
   * @param version the descriptor version
   * @param description human-readable prose (metadata; not authorization)
   * @param inputSchema the machine-readable argument JSON Schema
   * @param outputSchema the machine-readable result JSON Schema
   * @param requiredScopes the permission scopes this call needs (may be empty for read-only)
   * @param riskClass the blast radius of the tool
   * @param timeout the per-call timeout (strictly positive)
   * @param retryPolicy how a failed call is retried
   * @param requiresHumanApproval whether the call must pause for a human decision
   * @return a validated {@link ToolDescriptor}, or {@link IllegalArgumentException} if any field is
   *     invalid
   */
  public static ToolDescriptor of(
      String id,
      String name,
      String version,
      String description,
      JsonSchema inputSchema,
      JsonSchema outputSchema,
      Set<String> requiredScopes,
      ToolRiskClass riskClass,
      Duration timeout,
      RetryPolicy retryPolicy,
      boolean requiresHumanApproval) {
    return new ToolDescriptor(
        id,
        name,
        version,
        description,
        inputSchema,
        outputSchema,
        requiredScopes,
        riskClass,
        timeout,
        retryPolicy,
        requiresHumanApproval);
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be null or blank");
    }
  }

  private static void requireNonNull(Object value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
  }
}
