package com.example.agentflow.toolexecution.application;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The invocation policy guard's decision for one tool call (S08-09), the result of re-checking the
 * tool, its risk class and the caller's <em>current</em> permissions immediately before the call.
 *
 * <p>It is a small, immutable value that carries <b>why</b> the guard decided as it did through a
 * stable {@link #reason()} code, so the executor can classify the outcome without parsing a
 * message. A {@link ToolExecutionOutcome#DENIED} decision always names the caller's {@link
 * #missingScopes()} (the required scopes the caller does not currently hold); an {@link
 * ToolExecutionOutcome#ALLOWED} decision carries an empty set. The decision carries neither the
 * caller's credential nor the tool arguments, so it is safe to record or log.
 *
 * <p>It is the value the {@link ToolInvocationPolicy} guard returns and the tool executor (S08-13)
 * consumes; {@code toolexecution} owns it.
 */
public record PermissionDecision(
    ToolExecutionOutcome outcome,
    String reason,
    String toolName,
    String toolVersion,
    Set<String> missingScopes) {

  /**
   * @param outcome {@link ToolExecutionOutcome#ALLOWED} or {@link ToolExecutionOutcome#DENIED}
   * @param reason the stable code the executor classifies by; see {@link ToolInvocationPolicy} for
   *     the closed set ({@code allowed}, {@code unknown_tool}, {@code missing_scope}, {@code
   *     unknown_risk_class})
   * @param toolName the tool the decision is about (may be {@code null} when the tool could not be
   *     resolved, e.g. an unknown tool)
   * @param toolVersion the tool version the decision is about (may be {@code null})
   * @param missingScopes the required scopes the caller does not currently hold; empty when allowed
   *     and never {@code null} (an unmodifiable copy)
   */
  public static PermissionDecision allowed(
      String toolName, String toolVersion, Set<String> missingScopes) {
    return new PermissionDecision(
        ToolExecutionOutcome.ALLOWED,
        ToolInvocationPolicy.REASON_ALLOWED,
        toolName,
        toolVersion,
        immutableMissing(missingScopes));
  }

  /**
   * @param reason the stable denial code ({@code unknown_tool}, {@code missing_scope} or {@code
   *     unknown_risk_class})
   * @param toolName the tool the decision is about (may be {@code null})
   * @param toolVersion the tool version the decision is about (may be {@code null})
   * @param missingScopes the scopes the caller does not currently hold; {@code null} is treated as
   *     empty (for a denial that is not scope related, e.g. an unknown tool)
   */
  public static PermissionDecision denied(
      String reason, String toolName, String toolVersion, Set<String> missingScopes) {
    return new PermissionDecision(
        ToolExecutionOutcome.DENIED,
        reason,
        toolName,
        toolVersion,
        immutableMissing(missingScopes));
  }

  private static Set<String> immutableMissing(Set<String> missingScopes) {
    return Collections.unmodifiableSet(
        new LinkedHashSet<>(missingScopes == null ? Set.of() : missingScopes));
  }
}
