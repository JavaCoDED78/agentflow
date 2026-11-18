package com.example.agentflow.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for {@link SecretRedactor}: the single set of redaction rules shared by the
 * log path, the audit-metadata write and the DLQ-description write (S12-09; ADR-020).
 */
class SecretRedactorTest {

  private static final String BEARER_SECRET = "abcdef123456";
  private static final String AWS_KEY = "AKIA" + "ABCDEFGH01234567";
  private static final String API_KEY_VALUE = "sk-proj-abc123xyz";

  @Test
  void redact_passes_through_null() {
    assertThat(SecretRedactor.redact(null)).isNull();
  }

  @Test
  void plain_text_without_secrets_is_unchanged() {
    String input = "run 12 reached step ingest at 14:30 UTC";
    assertThat(SecretRedactor.redact(input)).isEqualTo(input);
  }

  @Test
  void bearer_token_is_masked_even_when_embedded_in_a_larger_message() {
    String input = "calling model with Bearer " + BEARER_SECRET + " before the call";
    String masked = SecretRedactor.redact(input);
    assertThat(masked).doesNotContain(BEARER_SECRET);
    assertThat(masked).contains("Bearer ***");
    assertThat(masked).contains("before the call");
  }

  @Test
  void api_key_equals_value_is_masked() {
    String input = "provider api_key=" + API_KEY_VALUE;
    String masked = SecretRedactor.redact(input);
    assertThat(masked).doesNotContain(API_KEY_VALUE);
    assertThat(masked).isEqualTo("provider api_key=***");
  }

  @Test
  void json_object_key_in_double_quotes_is_masked() {
    String input = "{\"api_key\": \"" + API_KEY_VALUE + "\"}";
    String masked = SecretRedactor.redact(input);
    assertThat(masked).doesNotContain(API_KEY_VALUE);
    assertThat(masked).isEqualTo("{\"api_key\": ***}");
  }

  @Test
  void camel_case_apikey_is_masked() {
    String input = "apiKey=" + API_KEY_VALUE;
    assertThat(SecretRedactor.redact(input)).isEqualTo("apiKey=***");
  }

  @Test
  void aws_access_key_is_masked() {
    String input = "assigned role arn:aws:iam::1:access/" + AWS_KEY;
    String masked = SecretRedactor.redact(input);
    assertThat(masked).doesNotContain(AWS_KEY);
    assertThat(masked).contains("AKIA***");
  }

  @Test
  void password_equals_value_is_masked() {
    String input = "oauth password=" + API_KEY_VALUE;
    assertThat(SecretRedactor.redact(input)).isEqualTo("oauth password=***");
  }

  @Test
  void full_prompt_value_is_masked_not_appended() {
    String input = "document=the full prompt body must not reach the log";
    String masked = SecretRedactor.redact(input);
    assertThat(masked).doesNotContain("the full prompt body must not reach the log");
    assertThat(masked).isEqualTo("document=***");
  }

  @Test
  void multiple_secrets_in_one_value_are_all_masked() {
    String input = "Bearer " + BEARER_SECRET + " and api_key=" + API_KEY_VALUE;
    String masked = SecretRedactor.redact(input);
    assertThat(masked).doesNotContain(BEARER_SECRET);
    assertThat(masked).doesNotContain(API_KEY_VALUE);
  }
}
