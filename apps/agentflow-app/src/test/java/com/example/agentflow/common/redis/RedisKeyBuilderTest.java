package com.example.agentflow.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * S12-18 unit tests for {@link RedisKeyBuilder}: the key format, the closed {@link RedisPurpose}
 * set, the tenant-scoping rule and the no-secret rule. Every failure is an {@link
 * IllegalArgumentException}; the builder never echoes a rejected segment into its message.
 */
class RedisKeyBuilderTest {

  private static final UUID TENANT_1 = UUID.fromString("11111111-0000-0000-0000-000000000001");
  private static final UUID TENANT_2 = UUID.fromString("22222222-0000-0000-0000-000000000002");

  private final RedisKeyBuilder builder = new RedisKeyBuilder("local");

  @Test
  void the_key_matches_the_agentflow_namespace_and_purposes() {
    RedisKeySpec spec =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT)
            .tenantId(TENANT_1)
            .suffix("create-run", "principal-1")
            .build();

    RedisKey key = builder.build(spec);

    assertThat(key.value())
        .isEqualTo(
            "agentflow:local:ratelimit:11111111-0000-0000-0000-000000000001:create-run:principal-1");
    assertThat(key.purpose()).isEqualTo(RedisPurpose.RATE_LIMIT);
    assertThat(key.tenantId()).isEqualTo(TENANT_1);
    assertThat(key.suffixParts()).containsExactly("create-run", "principal-1");
  }

  @Test
  void the_cache_purpose_uses_its_wire_name_in_the_namespace() {
    RedisKeySpec spec =
        RedisKeySpec.builder(RedisPurpose.CACHE)
            .tenantId(TENANT_1)
            .suffix("run", TENANT_1.toString())
            .build();

    RedisKey key = builder.build(spec);

    assertThat(key.value())
        .isEqualTo(
            "agentflow:local:cache:11111111-0000-0000-0000-000000000001:run:11111111-0000-0000-0000-000000000001");
    assertThat(key.value()).startsWith("agentflow:local:cache:");
  }

  @Test
  void cross_tenant_keys_differ_inside_the_namespace_and_never_overlap() {
    RedisKeySpec spec1 =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT)
            .tenantId(TENANT_1)
            .suffix("create-run", "p")
            .build();
    RedisKeySpec spec2 =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT)
            .tenantId(TENANT_2)
            .suffix("create-run", "p")
            .build();

    RedisKey key1 = builder.build(spec1);
    RedisKey key2 = builder.build(spec2);

    // The tenantId sits in the namespace, so neither key string contains the other's tenant id: a
    // consumer can never match the wrong tenant's value, and a missing key is indistinguishable
    // from
    // another tenant's.
    assertThat(key1.value()).isNotEqualTo(key2.value());
    assertThat(key1.value()).doesNotContain(TENANT_2.toString());
    assertThat(key2.value()).doesNotContain(TENANT_1.toString());
    assertThat(key1.value()).contains(TENANT_1.toString());
    assertThat(key2.value()).contains(TENANT_2.toString());
  }

  @Test
  void an_unknown_purpose_is_rejected_by_the_purpose_resolver() {
    assertThatThrownBy(() -> RedisPurpose.fromWire("workflow-state"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allowed purposes are");
  }

  @Test
  void a_tenant_scoped_purpose_without_a_tenant_id_is_rejected() {
    RedisKeySpec spec =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT).suffix("create-run", "p").build();

    assertThatThrownBy(() -> builder.build(spec))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId");
  }

  @Test
  void a_suffix_segment_with_whitespace_is_rejected() {
    RedisKeySpec spec =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT).tenantId(TENANT_1).suffix("a b").build();

    assertThatThrownBy(() -> builder.build(spec))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("whitespace");
  }

  @Test
  void a_suffix_segment_longer_than_the_bound_is_rejected() {
    RedisKeySpec spec =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT)
            .tenantId(TENANT_1)
            .suffix("x".repeat(41))
            .build();

    assertThatThrownBy(() -> builder.build(spec))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("segment");
  }

  @Test
  void a_suffix_segment_carrying_a_secret_is_rejected() {
    RedisKeySpec spec =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT)
            .tenantId(TENANT_1)
            .suffix("api_key=deadbeef")
            .build();

    assertThatThrownBy(() -> builder.build(spec))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("secret");
  }

  @Test
  void a_suffix_segment_carrying_a_json_api_key_is_rejected() {
    RedisKeySpec spec =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT)
            .tenantId(TENANT_1)
            .suffix("{\"token\":\"deadbeef\"}")
            .build();

    assertThatThrownBy(() -> builder.build(spec))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("secret");
  }

  @Test
  void the_assembled_key_above_the_bound_is_rejected_without_escaping_the_guard() {
    // Many long suffix segments: the assembled key exceeds the key-length bound.
    RedisKeySpec spec =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT)
            .tenantId(TENANT_1)
            .suffix("s".repeat(40), "t".repeat(40), "u".repeat(40), "v".repeat(40))
            .build();

    assertThatThrownBy(() -> builder.build(spec))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("characters");
  }

  @Test
  void an_overlong_environment_tag_is_rejected() {
    assertThatThrownBy(
            () -> new RedisKeyBuilder("an-environment-tag-that-is-very-long-exceeding-24"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("environment tag");
  }

  @Test
  void a_valid_key_builds_within_the_bounds() {
    RedisKeySpec spec =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT)
            .tenantId(TENANT_1)
            .suffix("create-run", "principal-1", "segment")
            .build();

    RedisKey key = builder.build(spec);

    assertThat(key.value()).doesNotContain("*").hasSizeLessThanOrEqualTo(200);
    assertThat(builder.env()).isEqualTo("local");
  }
}
