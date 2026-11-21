package com.example.agentflow.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * S12-18 unit tests for {@link RedisValueCodec}: the {@code 0.1} envelope round-trip, the size
 * bound and the no-secret rule.
 */
class RedisValueCodecTest {

  private final RedisValueCodec codec = new RedisValueCodec();

  @Test
  void the_version_stamp_is_zero_one() {
    assertThat(codec.encode("x").formatVersion()).isEqualTo("0.1");
  }

  @Test
  void encode_and_decode_round_trip_through_utf8() {
    RedisValue value = codec.encode("{\"state\":\"RUNNING\",\"steps\":2}");

    assertThat(codec.decode(value)).isEqualTo("{\"state\":\"RUNNING\",\"steps\":2}");
    assertThat(value.payload()).isNotEmpty();
  }

  @Test
  void a_null_content_is_rejected() {
    assertThatThrownBy(() -> codec.encode(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }

  @Test
  void a_null_value_is_rejected_on_decode() {
    assertThatThrownBy(() -> codec.decode(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }

  @Test
  void a_value_above_the_byte_bound_is_rejected() {
    assertThatThrownBy(() -> codec.encode("x".repeat(1025)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bytes");
  }

  @Test
  void a_value_carrying_a_secret_is_rejected() {
    assertThatThrownBy(() -> codec.encode("Bearer abc.def.ghi"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("secret");
  }

  @Test
  void a_value_carrying_a_json_api_key_is_rejected() {
    assertThatThrownBy(() -> codec.encode("{\"api_key\":\"deadbeef\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("secret");
  }

  @Test
  void the_envelope_bytes_are_a_defensive_copy() {
    RedisValue value = codec.encode("hello");
    byte[] bytes = value.payload();
    bytes[0] = (byte) 'X';

    assertThat(codec.decode(value)).isEqualTo("hello");
  }

  @Test
  void the_encoded_bytes_are_utf8_for_ascii() {
    RedisValue value = codec.encode("ok");

    assertThat(value.payload()).isEqualTo("ok".getBytes(StandardCharsets.UTF_8));
  }
}
