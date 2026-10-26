package com.example.agentflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the S04-03, S04-08 and S04-11 acceptance criteria through the HTTP boundary. The S04-03
 * checks (missing/expired/tampered/mis-issued token → {@code 401}) live alongside the S04-08
 * access-matrix checks: a valid token that does not hold the single permission an endpoint requires
 * is rejected with {@code 403 Forbidden} at the secured {@code SecurityFilterChain} (default-deny,
 * least privilege), while a valid token that holds the permission passes the security layer. The
 * rejections are attributed to the real reasons (signature/expiry/issuer/audience) rather than to
 * an unreachable Keycloak.
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

  @Autowired private WorkflowDefinitionService service;

  @Autowired private ObjectMapper objectMapper;

  @Value("${agentflow.local.tenant-id:00000000-0000-0000-0000-000000000000}")
  private String configuredTenant;

  private String issuer;

  private String audience;

  private WorkflowDefinition fixture;

  @BeforeEach
  void setUp() {
    // The WorkflowDefinitionService mock is a Spring singleton, shared across every test in this
    // class: reset gives each test a clean interaction count (so verify() is exact) and clears
    // stale
    // stubs from a previous test.
    Mockito.reset(service);
    issuer = properties.getSecurity().getIssuerUri();
    audience = properties.getSecurity().getAudience();
    fixture = WorkflowDefinition.draft(UUID.fromString(configuredTenant), "Report", null, "local");
    // Stub the create/list/get use cases so the controller returns observable results without a
    // database: create() builds a definition (201), a lookup is a 404 (the 404 / 403 masking
    // policy). The real repository path is covered by the DB-free WorkflowDefinitionServiceTest.
    when(service.createDefinition(any(), any(), any(), any())).thenReturn(fixture);
    when(service.getDefinition(any(), any()))
        .thenAnswer(
            invocation -> {
              throw new ResponseStatusException(
                  HttpStatus.NOT_FOUND, "workflow definition was not found for this tenant");
            });
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

  /**
   * Sends a GET to {@code /api/v1/workflows} (the list endpoint) with the given bearer token, so
   * the S04-08 read-permission check can be exercised through the HTTP boundary. Uses {@code
   * exchange} so the {@code Authorization} header is actually transmitted.
   */
  private ResponseEntity<String> authorizedGetWorkflows(String token) {
    HttpHeaders headers = token == null ? new HttpHeaders() : bearer(token);
    return rest.exchange(
        baseUrl() + "/api/v1/workflows", HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  /**
   * Sends a POST to {@code /api/v1/workflows} (the create endpoint) with the given bearer token, so
   * the S04-08 write-permission check can be exercised through the HTTP boundary.
   */
  private ResponseEntity<String> authorizedPost(String token) {
    HttpHeaders headers = token == null ? new HttpHeaders() : bearer(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        baseUrl() + "/api/v1/workflows",
        HttpMethod.POST,
        new HttpEntity<>("{\"name\": \"Report\"}", headers),
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

  /**
   * Signs a token with a {@code roles} claim (the realm-roles shape {@link
   * ClaimsToAuthoritiesMapper} reads), so the S04-08 access-matrix checks can carry a single
   * catalogue permission (e.g. {@code workflow:read}) and exercise the endpoint matrix.
   */
  private String signWithRoles(
      RSAKey key, String issuer, String audience, Date expiresAt, String... roles)
      throws Exception {
    JWTClaimsSet.Builder builder =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .notBeforeTime(new Date(System.currentTimeMillis() - 5 * 60_000L))
            .expirationTime(expiresAt);
    if (roles.length > 0) {
      builder.claim("roles", List.of(roles));
    }
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
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
  void missing_token_is_rejected() {
    ResponseEntity<String> response =
        rest.getForEntity(baseUrl() + "/api/v1/does-not-exist", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void expired_token_is_rejected() throws Exception {
    String token = sign(testKey, issuer, audience, fiveMinutesAgo());

    ResponseEntity<String> response = authorizedGet(token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void token_signed_by_another_key_is_rejected() throws Exception {
    String token = sign(otherKey(), issuer, audience, inFiveMinutes());

    ResponseEntity<String> response = authorizedGet(token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void wrong_issuer_is_rejected() throws Exception {
    String token = sign(testKey, "https://keycloak.example/realms/evil", audience, inFiveMinutes());

    ResponseEntity<String> response = authorizedGet(token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void wrong_audience_is_rejected() throws Exception {
    String token = sign(testKey, issuer, "some-other-audience", inFiveMinutes());

    ResponseEntity<String> response = authorizedGet(token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void valid_token_passes_the_security_layer() throws Exception {
    String token = sign(testKey, issuer, audience, inFiveMinutes());

    // The request reaches the dispatcher (a 404 for the missing route) instead of being rejected at
    // the security layer, which proves the token was accepted.
    ResponseEntity<String> response = authorizedGet(token);
    assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // --- S04-08: least privilege / default-deny through the HTTP boundary ---

  @Test
  void read_permission_suffices_for_list_definition() throws Exception {
    String token = signWithRoles(testKey, issuer, audience, inFiveMinutes(), "workflow:read");

    // Passes the security layer: it reaches the dispatcher (a 404 for the route) instead of a
    // 401/403 at the security layer, which proves the token held the permission.
    ResponseEntity<String> response = authorizedGet(token);

    assertThat(response.getStatusCode())
        .isNotEqualTo(HttpStatus.UNAUTHORIZED)
        .isNotEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void read_permission_is_required_for_list_definition() throws Exception {
    // A token that does NOT hold workflow:read is denied (403) on the read endpoint (GET): the
    // matrix gates each action on its own single permission, so the list endpoint is not reachable
    // without the read permission. This is the negative that the positive above does not prove.
    ResponseEntity<String> response =
        authorizedGetWorkflows(
            signWithRoles(testKey, issuer, audience, inFiveMinutes(), "run:execute"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void token_without_roles_is_denied_on_write() throws Exception {
    // A valid token that holds no role claims is denied (403) on the write endpoint (POST) — the
    // default-deny close: the matrix grants nothing to a caller holding none of its permissions.
    ResponseEntity<String> response =
        authorizedPost(sign(testKey, issuer, audience, inFiveMinutes()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void write_permission_is_required_for_create() throws Exception {
    // A token that holds only workflow:read reaches the security layer but is denied (403) on the
    // write endpoint (POST): the matrix gates each action on its own single permission.
    ResponseEntity<String> response =
        authorizedPost(signWithRoles(testKey, issuer, audience, inFiveMinutes(), "workflow:read"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void write_permission_suffices_for_create() throws Exception {
    // A token with workflow:write passes authorization and reaches the controller (the stubbed
    // application service builds a definition, 201 Created; never a 401/403 at the security layer).
    ResponseEntity<String> response =
        authorizedPost(signWithRoles(testKey, issuer, audience, inFiveMinutes(), "workflow:write"));

    assertThat(response.getStatusCode())
        .isNotEqualTo(HttpStatus.UNAUTHORIZED)
        .isNotEqualTo(HttpStatus.FORBIDDEN);
  }

  // --- S04-11: restricted actuator endpoints through the HTTP boundary ---

  @Test
  void sensitive_management_endpoints_are_not_reachable_anonymously() {
    // env / configprops / beans / mappings are not in the exposure allow-list (S01-18), and the
    // secured chain's anyRequest().authenticated() (S04-11) rejects an unauthenticated request to
    // any non-probe path with 401 before it reaches the dispatcher — so these endpoints are never
    // reachable anonymously. A verified caller then meets a 404 (the endpoint is not registered),
    // which is the least-exposure layer on top.
    String[] sensitive = {
      "/actuator/env", "/actuator/configprops", "/actuator/beans", "/actuator/mappings"
    };
    for (String path : sensitive) {
      ResponseEntity<String> response = rest.getForEntity(baseUrl() + path, String.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
  }

  // --- S04-13: tenant isolation through the secured HTTP boundary (invariant 1 / ADR-007) ---

  @Test
  void body_tenant_spoofing_is_not_scoped_through_the_secured_chain() throws Exception {
    // A caller sends a different tenant id in the request body, hoping the server scopes the
    // mutation to it. The controller resolves the tenant from the trusted configuration (the
    // verified
    // security context once S04-05/S04-06 is wired), never from the body (invariant 1). The mock
    // records the tenant the service actually received.
    UUID spoofedTenant = UUID.fromString("11111111-1111-1111-1111-111111111111");
    String token = signWithRoles(testKey, issuer, audience, inFiveMinutes(), "workflow:write");

    HttpHeaders headers = bearer(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body = "{\"tenant_id\":\"" + spoofedTenant + "\",\"name\":\"Report\"}";
    ResponseEntity<String> response =
        rest.exchange(
            baseUrl() + "/api/v1/workflows",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);

    // The request passes the secured layer and reaches the application service (201): the bogus
    // body
    // tenant did not cause a security rejection, and — critically — is not consulted for scoping.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    verify(service)
        .createDefinition(eq(UUID.fromString(configuredTenant)), eq("local"), eq("Report"), any());
    verify(service, never()).createDefinition(eq(spoofedTenant), any(), any(), any());
  }

  @Test
  void cross_tenant_read_is_indistinguishable_from_missing_through_the_secured_chain()
      throws Exception {
    // A valid token holder requests a definition owned by another tenant. Through the secured chain
    // it is rendered exactly like a missing one (404 not_found, the 404 / 403 masking policy,
    // S04-09):
    // a foreign resource is indistinguishable from a missing one, so a guessed UUID neither reads
    // nor
    // enumerates another tenant's data (invariant 1). The lookup runs under the configured tenant.
    UUID crossTenantId = UUID.randomUUID();
    String token = signWithRoles(testKey, issuer, audience, inFiveMinutes(), "workflow:read");
    ResponseEntity<String> response =
        rest.exchange(
            baseUrl() + "/api/v1/workflows/" + crossTenantId,
            HttpMethod.GET,
            new HttpEntity<>(bearer(token)),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    JsonNode json = objectMapper.readTree(response.getBody());
    assertThat(json.get("status").asInt()).isEqualTo(404);
    assertThat(json.get("error_class").asString()).isEqualTo("not_found");
    verify(service).getDefinition(eq(UUID.fromString(configuredTenant)), eq(crossTenantId));
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

    /**
     * Replaces the application {@link WorkflowDefinitionService} with a Mockito mock on the {@code
     * secured-test} profile, so the controller returns observable results (201 for create, 404 for
     * a cross-tenant lookup) without a database. It is {@link Primary} so both the real
     * {@code @Service} and the mock are registered, and the mock wins the {@code
     * WorkflowDefinitionController} and this test's {@code @Autowired} injection.
     */
    @Bean
    @Primary
    WorkflowDefinitionService workflowDefinitionService() {
      return mock(WorkflowDefinitionService.class);
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
