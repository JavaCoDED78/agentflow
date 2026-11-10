package com.example.agentflow.knowledgebase.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link DocumentUploadLimits} value object ({@code S10-03}). DB-free: pure
 * value.
 */
class DocumentUploadLimitsTest {

  @Test
  void the_values_round_trip_through_the_accessors() {
    DocumentUploadLimits limits = new DocumentUploadLimits(2048, 128, 120);

    assertThat(limits.getMaxBytes()).isEqualTo(2048);
    assertThat(limits.getMaxFileNameLength()).isEqualTo(128);
    assertThat(limits.getMaxMediaDurationSeconds()).isEqualTo(120);
  }

  @Test
  void two_limits_with_the_same_values_are_equal() {
    DocumentUploadLimits a = new DocumentUploadLimits(2048, 128, 120);
    DocumentUploadLimits b = new DocumentUploadLimits(2048, 128, 120);

    assertThat(a).isEqualTo(b);
    assertThat(a).hasSameHashCodeAs(b);
  }

  @Test
  void a_limit_that_differs_in_one_field_is_not_equal() {
    DocumentUploadLimits a = new DocumentUploadLimits(2048, 128, 120);
    DocumentUploadLimits b = new DocumentUploadLimits(2048, 128, 121);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void a_non_positive_limit_is_rejected() {
    assertThatThrownBy(() -> new DocumentUploadLimits(0, 128, 120))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxBytes");
    assertThatThrownBy(() -> new DocumentUploadLimits(2048, 0, 120))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxFileNameLength");
    assertThatThrownBy(() -> new DocumentUploadLimits(2048, 128, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxMediaDurationSeconds");
  }
}
