package com.example.agentflow.observability;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * S13-02 guard: the Prometheus scrape endpoint is exposed but stays internal-network-only.
 *
 * <p>The endpoint is deliberately <em>not</em> in the permit-all diagnostics set (it is not a
 * probe): on the {@code secured-test} chain it is behind the default-deny {@code
 * anyRequest().authenticated()}, so an external anonymous client gets {@code 401}, while a scrape
 * carrying a bearer token (the Prometheus server, from the internal network) gets a {@code 200}
 * with the JVM and HTTP-server metrics. The health/info probes remain public.
 *
 * <p>The context runs on the {@code secured-test} profile, so the <em>secured</em> chain is active
 * and the test-key {@link JwtDecoder} is the only decoder registered. The app boots without a
 * database, so the DB-pool (hikaricp) meters are not on the scrape here — the HTTP/JVM half of the
 * S13-02 criterion is what this test pins; DB-pool metrics ride the same auto-configuration and are
 * exercised by the profiles that wire a real DataSource.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "agentflow.http.port=0")
@ActiveProfiles("secured-test")
class PrometheusMetricsEndpointTest {

  @LocalServerPort private int port;

  @Autowired private RestTemplate rest;

  @Autowired private RSAKey testKey;

  @Autowired private AgentFlowProperties properties;

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

  private String sign(Date expiresAt) throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .notBeforeTime(new Date(System.currentTimeMillis() - 5 * 60_000L))
            .expirationTime(expiresAt)
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    jwt.sign(new RSASSASigner(testKey));
    return jwt.serialize();
  }

  private HttpHeaders bearer(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  @Test
  void anonymous_scrape_is_rejected() {
    HttpHeaders headers = new HttpHeaders();

    var response =
        rest.exchange(
            baseUrl() + "/actuator/prometheus",
            org.springframework.http.HttpMethod.GET,
            new org.springframework.http.HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void an_authenticated_scrape_carries_the_jvm_and_http_metrics() throws Exception {
    // Warm-up: a probe request so the http.server.requests observation exists before the scrape.
    rest.getForEntity(baseUrl() + "/actuator/health", String.class);

    String token = sign(new Date(System.currentTimeMillis() + 5 * 60_000L));
    var response =
        rest.exchange(
            baseUrl() + "/actuator/prometheus",
            org.springframework.http.HttpMethod.GET,
            new org.springframework.http.HttpEntity<>(bearer(token)),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType().toString()).contains("text/plain");
    String body = response.getBody() == null ? "" : response.getBody();
    // JVM and HTTP-server (tomcat) metrics: the S13-02 criterion, over a real scrape.
    assertThat(body).contains("jvm_memory_used_bytes");
    assertThat(body).contains("http_server_requests");
    // The common `application` tag is the one bounded identity tag the S13-01 standards require.
    assertThat(body).contains("application=\"agentflow-app\"");
  }

  @Test
  void the_scrape_does_not_carry_the_disabled_instrument_groups() throws Exception {
    rest.getForEntity(baseUrl() + "/actuator/health", String.class);

    String token = sign(new Date(System.currentTimeMillis() + 5 * 60_000L));
    var response =
        rest.exchange(
            baseUrl() + "/actuator/prometheus",
            org.springframework.http.HttpMethod.GET,
            new org.springframework.http.HttpEntity<>(bearer(token)),
            String.class);

    String body = response.getBody() == null ? "" : response.getBody();
    // The unneeded groups are off: lettuce command latency, logback event counters, cache.
    assertThat(body).doesNotContain("spring_data_redis");
    assertThat(body).doesNotContain("logback_events");
    // A metric NAME starting with "cache_" (the disabled group) — not a substring match, which
    // would false-positive on the spring-security "requestcache_" metric.
    assertThat(body).doesNotContainPattern("(?m)^(cache_|# (HELP|TYPE) cache_)");
  }

  /**
   * Supplies the test-key decoder so the secured chain validates locally against the in-memory key
   * (signature/issuer/audience), mirroring {@code SecurityFilterChainTest.TestKeycloak}.
   */
  @TestConfiguration
  static class TestKeycloak {

    @Bean
    RestTemplate restTemplate() {
      RestTemplate template = new RestTemplate();
      // No-op error handler: the secured endpoint answers 401 without a body, and the assertions
      // exercise the status code, so the response must surface instead of throwing.
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
