package com.example.agentflow.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies the S04-03 acceptance criterion through the HTTP boundary: a missing, expired, tampered
 * or mis-issued OAuth 2.0 access token is rejected with {@code 401 Unauthorized} by the secured
 * {@code SecurityFilterChain}, while a valid token is accepted (passes the security layer).
 *
 * <p>The context runs on the {@code secured-test} profile, so the <em>secured</em> chain is active
 * and the test-key {@link JwtDecoder} is the only decoder registered (the production and permissive
 * decoders are both excluded there). The test-key decoder validates the signature locally, so the
 * rejections are attributed to the real reasons (signature/expiry/issuer/audience) rather than to
 * an unreachable Keycloak.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("secured-test")
class SecurityFilterChainTest {

  @LocalServerPort private int port;

  @Autowired private AgentFlowProperties properties;

  @Autowired private RestTemplate rest;

  @Autowired private RSAKey testKey;

  private String issuer;

  private String audience;

  @BeforeEach
  void setUp() {
    issuer = properties.getSecurity().getIssuerUri();
    audience = properties.getSecurity().getAudience();
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  private HttpHeaders bearer(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  /**
   * Sends a GET to the missing API route with the given bearer token (or no authorization header
   * when {@code token} is {@code null}). Uses {@code exchange} so the {@code Authorization} header
   * is actually transmitted; {@code RestTemplate.getForEntity(url, type, args)} would otherwise
   * treat the token as a URL path variable and never send the header.
   */
  private ResponseEntity<String> authorizedGet(String token) {
    HttpHeaders headers = token == null ? new HttpHeaders() : bearer(token);
    return rest.exchange(
        baseUrl() + "/api/v1/does-not-exist",
        HttpMethod.GET,
        new HttpEntity<>(headers),
        String.class);
  }

  private String sign(RSAKey key, String tokenIssuer, String tokenAudience, Date expiresAt)
      throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(tokenIssuer)
            .audience(tokenAudience)
            .notBeforeTime(new Date(System.currentTimeMillis() - 5 * 60_000L))
            .expirationTime(expiresAt)
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static Date inFiveMinutes() {
    return new Date(System.currentTimeMillis() + 5 * 60_000L);
  }

  private static Date fiveMinutesAgo() {
    return new Date(System.currentTimeMillis() - 5 * 60_000L);
  }

  @Test
  void missingTokenIsRejected() {
    ResponseEntity<String> response =
        rest.getForEntity(baseUrl() + "/api/v1/does-not-exist", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void expiredTokenIsRejected() throws Exception {
    String token = sign(testKey, issuer, audience, fiveMinutesAgo());

    ResponseEntity<String> response = authorizedGet(token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void tokenSignedByAnotherKeyIsRejected() throws Exception {
    String token = sign(otherKey(), issuer, audience, inFiveMinutes());

    ResponseEntity<String> response = authorizedGet(token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void wrongIssuerIsRejected() throws Exception {
    String token = sign(testKey, "https://keycloak.example/realms/evil", audience, inFiveMinutes());

    ResponseEntity<String> response = authorizedGet(token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void wrongAudienceIsRejected() throws Exception {
    String token = sign(testKey, issuer, "some-other-audience", inFiveMinutes());

    ResponseEntity<String> response = authorizedGet(token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void validTokenPassesTheSecurityLayer() throws Exception {
    String token = sign(testKey, issuer, audience, inFiveMinutes());

    // The request reaches the dispatcher (a 404 for the missing route) instead of being rejected at
    // the security layer, which proves the token was accepted.
    ResponseEntity<String> response = authorizedGet(token);
    assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
  }

  /** A second, unrelated RSA key used to exercise the signature-rejection path. */
  private RSAKey otherKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
        .keyID("unrelated")
        .privateKey(keyPair.getPrivate())
        .build();
  }

  /**
   * Supplies a locally validating {@link JwtDecoder} so the signature/expiry checks run against the
   * in-memory test key instead of an unreachable Keycloak. It is {@link Primary} so it is injected
   * even if the production decoder bean is also registered.
   */
  @TestConfiguration
  static class TestKeycloak {

    @Bean
    RestTemplate restTemplate() {
      RestTemplate template = new RestTemplate();
      // No-op error handler: the secured API answers 401/404 without a body, and the tests assert
      // on the status code, so the response must surface instead of throwing.
      template.setErrorHandler(new NoOpResponseErrorHandler());
      return template;
    }

    @Bean
    RSAKey testKey() throws Exception {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair keyPair = generator.generateKeyPair();
      return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
          .keyID("agentflow-test-key")
          .algorithm(JWSAlgorithm.RS256)
          .privateKey(keyPair.getPrivate())
          .build();
    }

    @Bean
    @Primary
    @Profile("secured-test")
    JwtDecoder testJwtDecoder(RSAKey testKey, AgentFlowProperties properties) {
      NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource(testKey)).build();
      decoder.setJwtValidator(
          JwtValidators.createDefaultWithValidators(
              new JwtIssuerValidator(properties.getSecurity().getIssuerUri()),
              new JwtAudienceValidator(properties.getSecurity().getAudience())));
      return decoder;
    }

    private JWKSource<SecurityContext> jwkSource(RSAKey testKey) {
      JWKSet jwkSet = new JWKSet(List.of(testKey.toPublicJWK()));
      return new JWKSource<SecurityContext>() {
        @Override
        public List<JWK> get(JWKSelector selector, SecurityContext context)
            throws KeySourceException {
          return selector.select(jwkSet);
        }
      };
    }
  }
}
