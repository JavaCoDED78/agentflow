package com.example.agentflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit proof for S01-19: {@link SecretRedactingValueMasker} redacts secret-like values. */
class SecretRedactingValueMaskerTest {

  private final SecretRedactingValueMasker masker = new SecretRedactingValueMasker();

  @Test
  void masksBearerTokenInsideAMessage() {
    String masked = (String) masker.mask(null, "Authenticating with token Bearer abc.def.ghi here");
    assertThat(masked).isEqualTo("Authenticating with token Bearer *** here");
  }

  @Test
  void masksApiKeyPair() {
    assertThat(masker.mask(null, "api_key=sk_test_123456")).isEqualTo("api_key=***");
    assertThat(masker.mask(null, "password: s3cr3t")).isEqualTo("password: ***");
  }

  @Test
  void masksBearerKeyPair() {
    // bearer=<token> (key=value form) is masked the same way as "Bearer <token>".
    assertThat(masker.mask(null, "Authorization: bearer=eyJhbGc.payload.sig"))
        .isEqualTo("Authorization: bearer=***");
  }

  @Test
  void masksAwsAccessKeyId() {
    assertThat(masker.mask(null, "AKIAIOSFODNN7EXAMPLE")).isEqualTo("AKIA***");
  }

  @Test
  void leavesNonSecretValuesUntouched() {
    assertThat(masker.mask(null, "Request completed in 42ms"))
        .isEqualTo("Request completed in 42ms");
    assertThat(masker.mask(null, "tenantId=run-12345")).isEqualTo("tenantId=run-12345");
  }

  @Test
  void leavesNullAndNonStringsUntouched() {
    assertThat(masker.mask(null, (Object) null)).isNull();
    assertThat(masker.mask(null, 42)).isEqualTo(42);
    // TokenStreamContext is only context metadata and is ignored by the redaction;
    // a null context is accepted.
    assertThat(masker.mask(null, "Bearer x.y.z")).isEqualTo("Bearer ***");
  }
}
