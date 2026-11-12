package com.example.agentflow.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the configured-name mapping of the redelivery policy (S10-13): the configured name
 * maps to the enum, and an unknown name fails loudly (never silently defaulting).
 */
class RedeliveryPolicyTest {

  @Test
  void the_configured_names_map_to_the_policies() {
    assertThat(RedeliveryPolicy.fromConfig("skip")).isEqualTo(RedeliveryPolicy.SKIP);
    assertThat(RedeliveryPolicy.fromConfig("update")).isEqualTo(RedeliveryPolicy.UPDATE);
    assertThat(RedeliveryPolicy.fromConfig("SKIP")).isEqualTo(RedeliveryPolicy.SKIP);
  }

  @Test
  void the_policies_know_whether_they_overwrite_on_conflict() {
    assertThat(RedeliveryPolicy.SKIP.updatesOnConflict()).isFalse();
    assertThat(RedeliveryPolicy.UPDATE.updatesOnConflict()).isTrue();
  }

  @Test
  void an_unknown_policy_name_fails_loudly() {
    assertThatThrownBy(() -> RedeliveryPolicy.fromConfig("merge"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("merge");
  }
}
