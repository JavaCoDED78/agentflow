package com.example.agentflow.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
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
 *   <li>a <strong>permissive</strong> chain active only on the {@code test} profile, so the many
 *       existing tests that exercise controllers without authenticating keep working. A permissive
 *       principal never runs on the kubernetes or production profile (see S04-10).
 * </ul>
 */
@Configuration
class SecurityConfiguration {

  private final AgentFlowProperties properties;

  SecurityConfiguration(AgentFlowProperties properties) {
    this.properties = properties;
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
   * Resolves the issuer's OIDC discovery document over HTTP and then fetches the located {@code
   * jwk_set_uri}. Called lazily on first decode, never at startup.
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
      URI jwkSetUri =
          JSONObjectUtils.getURI(JSONObjectUtils.parse(discovery.body()), "jwk_set_uri");
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
   * The secured chain: a bearer JWT is required for everything except the diagnostics endpoints.
   * Active on every non-test profile (default, local, kubernetes) and on the {@code secured-test}
   * profile. The {@link JwtDecoder} already carries the issuer/audience/expiry validator set in
   * {@link #jwtDecoder()}; on {@code secured-test} it is the test's in-memory key.
   *
   * @param http the HTTP security customizer
   * @param decoder the standard JWT decoder
   * @return the secured {@link SecurityFilterChain}
   * @throws Exception if the chain cannot be built
   */
  @Bean
  @Profile("!test")
  SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder)));
    return http.build();
  }

  /**
   * The permissive chain, active only on the {@code test} profile. Existing tests exercise
   * controllers without authenticating, so on that profile every request is permitted. It never
   * runs on the kubernetes or production profile.
   *
   * @param http the HTTP security customizer
   * @return the permissive {@link SecurityFilterChain}
   * @throws Exception if the chain cannot be built
   */
  @Bean
  @Profile("test")
  SecurityFilterChain permissiveSecurityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }
}
