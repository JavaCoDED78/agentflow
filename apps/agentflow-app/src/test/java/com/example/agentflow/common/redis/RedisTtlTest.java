package com.example.agentflow.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * S12-18 unit tests for {@link RedisTtl}: it is always positive and never above the {@code max}, so
 * a write without a TTL is impossible and no caller can pin a value forever.
 */
class RedisTtlTest {

  @Test
  void a_positive_ttl_within_the_bound_is_accepted() {
    assertThat(RedisTtl.of(60).duration()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void the_max_ttl_is_accepted() {
    assertThat(RedisTtl.of(RedisTtl.MAX_SECONDS).duration()).isEqualTo(Duration.ofSeconds(300));
  }

  @Test
  void a_zero_ttl_is_rejected() {
    assertThatThrownBy(() -> RedisTtl.of(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void a_negative_ttl_is_rejected() {
    assertThatThrownBy(() -> RedisTtl.of(-5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void a_ttl_above_the_bound_is_rejected() {
    assertThatThrownBy(() -> RedisTtl.of(RedisTtl.MAX_SECONDS + 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most");
  }

  @Test
  void a_duration_ttl_above_the_bound_is_rejected() {
    assertThatThrownBy(() -> RedisTtl.of(Duration.ofSeconds(400)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most");
  }

  @Test
  void a_null_duration_is_rejected() {
    assertThatThrownBy(() -> RedisTtl.of((Duration) null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }
}
