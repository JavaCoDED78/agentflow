package com.example.agentflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.identity.security.KeycloakRealmFixture;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Real-decoder proof (S04-20): the production JWT decoder built by {@link SecurityConfiguration} —
 * the same object the secured {@code SecurityFilterChain} registers — is exercised against a real
 * Keycloak container, with a token minted by the realm through the S04-18 method. This is the
 * negative-side proof that wrong issuer, wrong audience and expired token are all rejected, plus
 * the positive proof that a valid token is accepted.
 *
 * <p>Each negative is expressed through the <em>real decoder configuration</em> — the production
 * builder, not a hand-rolled decoder — so the rejection path is the exact path production runs on:
 *
 * <ul>
 *   <li><b>wrong issuer</b>: the decoder is pointed at the <em>master</em> realm (a real, reachable
 *       issuer that is not the trusted one). The agentflow token is signed by the realm the decoder
 *       does not trust, so it is rejected.
 *   <li><b>wrong audience</b>: the decoder is configured with an audience the token does not carry.
 *       The JWK set resolves (same realm), the signature passes, and the audience validator rejects
 *       it.
 *   <li><b>expired</b>: the realm's {@code accessTokenLifespan} is shortened, a fresh token is
 *       minted, and the decoder rejects it.
 * </ul>
 *
 * <p>The fixture is per-class: the expiry proof mutates the realm's lifespan, so it must never
 * share a container with the realm-contract test. Every token is minted with the throwaway
 * test-user password — never printed, never stored.
 */
@DisplayName("Production JWT decoder against a real Keycloak (S04-20)")
class RealJwtDecoderTest {

  private static final String TENANT_A_ID = "11111111-1111-4111-8111-111111111111";

  private static KeycloakRealmFixture fixture;
  private static AgentFlowProperties properties;

  @BeforeAll
  static void startFixture() {
    fixture = new KeycloakRealmFixture();
    properties = new AgentFlowProperties();
    properties.getSecurity().setIssuerUri(fixture.issuerUri());
    properties.getSecurity().setAudience("agentflow-api");
  }

  @AfterAll
  static void stopFixture() {
    if (fixture != null) {
      fixture.close();
    }
  }

  @BeforeEach
  void ensureTenantUser() {
    fixture.createTestUser(
        "tenant-a-member",
        "tenant-a-member@agentflow.local",
        TENANT_A_ID,
        List.of("workflow:read"));
  }

  @Test
  @DisplayName(
      "a valid token is decoded by the production decoder (signature, issuer, audience, expiry all checked)")
  void validToken_accepted() {
    String token = fixture.mintUserToken("tenant-a-member");
    JwtDecoder decoder = new SecurityConfiguration(properties, null, null).jwtDecoder();

    org.springframework.security.oauth2.jwt.Jwt jwt = decoder.decode(token);

    // Compare the raw claims — the typed accessors (e.g. getAudience()) can wrap the claim, so
    // compare what
    // the realm actually minted.
    assertThat((String) jwt.getClaim("iss")).isEqualTo(fixture.issuerUri());
    // Nimbus exposes the aud claim as a List (the production Jwt.getAudience() contract).
    assertThat(jwt.getAudience()).containsExactly("agentflow-api");
    assertThat((String) jwt.getClaim("tenant_id")).isEqualTo(TENANT_A_ID);
  }

  @Test
  @DisplayName("a token minted by a realm the decoder does not trust is rejected")
  void wrongIssuer_rejected() {
    // The master realm is a real, reachable issuer in the same container — but not the trusted one.
    // The agentflow
    // token is signed by the agentflow realm; a decoder pinned to the master realm must not trust
    // it.
    AgentFlowProperties wrongIssuer = new AgentFlowProperties();
    wrongIssuer.getSecurity().setIssuerUri(fixture.baseUrl() + "/realms/master");
    wrongIssuer.getSecurity().setAudience("agentflow-api");

    String token = fixture.mintUserToken("tenant-a-member");
    JwtDecoder decoder = new SecurityConfiguration(wrongIssuer, null, null).jwtDecoder();

    assertThatThrownBy(() -> decoder.decode(token))
        .as("a token from an untrusted realm must be rejected")
        .isInstanceOf(JwtException.class);
  }

  @Test
  @DisplayName("a token whose audience does not match the configured audience is rejected")
  void wrongAudience_rejected() {
    AgentFlowProperties wrongAudience = new AgentFlowProperties();
    wrongAudience.getSecurity().setIssuerUri(fixture.issuerUri());
    wrongAudience.getSecurity().setAudience("wrong-audience");

    String token = fixture.mintUserToken("tenant-a-member");
    JwtDecoder decoder = new SecurityConfiguration(wrongAudience, null, null).jwtDecoder();

    assertThatThrownBy(() -> decoder.decode(token))
        .as("a token with a different audience must be rejected")
        .isInstanceOf(JwtException.class);
  }

  @Test
  @DisplayName("an expired token is rejected")
  void expiredToken_rejected() {
    // Shrink the realm's accessTokenLifespan, mint a fresh token, wait past its expiry, then
    // decode.
    int defaultLifespan = fixture.realmConfig().get("accessTokenLifespan").asInt();
    try {
      fixture.setAccessTokenLifespanSeconds(2);
      String token = fixture.mintUserToken("tenant-a-member");

      // The production decoder pins JwtTimestampValidator (default 60s clock skew), so a token only
      // becomes ineligible past that skew — prove the rejection with a token whose exp is beyond
      // it.
      try {
        Thread.sleep(65_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }

      JwtDecoder decoder = new SecurityConfiguration(properties, null, null).jwtDecoder();
      assertThatThrownBy(() -> decoder.decode(token))
          .as("an expired token must be rejected")
          .isInstanceOf(JwtException.class);
    } finally {
      // Restore the realm lifespan so any token minted by a sibling test in this class is not
      // silently near expiry.
      fixture.setAccessTokenLifespanSeconds(defaultLifespan);
    }
  }
}
