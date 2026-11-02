package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CheckpointRedactor}: the sensitive-field rules S07-07 applies to a step
 * output before it is stored inline (invariant 8 — secrets never land in a row).
 */
class CheckpointRedactorTest {

  @Test
  void redacts_a_bearer_authorization_header() {
    String raw = "GET /v1/data\nAuthorization: Bearer sk-live-ABCdef123456GHI";
    assertThat(CheckpointRedactor.redact(raw))
        .isEqualTo("GET /v1/data\nAuthorization: Bearer " + CheckpointRedactor.REDACTED);
  }

  @Test
  void redacts_a_basic_authorization_header() {
    String raw = "Authorization: Basic c2NlZXI6cGFzc3dvcmQ=";
    assertThat(CheckpointRedactor.redact(raw))
        .isEqualTo("Authorization: Basic " + CheckpointRedactor.REDACTED);
  }

  @Test
  void redacts_secret_json_values() {
    String raw = "{\"api_key\": \"abc123\", \"access_token\": \"xyz\", \"username\": \"ok\"}";
    String redacted = CheckpointRedactor.redact(raw);
    assertThat(redacted).contains("\"api_key\": \"" + CheckpointRedactor.REDACTED + "\"");
    assertThat(redacted).contains("\"access_token\": \"" + CheckpointRedactor.REDACTED + "\"");
    // a non-secret field is preserved
    assertThat(redacted).contains("\"username\": \"ok\"");
  }

  @Test
  void redacts_a_social_security_number() {
    assertThat(CheckpointRedactor.redact("ssn=123-45-6789 end")).isEqualTo("ssn=[REDACTED] end");
  }

  @Test
  void redacts_a_credit_card_number() {
    String raw = "card 4111 1111 1111 1111 ok";
    assertThat(CheckpointRedactor.redact(raw)).isEqualTo("card [REDACTED] ok");
  }

  @Test
  void redacts_a_secret_http_header_and_keeps_other_headers() {
    String raw = "X-Api-Key: top-secret, X-Trace: keep-me";
    String redacted = CheckpointRedactor.redact(raw);
    assertThat(redacted).contains("X-Trace: keep-me");
    assertThat(redacted).contains("X-Api-Key: " + CheckpointRedactor.REDACTED);
    assertThat(redacted).doesNotContain("top-secret");
  }

  @Test
  void keeps_a_partial_email_for_attribution() {
    assertThat(CheckpointRedactor.redact("mail alice@corp.example today"))
        .isEqualTo("mail alice@… today");
  }

  @Test
  void is_idempotent_re_redaction_leaves_the_output_unchanged() {
    String once = CheckpointRedactor.redact("Authorization: Bearer tok\nssn 999-88-7777");
    String twice = CheckpointRedactor.redact(once);
    assertThat(twice).isEqualTo(once);
  }

  @Test
  void passes_clean_text_through_untouched() {
    assertThat(CheckpointRedactor.redact("calculator result: 42"))
        .isEqualTo("calculator result: 42");
  }

  @Test
  void redacts_null_input_to_null() {
    assertThat(CheckpointRedactor.redact(null)).isNull();
  }
}
