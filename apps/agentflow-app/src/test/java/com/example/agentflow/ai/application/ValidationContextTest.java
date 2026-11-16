package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.domain.plan.PlanLimits;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for {@link ValidationContext} (S09-07): the external facts the gate reads. A
 * {@code null} tenant or {@code null} limits is a misconfiguration the gate fails fast on; the
 * allowlist sets snapshot unmodifiable.
 */
class ValidationContextTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final PlanLimits LIMITS = new PlanLimits(50, 8_192);

  @Test
  void exposes_tenant_allowed_types_allowed_tools_and_limits() {
    ValidationContext ctx =
        new ValidationContext(TENANT, Set.of("FINALIZE"), Set.of("calc"), LIMITS);

    assertThat(ctx.tenantId()).isEqualTo(TENANT);
    assertThat(ctx.allowedNodeTypes()).containsExactly("FINALIZE");
    assertThat(ctx.allowedToolNames()).containsExactly("calc");
    assertThat(ctx.limits()).isEqualTo(LIMITS);
  }

  @Test
  void a_null_tenant_is_a_misconfiguration() {
    assertThatThrownBy(() -> new ValidationContext(null, Set.of(), Set.of(), LIMITS))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_null_limits_is_a_misconfiguration() {
    assertThatThrownBy(() -> new ValidationContext(TENANT, Set.of(), Set.of(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void the_allowlists_snapshot_the_supplied_sets_unmodifiable() {
    Set<String> nodeTypes = new java.util.LinkedHashSet<>(List.of("FINALIZE"));
    Set<String> tools = new java.util.LinkedHashSet<>(List.of("calc", "report"));
    ValidationContext ctx = new ValidationContext(TENANT, nodeTypes, tools, LIMITS);

    nodeTypes.add("TOOL_CALL");
    tools.add("mystery");

    assertThat(ctx.allowedNodeTypes()).containsExactlyInAnyOrder("FINALIZE");
    assertThat(ctx.allowedToolNames()).containsExactlyInAnyOrder("calc", "report");
  }

  @Test
  void an_unset_allowlist_defaults_to_empty_so_membership_fails() {
    ValidationContext ctx = new ValidationContext(TENANT, null, null, LIMITS);

    assertThat(ctx.allowedNodeTypes()).isEmpty();
    assertThat(ctx.allowedToolNames()).isEmpty();
  }

  @Test
  void the_policy_map_and_caller_scopes_default_to_empty() {
    ValidationContext ctx =
        new ValidationContext(TENANT, Set.of("FINALIZE"), Set.of("calc"), LIMITS);

    assertThat(ctx.toolPolicies()).isEmpty();
    assertThat(ctx.callerScopes()).isEmpty();
  }

  @Test
  void the_six_component_form_exposes_tool_policies_and_caller_scopes() {
    ToolGatePolicy policy = new ToolGatePolicy(Set.of("write:report"), true);
    ValidationContext ctx =
        new ValidationContext(
            TENANT,
            Set.of("TOOL_CALL"),
            Set.of("report"),
            LIMITS,
            Map.of("report", policy),
            Set.of("write:report"));

    assertThat(ctx.toolPolicies()).containsExactly(Map.entry("report", policy));
    assertThat(ctx.callerScopes()).containsExactly("write:report");
  }

  @Test
  void the_policy_map_and_caller_scopes_snapshot_unmodifiable() {
    Map<String, ToolGatePolicy> policies =
        new java.util.LinkedHashMap<>(Map.of("calc", new ToolGatePolicy(Set.of(), false)));
    Set<String> scopes = new java.util.LinkedHashSet<>(List.of("write:report"));
    ValidationContext ctx =
        new ValidationContext(
            TENANT, Set.of("TOOL_CALL"), Set.of("calc", "report"), LIMITS, policies, scopes);

    policies.put("report", new ToolGatePolicy(Set.of("write:report"), false));
    scopes.add("admin");

    assertThat(ctx.toolPolicies()).containsOnlyKeys("calc");
    assertThat(ctx.callerScopes()).containsExactly("write:report");
    assertThatThrownBy(() -> ctx.toolPolicies().put("x", new ToolGatePolicy(Set.of(), false)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ctx.callerScopes().add("admin"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
