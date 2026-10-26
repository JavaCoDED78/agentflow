package com.example.agentflow.config;

import com.example.agentflow.identity.application.ClaimsToAuthoritiesMapper;
import com.example.agentflow.identity.application.authorization.AccessMatrix;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP security for the AgentFlow API (S04-03).
 *
 * <p>Secures the {@code /api/v1} surface with an OAuth 2.0 Resource Server that validates the JWT
 * access token: signature (via the standard {@link NimbusJwtDecoder}, built from the configured
 * issuer), token issuer, audience and expiry. A missing, expired, tampered or mis-issued token is
 * rejected with {@code 401 Unauthorized} before the request reaches any controller, which closes
 * the API by default until a Keycloak issuer is configured.
 *
 * <p>The diagnostics endpoints under {@code /actuator/health} and {@code /actuator/info} are left
 * reachable without a token so the liveness/readiness probes work; every other request is
 * authenticated.
 *
 * <p>Two {@link SecurityFilterChain}s are registered and split by the Spring profile:
 *
 * <ul>
 *   <li>the <strong>secured</strong> chain, active on every non-test profile (default, local,
 *       kubernetes) and on the {@code secured-test} profile used by the security-layer test;
 *   <li>a <strong>permissive</strong> chain (S04-10) that lives in the <em>test</em> source set and
 *       is active only on the {@code test} profile, so the many existing tests that exercise
 *       controllers without authenticating keep working. Moving it out of {@code src/main} means
 *       the permissive chain never ships in the production jar — a misconfigured profile can no
 *       longer open the API (see S04-10).
 * </ul>
 */
@Configuration
class SecurityConfiguration {

  private final AgentFlowProperties properties;

  private final ClaimsToAuthoritiesMapper claimsToAuthoritiesMapper;

  private final AccessMatrix accessMatrix;

  SecurityConfiguration(
      AgentFlowProperties properties,
      ClaimsToAuthoritiesMapper claimsToAuthoritiesMapper,
      AccessMatrix accessMatrix) {
    this.properties = properties;
    this.claimsToAuthoritiesMapper = claimsToAuthoritiesMapper;
    this.accessMatrix = accessMatrix;
  }

  /**
   * The standard JWT decoder (Nimbus), built from the configured issuer (S04-03). The JWK set is
   * resolved lazily on first decode via {@link #issuerJwkSource(String)}: OIDC discovery fetches
   * the {@code jwk_set_uri} and the keys only when a token is actually verified, not at startup, so
   * the context still boots without a reachable Keycloak. The {@link JWKSource} is built with the
   * non-deprecated {@link NimbusJwtDecoder#withJwkSource} API rather than the deprecated Nimbus
   * {@code RemoteJWKSet}. On the {@code secured-test} profile the context supplies a {@code
   * secured-test}-scoped {@link JwtDecoder} instead (the test's in-memory key), so the
   * signature/expiry/issuer/audience checks run locally rather than against an unreachable
   * Keycloak.
   *
   * <p>The token validator additionally checks the issuer claim, the audience and the expiry; a
   * missing, expired, tampered or mis-issued token is rejected with {@code 401} before the request
   * reaches any controller. The builder's {@code validator()} is package-private, so the validator
   * is attached via {@link NimbusJwtDecoder#setJwtValidator}.
   *
   * @return the {@link JwtDecoder}
   */
  @Bean
  @Profile("!test && !secured-test")
  @ConditionalOnMissingBean(JwtDecoder.class)
  JwtDecoder jwtDecoder() {
    String issuer = properties.getSecurity().getIssuerUri();
    String audience = properties.getSecurity().getAudience();
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(issuerJwkSource(issuer)).build();
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            new JwtIssuerValidator(issuer), new JwtAudienceValidator(audience)));
    return decoder;
  }

  /**
   * The JWT authentication converter (S04-04): the single place that maps a verified token's role
   * claims into Spring Security authorities via {@link ClaimsToAuthoritiesMapper}. The secured
   * chain registers it as the {@link JwtAuthenticationConverter}, so every entry point — HTTP
   * controllers, Kafka listeners, scheduled jobs — derives the principal's {@code
   * workflow:read}/{@code run:execute}/… authorities from the same catalogue; controllers never
   * parse the JWT themselves. The test-only permissive chain (S04-10) leaves the default converter
   * in place.
   *
   * @param mapper the claims-to-authorities converter
   * @return the {@link JwtAuthenticationConverter}
   */
  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter(ClaimsToAuthoritiesMapper mapper) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    // One place, deny-by-default, idempotent.
    converter.setJwtGrantedAuthoritiesConverter(mapper::map);
    return converter;
  }

  /**
   * A lazy {@link JWKSource} that resolves the issuer's OIDC discovery document and fetches the
   * {@code jwk_set_uri} only on the first {@link JWKSource#get(com.nimbusds.jose.jwk.JWKSelector,
   * SecurityContext)} (never at startup), so the context boots without a reachable Keycloak. Built
   * without the deprecated Nimbus {@code RemoteJWKSet}: the keys are fetched over HTTP and selected
   * with {@link JWKSelector#select(com.nimbusds.jose.jwk.JWKSet)}.
   *
   * @param issuer the configured issuer base URI
   * @return a lazy-discovery {@link JWKSource}
   */
  private JWKSource<SecurityContext> issuerJwkSource(String issuer) {
    return (selector, context) -> selector.select(jwkSetFromIssuer(issuer));
  }

  /**
   * Resolves the issuer's OIDC discovery document over HTTP and then fetches the located JWK set.
   * The JWK set URL is read from the {@code jwk_set_uri} field (OIDC discovery), falling back to
   * {@code jwks_uri} (Keycloak / legacy OIDC) when the issuer does not publish the newer field.
   * Called lazily on first decode, never at startup.
   *
   * @param issuer the configured issuer base URI
   * @return the parsed {@link JWKSet}
   */
  private JWKSet jwkSetFromIssuer(String issuer) {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      URI discoveryUri = URI.create(issuer + "/.well-known/openid-configuration");
      HttpResponse<String> discovery =
          client.send(
              HttpRequest.newBuilder(discoveryUri).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      requireOk(discovery, "OIDC discovery for " + issuer);
      Map<String, Object> metadata = JSONObjectUtils.parse(discovery.body());
      // OIDC discovery publishes the JWK set under `jwk_set_uri`; Keycloak (and the legacy spec)
      // use
      // `jwks_uri` instead. Resolve whichever is present so an issuer is never rejected for a
      // field-name difference alone.
      URI jwkSetUri = JSONObjectUtils.getURI(metadata, "jwk_set_uri");
      if (jwkSetUri == null) {
        jwkSetUri = JSONObjectUtils.getURI(metadata, "jwks_uri");
      }
      if (jwkSetUri == null) {
        throw new IllegalStateException(
            "issuer " + issuer + " discovery exposes neither jwk_set_uri nor jwks_uri");
      }
      HttpResponse<String> jwkSet =
          client.send(
              HttpRequest.newBuilder(jwkSetUri).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      requireOk(jwkSet, "JWK set fetch for " + jwkSetUri);
      return JWKSet.parse(jwkSet.body());
    } catch (Exception e) {
      throw new IllegalStateException(
          "Unable to resolve the issuer JWK set for " + issuer + ": " + e.getMessage(), e);
    }
  }

  private static void requireOk(HttpResponse<String> response, String what) {
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(what + ": HTTP " + response.statusCode());
    }
  }

  /**
   * The secured chain: a bearer JWT is required for everything except the diagnostics endpoints,
   * and every authenticated {@code /api/v1} endpoint is additionally gated on the single
   * least-privilege permission its action needs (S04-08, the {@link AccessMatrix}). The diagnostics
   * endpoints ({@code /actuator/health}, {@code /actuator/info}) stay {@code permitAll} so the
   * liveness/ readiness probes work; {@code anyRequest().authenticated()} keeps the default-deny
   * close so a future endpoint grants nothing until it is added to the matrix. On {@code
   * secured-test} the {@link JwtDecoder} is the test's in-memory key.
   *
   * @param http the HTTP security customizer
   * @param decoder the standard JWT decoder
   * @param jwtAuthenticationConverter the claims-to-authorities converter (S04-04)
   * @param accessMatrix the endpoint access matrix (S04-08) mapping each {@code /api/v1} action to
   *     the permission it requires
   * @return the {@link SecurityFilterChain}
   * @throws Exception if the chain cannot be built
   */
  @Bean
  @Profile("!test")
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder decoder,
      JwtAuthenticationConverter jwtAuthenticationConverter,
      AccessMatrix accessMatrix)
      throws Exception {
    // Default-deny with an explicit access matrix (S04-08). The diagnostics endpoints stay
    // reachable for the liveness/readiness probes; every authenticated endpoint is gated on its
    // single least-privilege permission from the {@link AccessMatrix}; and everything else is
    // authenticated, so no endpoint is reachable without an explicit rule here.
    http.csrf(AbstractHttpConfigurer::disable);
    http.authorizeHttpRequests(
        authz -> {
          // Public for the liveness/readiness/info probes only.
          authz
              .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
              .permitAll();
          // S04-08: least privilege — each /api/v1 action is gated on the single permission the
          // {@link AccessMatrix} maps it to (e.g. workflow:read for a GET, workflow:write for a
          // POST); a token must hold exactly the authority each rule requires.
          for (AccessMatrix.EndpointRule rule : accessMatrix.rules()) {
            authz
                .requestMatchers(HttpMethod.valueOf(rule.method()), rule.pathPattern())
                .hasAuthority(rule.requiredPermission().id());
          }
          // Default-deny close: any endpoint not listed above (including future ones) is
          // authenticated only, so nothing is reachable without an explicit rule here.
          authz.anyRequest().authenticated();
        });
    http.oauth2ResourceServer(
        oauth ->
            oauth.jwt(
                jwt ->
                    jwt.decoder(decoder).jwtAuthenticationConverter(jwtAuthenticationConverter)));
    return http.build();
  }
}
