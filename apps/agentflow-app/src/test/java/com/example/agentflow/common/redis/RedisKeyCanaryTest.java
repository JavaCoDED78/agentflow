package com.example.agentflow.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * S12-18 canary guard: a planted redis-key canary (which stands in for a real connection password
 * or any secret a developer might accidentally route through Redis) is <b>never written into a
 * key</b>. The canary value is read from the sanctioned test-classpath fixture, so this class is
 * not itself a place the canary scan would flag (see {@code RedisPasswordLeakTest}).
 *
 * <p>Two independent facts are proven:
 *
 * <ul>
 *   <li>the canary, used as a suffix segment, is rejected by the builder — it cannot become part of
 *       a key;
 *   <li>a legitimately built key never contains the canary.
 * </ul>
 */
class RedisKeyCanaryTest {

  private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-000000000001");
  private static final String CANARY_RESOURCE = "/secrets/canary-redis-password.txt";

  private final RedisKeyBuilder builder = new RedisKeyBuilder("local");

  /**
   * @return the canary, read from the sanctioned fixture (never hardcoded in source)
   */
  private String canary() {
    try (InputStream in = getClass().getResourceAsStream(CANARY_RESOURCE)) {
      assertThat(in).as("the canary fixture must be on the test classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read the canary fixture", exception);
    }
  }

  @Test
  void the_canary_lives_only_in_the_sanctioned_fixture() {
    assertThat(canary()).isNotBlank();
  }

  @Test
  void the_canary_is_rejected_as_a_key_segment_so_it_never_leaves_the_fixture() {
    String canary = canary();
    RedisKeySpec spec =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT).tenantId(TENANT).suffix(canary).build();

    // The canary is a long identifier-like token; the segment guard rejects it, so it cannot enter
    // a
    // key. The rejection must not echo the canary back into the message.
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> builder.build(spec));
    assertThat(ex.getMessage()).contains("segment");
    assertThat(ex.getMessage()).doesNotContain(canary());
  }

  @Test
  void a_legitimately_built_key_never_carries_the_canary() {
    String canary = canary();
    RedisKeySpec spec =
        RedisKeySpec.builder(RedisPurpose.RATE_LIMIT)
            .tenantId(TENANT)
            .suffix("create-run", "principal")
            .build();

    RedisKey key = builder.build(spec);

    assertThat(key.value()).doesNotContain(canary());
  }
}
