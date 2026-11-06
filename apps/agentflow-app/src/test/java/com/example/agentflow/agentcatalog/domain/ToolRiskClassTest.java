package com.example.agentflow.agentcatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ToolRiskClass} vocabulary (S08-01).
 *
 * <p>They cover the acceptance criterion for the risk metadata: the enum holds exactly the three
 * closed blast-radius values, and an unknown classification cannot be represented (the enum can
 * only hold these three, so any classification that does not resolve to one of them is rejected —
 * the mechanism behind "unknown risk class is denied by default", {@code S11-01}).
 */
class ToolRiskClassTest {

  @Test
  void holds_exactly_the_three_closed_blast_radius_values() {
    assertThat(ToolRiskClass.values())
        .containsExactlyInAnyOrder(
            ToolRiskClass.READ_ONLY, ToolRiskClass.WRITE, ToolRiskClass.EXTERNAL_SIDE_EFFECT);
    assertThat(ToolRiskClass.values()).hasSize(3);
  }

  @Test
  void the_values_order_by_increasing_blast_radius() {
    // READ_ONLY is the least powerful, EXTERNAL_SIDE_EFFECT the most — used only to compare how
    // much
    // trust a tool needs, never to expand the allowlist.
    assertThat(ToolRiskClass.READ_ONLY.ordinal()).isLessThan(ToolRiskClass.WRITE.ordinal());
    assertThat(ToolRiskClass.WRITE.ordinal())
        .isLessThan(ToolRiskClass.EXTERNAL_SIDE_EFFECT.ordinal());
  }

  @Test
  void value_of_resolves_the_canonical_spelling() {
    assertThat(ToolRiskClass.valueOf("READ_ONLY")).isEqualTo(ToolRiskClass.READ_ONLY);
    assertThat(ToolRiskClass.valueOf("WRITE")).isEqualTo(ToolRiskClass.WRITE);
    assertThat(ToolRiskClass.valueOf("EXTERNAL_SIDE_EFFECT"))
        .isEqualTo(ToolRiskClass.EXTERNAL_SIDE_EFFECT);
  }

  @Test
  void an_unknown_risk_class_cannot_be_represented_and_is_rejected() {
    // The enum can only hold the three values, so an unknown classification is rejected before the
    // tool is registered/called — this is the "unknown risk class is denied by default" seam.
    assertThatThrownBy(() -> ToolRiskClass.valueOf("NO_OP"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ToolRiskClass.valueOf("ADMIN"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
