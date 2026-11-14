package com.example.agentflow.workflowexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The {@link ApprovalStatus} vocabulary (S11-04) mirrors the {@code approvals_status_check} bound
 * in storage ({@code V3_3_22__create_approvals.sql}), so {@code valueOf(row.status())} is safe on
 * every row the table accepts. This test pins the vocabulary — a drift between the enum and the
 * CHECK is a schema change, not an input.
 */
class ApprovalStatusTest {

  @Test
  void the_enum_mirrors_the_storage_check_vocabulary() {
    assertThat(ApprovalStatus.values())
        .extracting(Enum::name)
        .containsExactly("PENDING", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED");
  }

  @Test
  void value_of_round_trips_every_status_token() {
    for (ApprovalStatus status : ApprovalStatus.values()) {
      assertThat(ApprovalStatus.valueOf(status.name())).isSameAs(status);
    }
  }
}
