package com.example.agentflow.toolexecution.application;

import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import com.example.agentflow.toolexecution.domain.ToolExecutionContext;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The invocation policy guard (S08-09): re-checks, immediately <em>before</em> a tool is invoked,
 * that the tool is still registered, its risk class is known, and the caller holds every required
 * scope in its <em>current</em> permission set — and refuses the call otherwise.
 *
 * <p><b>Why this is a separate check from workflow activation.</b> The workflow activation / plan
 * validation gate (S09-04) decides, once, that a plan is executable: the tools it references exist
 * and the activating caller held the scopes at that moment. That decision is <em>not</em> trusted
 * at invocation. Between activation and invocation the caller's permissions can change — a scope
 * can be revoked, a role can be reassigned — so the guard re-resolves the caller's <em>current</em>
 * permissions and re-checks them against the tool every time a tool runs (the S08-09 acceptance: a
 * permission revoked after activation blocks the call). The guard therefore never reads a boolean
 * "was it allowed at activation"; it reads what the caller may do <em>now</em>.
 *
 * <p>The guard decides through the {@link ToolRegistry}: it resolves the tool by {@code (name,
 * version)} so an unknown tool resolves to nothing and is denied (an unknown tool is never called,
 * invariant 7 / S08-02). It then compares the caller's current scopes against the descriptor's
 * {@link ToolDescriptor#requiredScopes()}. An empty required-scope set (a read-only tool) grants no
 * side effect and is therefore always allowed regardless of the caller's capabilities; a tool whose
 * required scopes are not all held is denied, naming the missing scopes. A {@link
 * ToolExecutionContext} with {@code null} capabilities is treated as "no capability", so a guard
 * against a tool that needs a scope denies — the guard fails safe.
 *
 * <p><b>Where the current permissions come from.</b> The caller passes the {@link
 * ToolExecutionContext} the executor already built for this call (S08-08, immutable, built
 * server-side from the verified context, carrying the caller's minimal capabilities). The guard
 * reads {@link ToolExecutionContext#capabilities()} as the current permission set for <em>this</em>
 * invocation; it is resolved fresh for each call, never a snapshot captured when the workflow was
 * activated. The tenant/actor thread through the context are the trusted, verified identity
 * (invariant 1); the guard reads no caller-controlled field for authorization.
 *
 * <p>The guard is a pure decision over the registry and the current capabilities: it reads no time,
 * no randomness, no database and no Kafka, and depends only on the DB-free {@link ToolRegistry}, so
 * it is safe in every profile and fully testable in isolation (invariant 10). It adds no module
 * edge — {@code toolexecution} already depends on {@code agentcatalog} for {@link
 * com.example.agentflow.agentcatalog.domain.ToolRiskClass}, which a {@link ToolDescriptor} carries.
 *
 * <p><b>Audit.</b> The guard only <em>decides</em>; writing the allow/deny record to the
 * append-only audit trail (module-map business-rule ownership: "tool allow/deny/execute is an
 * audited action", edge 4) is the executor's job, applied to the {@link PermissionDecision} it
 * returns, so the decision and its record stay consistent.
 */
@Service
public class ToolInvocationPolicy {

  /** The call is permitted: the tool resolved and the caller holds every required scope. */
  public static final String REASON_ALLOWED = "allowed";

  /**
   * No tool is registered for the requested {@code (name, version)}; unknown tools are never
   * called.
   */
  public static final String REASON_UNKNOWN_TOOL = "unknown_tool";

  /** The caller does not currently hold every {@code requiredScopes} scope of the tool. */
  public static final String REASON_MISSING_SCOPE = "missing_scope";

  /**
   * The tool resolved but carries no risk class. An unknown risk class is denied by default ({@code
   * S11-01}); a registered descriptor always carries one, so this is a defensive fail-safe, never a
   * path a valid descriptor reaches.
   */
  public static final String REASON_UNKNOWN_RISK_CLASS = "unknown_risk_class";

  private final ToolRegistry registry;

  /**
   * @param registry the deterministic tool registry the guard resolves tool descriptors through;
   *     never {@code null}
   */
  public ToolInvocationPolicy(ToolRegistry registry) {
    this.registry = registry;
  }

  /**
   * Re-check, immediately before invoking a tool, whether the call is permitted now.
   *
   * @param context the call context the executor built for this invocation; its {@code
   *     capabilities} are the caller's current permission set — never a value captured at workflow
   *     activation; may be {@code null} (treated as "no capability", so a scoped tool is denied)
   * @param toolName the callable tool name to check
   * @param toolVersion the tool version to check
   * @return the guard's {@link PermissionDecision}: {@link ToolExecutionOutcome#ALLOWED} when the
   *     tool is registered and the caller holds every required scope, otherwise {@link
   *     ToolExecutionOutcome#DENIED} (with a stable {@code reason()} and, when scope related, the
   *     missing scopes)
   */
  public PermissionDecision decide(
      ToolExecutionContext context, String toolName, String toolVersion) {
    Set<String> currentScopes = currentScopes(context);
    Optional<ToolDescriptor> tool = registry.resolve(toolName, toolVersion);
    if (tool.isEmpty()) {
      return PermissionDecision.denied(REASON_UNKNOWN_TOOL, toolName, toolVersion, null);
    }
    return decideFor(tool.get(), currentScopes);
  }

  /**
   * The pure decision over an already-resolved descriptor and the caller's current scopes. Kept
   * package-private so the guard is the single entry point, and so a caller cannot bypass the
   * registry resolution (which is what enforces "unknown tool → denied").
   *
   * @param tool the resolved descriptor; never {@code null}
   * @param currentScopes the caller's current permission scopes (an empty set grants no side
   *     effect)
   * @return the guard's {@link PermissionDecision}
   */
  PermissionDecision decideFor(ToolDescriptor tool, Set<String> currentScopes) {
    if (tool.riskClass() == null) {
      return PermissionDecision.denied(
          REASON_UNKNOWN_RISK_CLASS, tool.name(), tool.version(), null);
    }
    Set<String> missing = new LinkedHashSet<>(tool.requiredScopes());
    missing.removeAll(currentScopes == null ? Set.of() : currentScopes);
    if (!missing.isEmpty()) {
      return PermissionDecision.denied(REASON_MISSING_SCOPE, tool.name(), tool.version(), missing);
    }
    return PermissionDecision.allowed(tool.name(), tool.version(), Set.of());
  }

  private static Set<String> currentScopes(ToolExecutionContext context) {
    return context == null || context.capabilities() == null ? Set.of() : context.capabilities();
  }
}
