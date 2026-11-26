package com.example.agentflow.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Keycloak realm contract test (S04-20).
 *
 * <p>Starts a throwaway Keycloak container on the pinned production image and imports the
 * declarative realm from Git ({@code infra/compose/keycloak/realm-agentflow.json}), exactly as
 * Compose does. The contract is pinned against the realm as the admin API reports it — the scope's
 * mapper configuration, the clients' grant-type posture, the role catalogue, and the claims a
 * minted token carries — so a change to the realm that breaks the audience, the tenant claim, the
 * roles or the grant-type posture fails this test instead of silently rotting the live stack.
 *
 * <p>The token is minted exactly through the S04-18 method (headless Authorization Code + PKCE
 * against the public {@code agentflow-web} client) — the same way a real user authenticates — and
 * the claims are then asserted. The token is NOT re-verified here (the signature, expiry, issuer
 * and audience are the production decoder's job); this test pins the realm contract. The
 * production-config decoder proof lives in {@code RealJwtDecoderTest}.
 *
 * <p>This test runs with {@code ./gradlew integrationTest} (the Docker-backed suite) and not the
 * fast {@code check} gate.
 */
@DisplayName("Keycloak realm contract (S04-20)")
class KeycloakRealmContractTest {

  /**
   * Fixed, documented UUIDs for the two local tenants (same values as
   * scripts/create-test-users.py).
   */
  private static final String TENANT_A_ID = "11111111-1111-4111-8111-111111111111";

  private static final String TENANT_B_ID = "22222222-2222-4222-8222-222222222222";

  /** The 8 catalogue roles (S04-04) — the realm grants exactly these, never more. */
  private static final List<String> CATALOGUE_ROLES =
      List.of(
          "workflow:read",
          "workflow:write",
          "workflow:activate",
          "document:read",
          "document:write",
          "run:execute",
          "run:approve",
          "ops:dlq");

  /**
   * The standard Keycloak roles that legitimately coexist with the catalogue (never custom, never
   * catalogue).
   */
  private static final List<String> STANDARD_ROLES =
      List.of("default-roles-agentflow", "offline_access", "uma_authorization");

  private static KeycloakRealmFixture fixture;

  @BeforeAll
  static void startFixture() {
    fixture = new KeycloakRealmFixture();
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
        List.of("workflow:read", "document:read", "run:execute"));
  }

  // ------------------------------------------------------------ clients and posture

  @Test
  @DisplayName("the public SPA client is a public standard-flow client with no direct grant")
  void webClient_publicStandardFlow_noDirectGrant() {
    JsonNode client = findClient("agentflow-web");
    assertThat(client.get("publicClient")).isNotNull();
    assertThat(client.get("publicClient").asBoolean()).isTrue();
    assertThat(client.get("standardFlowEnabled").asBoolean()).isTrue();
    assertThat(client.get("implicitFlowEnabled").asBoolean()).isFalse();
    assertThat(client.get("directAccessGrantsEnabled").asBoolean()).isFalse();
    assertThat(client.get("serviceAccountsEnabled").asBoolean()).isFalse();
    assertThat(client.get("redirectUris").toString()).contains("http://localhost:3000/*");
    assertThat(client.get("webOrigins").toString()).contains("http://localhost:3000");
  }

  @Test
  @DisplayName(
      "the service client is a confidential client with service accounts only (no standard flow)")
  void serviceClient_confidential_serviceAccountsOnly() {
    JsonNode client = findClient("agentflow-service");
    assertThat(client.get("publicClient").asBoolean()).isFalse();
    assertThat(client.get("serviceAccountsEnabled").asBoolean()).isTrue();
    assertThat(client.get("standardFlowEnabled").asBoolean()).isFalse();
    assertThat(client.get("implicitFlowEnabled").asBoolean()).isFalse();
    assertThat(client.get("directAccessGrantsEnabled").asBoolean()).isFalse();
    assertThat(client.get("bearerOnly").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("the resource-server client exists and is not a user-facing client")
  void apiClient_exists_notUserFacing() {
    JsonNode client = findClient("agentflow-api");
    assertThat(client.get("clientId").asText()).isEqualTo("agentflow-api");
    assertThat(client.get("standardFlowEnabled").asBoolean()).isFalse();
    assertThat(client.get("directAccessGrantsEnabled").asBoolean()).isFalse();
  }

  @Test
  @DisplayName(
      "no client carries a credential the public figures; no client is both confidential and standard-flow")
  void noClientMixesGrantTypes() {
    for (String clientId : List.of("agentflow-web", "agentflow-service", "agentflow-api")) {
      JsonNode client = findClient(clientId);
      boolean standardFlow = client.get("standardFlowEnabled").asBoolean();
      boolean serviceAccounts = client.get("serviceAccountsEnabled").asBoolean();
      assertThat(standardFlow && serviceAccounts).isFalse();
    }
  }

  // ------------------------------------------------------------ scope and role contract

  @Test
  @DisplayName("the agentflow scope carries the tenant-id, roles and fixed-audience mappers")
  void scope_carriesTenantClaimRolesAndFixedAudience() {
    JsonNode scope = findScope("agentflow");
    JsonNode mappers = scope.get("protocolMappers");
    assertThat(mappers.isArray()).isTrue();

    JsonNode tenant = findMapper(mappers, "tenant-id");
    assertThat(tenant).isNotNull();
    assertThat(tenant.get("config").get("user.attribute").asText()).isEqualTo("tenant_id");
    assertThat(tenant.get("config").get("claim.name").asText()).isEqualTo("tenant_id");

    JsonNode roles = findMapper(mappers, "realm-access-roles");
    assertThat(roles).isNotNull();
    assertThat(roles.get("config").get("claim.name").asText()).isEqualTo("realm_access.roles");
    assertThat(roles.get("config").get("access.token.claim").asText()).isEqualTo("true");

    JsonNode audience = findMapper(mappers, "fixed-audience");
    assertThat(audience).isNotNull();
    assertThat(audience.get("config").get("included.client.audience").asText())
        .isEqualTo("agentflow-api");
    assertThat(audience.get("config").get("access.token.claim").asText()).isEqualTo("true");
  }

  @Test
  @DisplayName("both user-facing clients and the service client default to the agentflow scope")
  void clientsDefaultToAgentflowScope() {
    for (String clientId : List.of("agentflow-web", "agentflow-service")) {
      JsonNode client = findClient(clientId);
      JsonNode defaultScopes = client.get("defaultClientScopes");
      assertThat(defaultScopes.isArray()).isTrue();
      assertThat(defaultScopes.toString()).contains("agentflow");
    }
  }

  @Test
  @DisplayName("the realm grants exactly the 8 catalogue roles, plus only standard Keycloak roles")
  void catalogueRoles_present_noCustomExtras() {
    JsonNode roles = fixture.adminGet("/admin/realms/agentflow/roles");
    List<String> names = new java.util.ArrayList<>();
    for (JsonNode role : roles) {
      names.add(role.get("name").asText());
    }
    for (String catalogueRole : CATALOGUE_ROLES) {
      assertThat(names).contains(catalogueRole);
    }
    List<String> custom = names.stream().filter(n -> !STANDARD_ROLES.contains(n)).toList();
    assertThat(custom).containsExactlyInAnyOrderElementsOf(CATALOGUE_ROLES);
  }

  // ------------------------------------------------------------ minted token and claims

  @Test
  @DisplayName(
      "a minted access token carries the pinned issuer, audience, tenant claim and role set")
  void mintedToken_carriesPinnedClaims() {
    String token = fixture.mintUserToken("tenant-a-member");
    JsonNode claims = decodePayload(token);

    assertThat(claims.get("iss").asText()).isEqualTo(fixture.issuerUri());
    assertThat(claims.get("aud").asText()).isEqualTo("agentflow-api");
    assertThat(claims.get("tenant_id").asText()).isEqualTo(TENANT_A_ID);

    JsonNode roles = claims.get("realm_access").get("roles");
    assertThat(roles.isArray()).isTrue();
    java.util.Set<String> roleNames = new java.util.HashSet<>();
    for (JsonNode role : roles) {
      roleNames.add(role.asText());
    }
    // The assigned catalogue roles are present, the other catalogue roles are absent, and only the
    // standard Keycloak roles (plus the catalogue roles) appear in the token.
    assertThat(roleNames).contains("workflow:read", "document:read", "run:execute");
    assertThat(roleNames)
        .doesNotContain(
            "workflow:write", "workflow:activate", "document:write", "run:approve", "ops:dlq");
    java.util.Set<String> allowed = new java.util.HashSet<>(CATALOGUE_ROLES);
    allowed.addAll(STANDARD_ROLES);
    assertThat(roleNames).isSubsetOf(allowed);

    // The tenant claim is the local documented UUID, never a JWT-derived identity string.
    assertThat(claims.get("tenant_id").asText())
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    // iss from discovery matches the token (the same issuer the app pins).
    JsonNode discovery = fixture.discoveryDocument();
    assertThat(discovery.get("issuer").asText()).isEqualTo(fixture.issuerUri());
  }

  // ------------------------------------------------------------ helpers

  private JsonNode findClient(String clientId) {
    JsonNode clients = fixture.adminGet("/admin/realms/agentflow/clients?clientId=" + clientId);
    assertThat(clients.isArray()).isTrue();
    assertThat(clients.size()).isGreaterThan(0);
    return clients.get(0);
  }

  private JsonNode findScope(String name) {
    JsonNode scopes = fixture.adminGet("/admin/realms/agentflow/client-scopes");
    for (JsonNode scope : scopes) {
      if (name.equals(scope.get("name").asText())) {
        return scope;
      }
    }
    throw new IllegalStateException("scope " + name + " is missing from the realm");
  }

  private JsonNode findMapper(JsonNode mappers, String name) {
    for (JsonNode mapper : mappers) {
      if (name.equals(mapper.get("name").asText())) {
        return mapper;
      }
    }
    return null;
  }

  /**
   * Decode the (unverified, display-only) JWT payload to read its claims. The signature is the
   * production decoder's job, not this test's.
   */
  private static JsonNode decodePayload(String token) {
    String[] parts = token.split("\\.");
    assertThat(parts).hasSize(3);
    String payload = parts[1];
    payload += "=".repeat((4 - payload.length() % 4) % 4);
    byte[] bytes = java.util.Base64.getUrlDecoder().decode(payload);
    try {
      return new tools.jackson.databind.ObjectMapper()
          .readTree(new String(bytes, StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("token payload is not valid JSON", e);
    }
  }
}
