package com.example.agentflow.toolexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.toolexecution.domain.JsonSchema;
import com.example.agentflow.toolexecution.domain.RetryPolicy;
import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for the tool-risk-classification policy (S11-01): <em>a new tool is not
 * available until it is classified</em>.
 *
 * <p>Availability is defined by the {@link ToolRegistry} — the single source of <em>which tools are
 * registered</em> — and the registry is the only place a tool becomes callable (the Planner and the
 * permission guard both resolve a tool through it). The policy therefore reduces to a property of
 * that boundary: <b>the registry can never expose a tool that is not classified</b>. The property
 * holds because classification is enforced <em>before</em> a descriptor can be registered, in two
 * layers that together close the seam:
 *
 * <ul>
 *   <li>The <b>vocabulary is closed</b> ({@link ToolRiskClass}): an unknown risk class cannot be
 *       represented, so a descriptor can never carry an unclassified value (see {@code
 *       ToolRiskClassTest}).
 *   <li>The <b>descriptor refuses an unclassified value</b> at construction ({@link
 *       ToolDescriptor}): building a descriptor with a {@code null} risk class is a contract
 *       failure, so it cannot be registered.
 * </ul>
 *
 * <p>This suite states the property in one place, at the availability boundary, so the S11-01
 * acceptance criterion ("a new tool is not available until classified; an unknown risk class is
 * denied by default") is verified by a single, readable assertion set. DB-free: the registry and
 * the descriptor are pure values (no Spring, no database, no model, no clock).
 */
class ToolRiskClassificationAvailabilityTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final RetryPolicy RETRY = RetryPolicy.ofDefaults();

  private static Map<String, Object> schema() {
    return Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "type", "object");
  }

  private static ToolDescriptor of(ToolRiskClass riskClass) {
    return ToolDescriptor.of(
        "tool-" + riskClass.name().toLowerCase(),
        "classified-" + riskClass.name().toLowerCase(),
        "1",
        "A " + riskClass + " tool.",
        JsonSchema.of(schema()),
        JsonSchema.of(schema()),
        Set.of(),
        riskClass,
        TIMEOUT,
        RETRY,
        false);
  }

  @Test
  void a_tool_is_available_for_each_of_the_three_closed_risk_classes() {
    ToolRegistry registry = new ToolRegistry();
    ToolDescriptor readOnly = of(ToolRiskClass.READ_ONLY);
    ToolDescriptor write = of(ToolRiskClass.WRITE);
    ToolDescriptor external = of(ToolRiskClass.EXTERNAL_SIDE_EFFECT);
    registry.register(readOnly);
    registry.register(write);
    registry.register(external);

    // Every classified tool is registered (size counts the distinct (name, version) keys),
    // resolves, and carries its known risk class. Resolve by the descriptor's own name/version
    // so the assertion depends only on the closed vocabulary, never on a spelling I hardcoded.
    assertThat(registry.size()).isEqualTo(3);
    assertThat(registry.resolve(readOnly.name(), readOnly.version())).isPresent();
    assertThat(registry.resolve(write.name(), write.version())).isPresent();
    assertThat(registry.resolve(external.name(), external.version())).isPresent();
    registry.all().forEach(descriptor -> assertThat(descriptor.riskClass()).isNotNull());
  }

  @Test
  void an_unclassified_descriptor_is_refused_before_it_can_be_registered() {
    ToolRegistry registry = new ToolRegistry();

    // The descriptor constructor rejects an unclassified tool (a null risk class) with a contract
    // failure naming the field, so the value never reaches the registry — this is the "unknown
    // risk class is denied by default" seam at the availability boundary.
    assertThatThrownBy(
            () ->
                ToolDescriptor.of(
                    "tool-unclassified-1",
                    "unclassified",
                    "1",
                    "A tool with no risk class.",
                    JsonSchema.of(schema()),
                    JsonSchema.of(schema()),
                    Set.of(),
                    null,
                    TIMEOUT,
                    RETRY,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("riskClass");

    // Nothing registered: the registry exposes no unclassified tool.
    assertThat(registry.size()).isZero();
    assertThat(registry.resolve("unclassified", "1")).isEmpty();
  }
}
