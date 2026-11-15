package com.example.agentflow.workflowexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for {@link ApprovalPayloadFingerprint} (S11-06). The digest is the binding between
 * a human decision and the exact gated payload, so the properties that matter are canonical
 * stability (the same logical config digests identically regardless of map insertion order —
 * including nested argument maps), sensitivity (any argument change yields a different digest), and
 * the contract guards (only plain JSON value types are accepted).
 */
class ApprovalPayloadFingerprintTest {

  @Test
  void the_same_config_digests_identically_regardless_of_insertion_order() {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("action", "Send the monthly financial report");
    first.put("tool", "email.send");
    Map<String, Object> firstArgs = new LinkedHashMap<>();
    firstArgs.put("to", "finance@acme.example");
    firstArgs.put("subject", "Monthly report");
    first.put("arguments", firstArgs);

    Map<String, Object> second = new LinkedHashMap<>();
    Map<String, Object> secondArgs = new LinkedHashMap<>();
    secondArgs.put("subject", "Monthly report");
    secondArgs.put("to", "finance@acme.example");
    second.put("arguments", secondArgs);
    second.put("tool", "email.send");
    second.put("action", "Send the monthly financial report");

    assertThat(ApprovalPayloadFingerprint.compute("send-report", first))
        .isEqualTo(ApprovalPayloadFingerprint.compute("send-report", second));
  }

  @Test
  void nested_argument_maps_digest_identically_regardless_of_their_insertion_order() {
    Map<String, Object> ordered = Map.of("arguments", Map.of("a", 1, "b", 2, "c", 3));
    Map<String, Object> shuffled = new LinkedHashMap<>();
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("c", 3);
    nested.put("a", 1);
    nested.put("b", 2);
    shuffled.put("arguments", nested);

    assertThat(ApprovalPayloadFingerprint.compute("step", ordered))
        .isEqualTo(ApprovalPayloadFingerprint.compute("step", shuffled));
  }

  @Test
  void a_changed_argument_yields_a_different_fingerprint() {
    Map<String, Object> original =
        Map.of("tool", "email.send", "arguments", Map.of("to", "finance@acme.example"));
    Map<String, Object> tampered =
        Map.of("tool", "email.send", "arguments", Map.of("to", "attacker@evil.example"));

    assertThat(ApprovalPayloadFingerprint.compute("send-report", original))
        .isNotEqualTo(ApprovalPayloadFingerprint.compute("send-report", tampered));
  }

  @Test
  void a_changed_step_yields_a_different_fingerprint() {
    Map<String, Object> config = Map.of("tool", "email.send");

    assertThat(ApprovalPayloadFingerprint.compute("send-report", config))
        .isNotEqualTo(ApprovalPayloadFingerprint.compute("send-archive", config));
  }

  @Test
  void the_fingerprint_is_the_lowercase_hex_sha256_of_the_canonical_json() {
    String fingerprint = ApprovalPayloadFingerprint.compute("step", Map.of("tool", "t"));

    assertThat(fingerprint).hasSize(64).containsPattern("[0-9a-f]{64}");
    // the digest pins the canonical form itself: stable, verifiable, never the payload
    String expectedCanonical = "{\"step\":\"step\",\"config\":{\"tool\":\"t\"}}";
    assertThat(fingerprint)
        .isEqualTo(com.example.agentflow.common.Fingerprint.sha256Hex(expectedCanonical));
  }

  @Test
  void strings_escape_canically() {
    Map<String, Object> withEscapes =
        Map.of("arguments", Map.of("body", "line1\nline2 \"quoted\" \\ end"));
    String fingerprint = ApprovalPayloadFingerprint.compute("step", withEscapes);

    String expectedCanonical =
        "{\"step\":\"step\",\"config\":{\"arguments\":{\"body\":\"line1\\nline2"
            + " \\\"quoted\\\" \\\\ end\"}}}";
    assertThat(fingerprint)
        .isEqualTo(com.example.agentflow.common.Fingerprint.sha256Hex(expectedCanonical));
  }

  @Test
  void lists_keep_their_order_and_nulls_digest_as_json_null() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("tags", List.of("a", "b"));
    config.put("absent", null);

    String fingerprint = ApprovalPayloadFingerprint.compute("step", config);
    String expectedCanonical =
        "{\"step\":\"step\",\"config\":{\"absent\":null,\"tags\":[\"a\",\"b\"]}}";
    assertThat(fingerprint)
        .isEqualTo(com.example.agentflow.common.Fingerprint.sha256Hex(expectedCanonical));
  }

  @Test
  void numbers_digest_by_their_canonical_form() {
    Map<String, Object> config = Map.of("limit", 3, "ratio", 0.5, "price", new BigDecimal("1.00"));

    String fingerprint = ApprovalPayloadFingerprint.compute("step", config);
    String expectedCanonical =
        "{\"step\":\"step\",\"config\":{\"limit\":3,\"price\":1,\"ratio\":0.5}}";
    assertThat(fingerprint)
        .isEqualTo(com.example.agentflow.common.Fingerprint.sha256Hex(expectedCanonical));
  }

  @Test
  void a_blank_step_key_or_a_null_config_is_a_contract_failure() {
    assertThatThrownBy(() -> ApprovalPayloadFingerprint.compute(" ", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalPayloadFingerprint.compute("step", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_non_json_config_value_is_a_contract_failure() {
    Map<String, Object> config = Map.of("handler", new Object());

    assertThatThrownBy(() -> ApprovalPayloadFingerprint.compute("step", config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plain JSON values");
  }

  @Test
  @SuppressWarnings("unchecked")
  void a_non_string_map_key_is_a_contract_failure() {
    Map<String, Object> config = new LinkedHashMap<>();
    ((Map<Object, Object>) (Map<?, ?>) config).put(42, "value");

    assertThatThrownBy(() -> ApprovalPayloadFingerprint.compute("step", config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keys must be strings");
  }
}
