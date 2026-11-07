package com.example.agentflow.toolexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.toolexecution.domain.JsonSchema;
import com.example.agentflow.toolexecution.domain.RetryPolicy;
import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import com.example.agentflow.toolexecution.domain.ToolExecutionContext;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ToolInvocationPolicy} invocation policy guard (S08-09).
 *
 * <p>They verify the acceptance criterion — <em>a permission revoked after activation blocks the
 * call</em> — through the property that makes it hold: the guard re-checks, immediately before the
 * call, the tool (registry), its risk class and the caller's <em>current</em> permission set, and
 * it decides from that current set every time rather than from a decision captured at workflow
 * activation. DB-free: the guard is a pure decision over the in-memory {@link ToolRegistry} and the
 * {@link ToolExecutionContext#capabilities()}; no model, no Kafka, no database, no system clock.
 */
class ToolInvocationPolicyTest {

  private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RUN = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static final String EMAIL = "send-email";
  private static final String CALC = "calculator";

  private final ToolRegistry registry = new ToolRegistry();

  private final ToolInvocationPolicy guard = new ToolInvocationPolicy(registry);

  /** A tool that needs two scopes and must be approved before it changes external state. */
  private static ToolDescriptor emailTool() {
    return ToolDescriptor.of(
        "tool-send-email-1",
        EMAIL,
        "1",
        "Send an email to a recipient.",
        JsonSchema.of(schema("object")),
        JsonSchema.of(schema("object")),
        Set.of("tool:send-email", "mail:send"),
        ToolRiskClass.EXTERNAL_SIDE_EFFECT,
        TIMEOUT,
        RetryPolicy.ofDefaults(),
        true);
  }

  /** A read-only tool that needs no side-effect scope. */
  private static ToolDescriptor calculator() {
    return ToolDescriptor.of(
        "tool-calculator-1",
        CALC,
        "1",
        "Evaluate a safe arithmetic expression like 2 + 3 * 4.",
        JsonSchema.of(schema("object")),
        JsonSchema.of(schema("object")),
        Set.of(),
        ToolRiskClass.READ_ONLY,
        TIMEOUT,
        RetryPolicy.ofDefaults(),
        false);
  }

  private static java.util.Map<String, Object> schema(String type) {
    return java.util.Map.of(
        "$schema", "https://json-schema.org/draft/2020-12/schema", "type", type);
  }

  private static ToolExecutionContext context(Set<String> capabilities) {
    return ToolExecutionContext.of(TENANT, "alice", RUN, "step-1", "corr-1", capabilities);
  }

  private static void seed(ToolRegistry registry) {
    registry.register(emailTool());
    registry.register(calculator());
  }

  @Test
  void an_allowed_call_passes_when_the_current_capabilities_hold_every_required_scope() {
    seed(registry);

    PermissionDecision decision =
        guard.decide(context(Set.of("tool:send-email", "mail:send")), EMAIL, "1");

    assertThat(decision.outcome()).isEqualTo(ToolExecutionOutcome.ALLOWED);
    assertThat(decision.reason()).isEqualTo(ToolInvocationPolicy.REASON_ALLOWED);
    assertThat(decision.missingScopes()).isEmpty();
  }

  @Test
  void a_call_is_denied_when_a_required_scope_is_absent_from_the_current_capabilities() {
    seed(registry);

    PermissionDecision decision = guard.decide(context(Set.of("tool:send-email")), EMAIL, "1");

    assertThat(decision.outcome()).isEqualTo(ToolExecutionOutcome.DENIED);
    assertThat(decision.reason()).isEqualTo(ToolInvocationPolicy.REASON_MISSING_SCOPE);
    assertThat(decision.toolName()).isEqualTo(EMAIL);
    // The missing scope is named so the executor classifies without parsing a message.
    assertThat(decision.missingScopes()).containsExactly("mail:send");
  }

  @Test
  void an_extra_capability_does_not_change_an_allowed_decision() {
    seed(registry);

    PermissionDecision decision =
        guard.decide(context(Set.of("tool:send-email", "mail:send", "workflow:read")), EMAIL, "1");

    assertThat(decision.outcome()).isEqualTo(ToolExecutionOutcome.ALLOWED);
    assertThat(decision.missingScopes()).isEmpty();
  }

  @Test
  void a_read_only_tool_with_no_required_scope_is_allowed_regardless_of_the_capabilities() {
    seed(registry);

    PermissionDecision withEmptyCapabilities = guard.decide(context(Set.of()), CALC, "1");

    assertThat(withEmptyCapabilities.outcome()).isEqualTo(ToolExecutionOutcome.ALLOWED);
    // An empty capability set grants no side effect, but a read-only tool needs none, so it is
    // still allowed — the empty scope set is "no side effect permitted", not "everything denied".
  }

  @Test
  void a_scoped_tool_is_denied_when_the_current_capabilities_do_not_include_it() {
    seed(registry);

    PermissionDecision decision = guard.decide(context(Set.of("workflow:read")), EMAIL, "1");

    assertThat(decision.outcome()).isEqualTo(ToolExecutionOutcome.DENIED);
    assertThat(decision.reason()).isEqualTo(ToolInvocationPolicy.REASON_MISSING_SCOPE);
    assertThat(decision.missingScopes()).containsExactlyInAnyOrder("tool:send-email", "mail:send");
  }

  @Test
  void an_unknown_tool_is_denied_and_never_reaches_the_call() {
    seed(registry);

    PermissionDecision decision =
        guard.decide(context(Set.of("tool:send-email", "mail:send")), "never-registered", "1");

    assertThat(decision.outcome()).isEqualTo(ToolExecutionOutcome.DENIED);
    assertThat(decision.reason()).isEqualTo(ToolInvocationPolicy.REASON_UNKNOWN_TOOL);
    assertThat(decision.missingScopes()).isEmpty();
  }

  @Test
  void an_unknown_tool_is_denied_even_when_the_caller_holds_every_scope() {
    // The capabilities are irrelevant once the tool does not resolve — an unknown tool is never
    // called regardless of the caller's permission set.
    seed(registry);

    PermissionDecision decision =
        guard.decide(context(Set.of("all:scopes", "to:everything")), "never-registered", "9");

    assertThat(decision.outcome()).isEqualTo(ToolExecutionOutcome.DENIED);
    assertThat(decision.reason()).isEqualTo(ToolInvocationPolicy.REASON_UNKNOWN_TOOL);
  }

  @Test
  void a_null_context_denies_a_scoped_tool_and_allows_a_read_only_one() {
    seed(registry);

    PermissionDecision scoped = guard.decide(null, EMAIL, "1");
    PermissionDecision readOnly = guard.decide(null, CALC, "1");

    // The guard fails safe: no current capabilities means no scope is held, so a scoped tool is
    // denied while a read-only tool (which needs no scope) is allowed.
    assertThat(scoped.outcome()).isEqualTo(ToolExecutionOutcome.DENIED);
    assertThat(scoped.reason()).isEqualTo(ToolInvocationPolicy.REASON_MISSING_SCOPE);
    assertThat(readOnly.outcome()).isEqualTo(ToolExecutionOutcome.ALLOWED);
  }

  @Test
  void a_permission_revoked_after_activation_blocks_the_invocation() {
    // The acceptance criterion for S08-09. The same tool is checked twice: first while the caller
    // holds the required scope (the state at/around activation), then with the scope removed —
    // which
    // is exactly what a revocation produces in the trusted context by invocation time. The guard
    // re-reads the current capabilities each time, so the second check denies.
    seed(registry);

    PermissionDecision atActivation =
        guard.decide(context(Set.of("tool:send-email", "mail:send")), EMAIL, "1");
    PermissionDecision atInvocationAfterRevocation = guard.decide(context(Set.of()), EMAIL, "1");

    assertThat(atActivation.outcome()).isEqualTo(ToolExecutionOutcome.ALLOWED);
    assertThat(atInvocationAfterRevocation.outcome()).isEqualTo(ToolExecutionOutcome.DENIED);
    assertThat(atInvocationAfterRevocation.reason())
        .isEqualTo(ToolInvocationPolicy.REASON_MISSING_SCOPE);
    assertThat(atInvocationAfterRevocation.missingScopes())
        .containsExactlyInAnyOrder("tool:send-email", "mail:send");
  }

  @Test
  void the_guard_rechecks_current_permissions_and_does_not_trust_an_activation_decision() {
    // The guard must not reuse a decision made at activation. Given the same tool, the first call
    // is allowed (the scope is held now) and the very next call with the scope gone is denied — the
    // only thing that changed is the current permission set, so the guard is proving it evaluates
    // current permissions rather than a frozen activation flag.
    seed(registry);

    PermissionDecision first =
        guard.decide(context(Set.of("tool:send-email", "mail:send")), EMAIL, "1");
    PermissionDecision again = guard.decide(context(Set.of("tool:send-email")), EMAIL, "1");

    assertThat(first.outcome()).isEqualTo(ToolExecutionOutcome.ALLOWED);
    assertThat(again.outcome()).isEqualTo(ToolExecutionOutcome.DENIED);
    assertThat(again.missingScopes()).containsExactly("mail:send");
  }

  @Test
  void an_allowed_decision_carries_an_empty_unmodifiable_missing_scope_set() {
    seed(registry);

    PermissionDecision decision =
        guard.decide(context(Set.of("tool:send-email", "mail:send")), EMAIL, "1");

    assertThat(decision.missingScopes()).isEmpty();
    assertThatThrownBy(() -> decision.missingScopes().add("tool:x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void a_denied_decision_carries_the_missing_scopes_unmodifiable() {
    seed(registry);

    PermissionDecision decision = guard.decide(context(Set.of("tool:send-email")), EMAIL, "1");

    assertThat(decision.missingScopes()).containsExactly("mail:send");
    assertThatThrownBy(() -> decision.missingScopes().add("tool:x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
