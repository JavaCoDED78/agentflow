package com.example.agentflow.toolexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ToolDescriptor} contract (S08-01).
 *
 * <p>They verify the acceptance criterion — every tool carries a machine-readable schema and risk
 * metadata — and the construction invariants: a non-blank identity, present schemas, a strictly
 * positive timeout, present scopes/risk-class/retry-policy, an unmodifiable allowlist, and value
 * equality. DB-free.
 */
class ToolDescriptorTest {

  private static final RetryPolicy RETRY = RetryPolicy.ofDefaults();
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static Map<String, Object> schema(String type) {
    return Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "type", type);
  }

  private static ToolDescriptor calculator() {
    return ToolDescriptor.of(
        "tool-calculator-1",
        "calculator",
        "1",
        "Evaluate a safe arithmetic expression like 2 + 3 * 4.",
        JsonSchema.of(schema("object")),
        JsonSchema.of(schema("object")),
        Set.of(),
        ToolRiskClass.READ_ONLY,
        TIMEOUT,
        RETRY,
        false);
  }

  @Test
  void a_descriptor_carries_a_machine_readable_schema_and_risk_metadata() {
    ToolDescriptor tool = calculator();
    assertThat(tool.inputSchema()).isNotNull();
    assertThat(tool.outputSchema()).isNotNull();
    assertThat(tool.inputSchema().root()).containsEntry("type", "object");
    assertThat(tool.riskClass()).isEqualTo(ToolRiskClass.READ_ONLY);
    // The description is human prose; it is distinct from the machine-readable schema.
    assertThat(tool.description()).contains("arithmetic");
  }

  @Test
  void of_defaults_retry_and_timeout_are_used_when_built() {
    ToolDescriptor tool = calculator();
    assertThat(tool.retryPolicy()).isEqualTo(RETRY);
    assertThat(tool.timeout()).isEqualTo(TIMEOUT);
    assertThat(tool.requiresHumanApproval()).isFalse();
    assertThat(tool.name()).isEqualTo("calculator");
    assertThat(tool.version()).isEqualTo("1");
  }

  @Test
  void the_required_scopes_are_stored_unmodifiable() {
    ToolDescriptor tool =
        ToolDescriptor.of(
            "id",
            "send-email",
            "1",
            "Send an email.",
            JsonSchema.of(schema("object")),
            JsonSchema.of(schema("object")),
            Set.of("tool:send-email", "mail:send"),
            ToolRiskClass.EXTERNAL_SIDE_EFFECT,
            TIMEOUT,
            RETRY,
            true);
    assertThat(tool.requiredScopes()).containsExactlyInAnyOrder("tool:send-email", "mail:send");
    // The descriptor's allowlist cannot be mutated by the caller after building it.
    assertThatThrownBy(() -> tool.requiredScopes().add("admin:all"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void an_empty_scope_set_is_allowed_and_means_no_side_effect_scope_is_granted() {
    ToolDescriptor tool = calculator();
    assertThat(tool.requiredScopes()).isEmpty();
    // An empty scope set is a valid descriptor; it is the *permission check* (server-side, from the
    // verified context) that denies a side effect, not the description. A rich description never
    // grants the scope the caller lacks.
    assertThat(tool.description()).isNotEmpty();
  }

  @Test
  void value_equality_holds_for_equal_descriptors() {
    ToolDescriptor a = calculator();
    ToolDescriptor b = calculator();
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
    // A different risk class breaks equality.
    ToolDescriptor other =
        ToolDescriptor.of(
            "tool-calculator-1",
            "calculator",
            "1",
            "Evaluate a safe arithmetic expression like 2 + 3 * 4.",
            JsonSchema.of(schema("object")),
            JsonSchema.of(schema("object")),
            Set.of(),
            ToolRiskClass.WRITE,
            TIMEOUT,
            RETRY,
            false);
    assertThat(a).isNotEqualTo(other);
  }

  @Test
  void a_blank_or_null_identity_is_rejected() {
    assertThatThrownBy(
            () ->
                ToolDescriptor.of(
                    "id",
                    "   ",
                    "1",
                    "d",
                    JsonSchema.of(schema("object")),
                    JsonSchema.of(schema("object")),
                    Set.of(),
                    ToolRiskClass.READ_ONLY,
                    TIMEOUT,
                    RETRY,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(
            () ->
                ToolDescriptor.of(
                    "",
                    "calculator",
                    "1",
                    "d",
                    JsonSchema.of(schema("object")),
                    JsonSchema.of(schema("object")),
                    Set.of(),
                    ToolRiskClass.READ_ONLY,
                    TIMEOUT,
                    RETRY,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
  }

  @Test
  void a_missing_machine_readable_schema_is_rejected() {
    assertThatThrownBy(
            () ->
                ToolDescriptor.of(
                    "id",
                    "calculator",
                    "1",
                    "d",
                    null,
                    JsonSchema.of(schema("object")),
                    Set.of(),
                    ToolRiskClass.READ_ONLY,
                    TIMEOUT,
                    RETRY,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inputSchema");
    assertThatThrownBy(
            () ->
                ToolDescriptor.of(
                    "id",
                    "calculator",
                    "1",
                    "d",
                    JsonSchema.of(schema("object")),
                    null,
                    Set.of(),
                    ToolRiskClass.READ_ONLY,
                    TIMEOUT,
                    RETRY,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outputSchema");
  }

  @Test
  void a_non_positive_timeout_is_rejected() {
    assertThatThrownBy(
            () ->
                ToolDescriptor.of(
                    "id",
                    "calculator",
                    "1",
                    "d",
                    JsonSchema.of(schema("object")),
                    JsonSchema.of(schema("object")),
                    Set.of(),
                    ToolRiskClass.READ_ONLY,
                    Duration.ZERO,
                    RETRY,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeout");
    assertThatThrownBy(
            () ->
                ToolDescriptor.of(
                    "id",
                    "calculator",
                    "1",
                    "d",
                    JsonSchema.of(schema("object")),
                    JsonSchema.of(schema("object")),
                    Set.of(),
                    ToolRiskClass.READ_ONLY,
                    Duration.ofSeconds(-5),
                    RETRY,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeout");
  }

  @Test
  void a_null_risk_class_scope_or_retry_policy_is_rejected() {
    assertThatThrownBy(
            () ->
                ToolDescriptor.of(
                    "id",
                    "calculator",
                    "1",
                    "d",
                    JsonSchema.of(schema("object")),
                    JsonSchema.of(schema("object")),
                    Set.of(),
                    null,
                    TIMEOUT,
                    RETRY,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("riskClass");
    assertThatThrownBy(
            () ->
                ToolDescriptor.of(
                    "id",
                    "calculator",
                    "1",
                    "d",
                    JsonSchema.of(schema("object")),
                    JsonSchema.of(schema("object")),
                    null,
                    ToolRiskClass.READ_ONLY,
                    TIMEOUT,
                    RETRY,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requiredScopes");
    assertThatThrownBy(
            () ->
                ToolDescriptor.of(
                    "id",
                    "calculator",
                    "1",
                    "d",
                    JsonSchema.of(schema("object")),
                    JsonSchema.of(schema("object")),
                    Set.of(),
                    ToolRiskClass.READ_ONLY,
                    TIMEOUT,
                    null,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retryPolicy");
  }

  @Test
  void the_constructor_does_not_mutate_a_supplied_scope_list() {
    Set<String> mutable = new java.util.LinkedHashSet<>(List.of("tool:x"));
    ToolDescriptor tool =
        ToolDescriptor.of(
            "id",
            "calculator",
            "1",
            "d",
            JsonSchema.of(schema("object")),
            JsonSchema.of(schema("object")),
            mutable,
            ToolRiskClass.READ_ONLY,
            TIMEOUT,
            RETRY,
            false);
    mutable.add("tool:y"); // mutate the source set after building the descriptor
    assertThat(tool.requiredScopes()).containsExactly("tool:x"); // the descriptor kept its own copy
  }
}
