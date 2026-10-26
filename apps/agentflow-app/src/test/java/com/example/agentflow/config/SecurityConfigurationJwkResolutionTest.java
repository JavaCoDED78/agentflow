package com.example.agentflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the JWK-set resolution in {@link SecurityConfiguration} (S04-03 follow-up).
 *
 * <p>{@link SecurityConfiguration#jwkSetFromIssuer(String)} fetches the issuer's OIDC discovery
 * document over HTTP and resolves the JWK set URL from it. This is the key lookup behind the whole
 * secured chain: a failure here rejects every token with {@code 401 invalid_token} before the
 * audience check runs. Keycloak 26.8.1 (the running realm) exposes the JWK set under the legacy
 * {@code jwks_uri} discovery field rather than the OIDC-standard {@code jwk_set_uri}, so the
 * resolution must tolerate both. These tests drive that resolution against a local {@link
 * HttpServer} (no external network, deterministic) via the private method.
 */
class SecurityConfigurationJwkResolutionTest {

  private RSAKey testKey;

  private HttpServer server;

  /** The discovery body served by the local server for the current test. */
  private String currentDiscovery;

  @BeforeEach
  void setUp() throws Exception {
    KeyPair keyPair = keyPair();
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    // Encode the modulus/exponent as base64url so the RSAKey.Builder accepts them.
    String modulus =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(publicKey.getModulus().toByteArray());
    String exponent =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(publicKey.getPublicExponent().toByteArray());
    testKey =
        new RSAKey.Builder(new Base64URL(modulus), new Base64URL(exponent))
            .keyID("test-key")
            .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (server != null) {
      server.stop(0);
    }
  }

  /** OIDC-standard discovery: the JWK set is under {@code jwk_set_uri}. */
  @Test
  void resolves_jwk_set_uri_field_when_present() throws Exception {
    server = startServer();
    currentDiscovery = discoveryJson().replace("jwks_uri", "jwk_set_uri");
    JWKSet set = resolve(baseUri());
    assertThat(set.getKeys()).anyMatch(key -> "test-key".equals(key.getKeyID()));
  }

  /**
   * Keycloak 26.8.1 regression: discovery exposes only {@code jwks_uri}, not {@code jwk_set_uri}.
   * The resolution must fall back to {@code jwks_uri} (both point at {@code /certs}) so the token
   * is still verified.
   */
  @Test
  void falls_back_to_jwks_uri_when_jwk_set_absent() throws Exception {
    server = startServer();
    currentDiscovery = discoveryJson(); // default discovery uses jwks_uri
    JWKSet set = resolve(baseUri());
    assertThat(set.getKeys()).anyMatch(key -> "test-key".equals(key.getKeyID()));
  }

  /**
   * When discovery exposes neither {@code jwk_set_uri} nor {@code jwks_uri}, resolution fails with
   * a clear message naming the issuer rather than a silent {@code null}.
   */
  @Test
  void fails_clearly_when_discovery_exposes_neither_uri() throws Exception {
    server = startServer();
    currentDiscovery =
        "{\n  \"issuer\": \"" + baseUri() + "/realms/agentflow\"\n}"; // no JWK set URL at all
    assertThatThrownBy(() -> resolve(baseUri()))
        .isInstanceOfSatisfying(
            InvocationTargetException.class,
            failure -> {
              Throwable cause = failure.getCause();
              assertThat(cause).isInstanceOf(IllegalStateException.class);
              assertThat(cause).hasMessageContaining("exposes neither jwk_set_uri nor jwks_uri");
            });
  }

  private JWKSet resolve(String issuer) throws Exception {
    Method method = SecurityConfiguration.class.getDeclaredMethod("jwkSetFromIssuer", String.class);
    method.setAccessible(true);
    return (JWKSet) method.invoke(new SecurityConfiguration(null, null, null), issuer);
  }

  private HttpServer startServer() throws IOException {
    HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String jwkSetJson = new JWKSet(List.of(testKey)).toString();
    httpServer.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          if (path.endsWith("/.well-known/openid-configuration")) {
            respond(exchange, currentDiscovery);
          } else if (path.endsWith("/certs")) {
            respond(exchange, jwkSetJson);
          } else {
            respond(exchange, "{}");
          }
        });
    httpServer.start();
    return httpServer;
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private String discoveryJson() {
    return "{\n"
        + "  \"issuer\": \""
        + baseUri()
        + "/realms/agentflow\",\n"
        + "  \"jwks_uri\": \""
        + baseUri()
        + "/certs\"\n"
        + "}";
  }

  private String baseUri() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static KeyPair keyPair() {
    try {
      KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
      kpg.initialize(2048);
      return kpg.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
