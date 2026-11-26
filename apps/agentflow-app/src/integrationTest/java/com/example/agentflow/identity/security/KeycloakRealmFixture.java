package com.example.agentflow.identity.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared Testcontainers fixture for the AgentFlow Keycloak realm (S04-20).
 *
 * <p>It starts a throwaway Keycloak container on the same pinned image Compose uses ({@code
 * infra/compose/docker-compose.yml}), imports the declarative realm from Git ({@code
 * infra/compose/keycloak/realm-agentflow.json}) verbatim — the container never hand-edits the realm
 * — and exposes the S04-18 user-token acquisition method (headless Authorization Code + PKCE) plus
 * the minimal admin-API operations the contract test and the real-decoder IT need (user creation,
 * role assignment, realm reads, and a lifespan mutation for the expiry proof). Neither the admin
 * password nor the test user password is read from — or printed to — any log or exception message;
 * both are test-only throwaway values defined here and never used anywhere else.
 *
 * <p>The container is <em>per-test-class</em>, never a singleton: the decoder IT mutates the realm
 * (it shortens {@code accessTokenLifespan} to prove the expiry rejection), and that mutation must
 * never leak into the realm-contract test. Each test class gets its own fixture instance, started
 * in the constructor and disposed via {@link #close()}. The fixture never touches the production
 * Keycloak instance or the Compose stack.
 *
 * <p>Every public operation is best-effort against a freshly-booted realm: admin calls authenticate
 * against the master realm (as {@code admin-cli}); the PKCE flow is a plain HTTP state machine with
 * no browser, mirroring {@code scripts/get-user-token.py}.
 */
public class KeycloakRealmFixture implements AutoCloseable {

  /** The realm name the whole stack uses ({@code infra/compose/keycloak/realm-agentflow.json}). */
  public static final String REALM = "agentflow";

  /** The pinned image Compose uses — never bump independently here. */
  public static final String IMAGE = "quay.io/keycloak/keycloak:26.8.2";

  /** Throwaway values for a local-throwaway container only; never printed or logged. */
  public static final String ADMIN_USER = "admin";

  public static final String ADMIN_PASSWORD = "admin";
  public static final String SERVICE_CLIENT_SECRET = "test-s...cret";
  public static final String TEST_USER_PASSWORD = "test-only-throwaway-user-password";

  /** The public browser client used for the PKCE flow (S04-18). */
  public static final String CLIENT_ID = "agentflow-web";

  /** The redirect URI the realm pins for the public client. */
  public static final String REDIRECT_URI = "http://localhost:3000/auth-callback";

  private static final Pattern FORM_ACTION = Pattern.compile("action=\"([^\"]+)\"");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final GenericContainer<?> container;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * Starts a throwaway container and imports the realm from Git. The realm file is resolved by
   * walking up the directory tree from the working directory until a repository root marker is
   * found, so the fixture works no matter whether the test JVM is started by Gradle, an IDE or the
   * Gradle wrapper; the {@code keycloak.realm.file} system property overrides the resolution.
   */
  public KeycloakRealmFixture() {
    Path realmPath = resolveRealmPath();
    final byte[] realmBytes;
    try {
      realmBytes = Files.readAllBytes(realmPath);
    } catch (IOException e) {
      throw new IllegalStateException(
          "cannot read realm file " + realmPath + ": " + e.getMessage(), e);
    }

    try {
      this.container =
          new GenericContainer<>(IMAGE)
              .withExposedPorts(8080)
              .withEnv("KEYCLOAK_ADMIN", ADMIN_USER)
              .withEnv("KEYCLOAK_ADMIN_PASSWORD", ADMIN_PASSWORD)
              .withEnv("AGENTFLOW_SERVICE_CLIENT_SECRET", SERVICE_CLIENT_SECRET)
              .withCopyToContainer(
                  Transferable.of(realmBytes), "/opt/keycloak/data/import/realm.json")
              .withCommand("start-dev", "--import-realm")
              .waitingFor(
                  Wait.forHttp("/realms/master")
                      .forPort(8080)
                      .withStartupTimeout(Duration.ofMinutes(5)));
      this.container.start();
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "failed to start the Keycloak fixture container (image " + IMAGE + "): " + e.getMessage(),
          e);
    }
    this.http =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
  }

  /**
   * Resolves the Git-tracked realm file by walking up the directory tree from the working directory
   * until the realm path exists. A system property override is honored (e.g. an IDE run against a
   * checked-in variant). The resolution never reads the file — the caller passes the bytes at
   * container build time.
   *
   * @return the absolute path to the realm JSON file
   * @throws IllegalStateException if the file cannot be located
   */
  public static Path resolveRealmPath() {
    String override = System.getProperty("keycloak.realm.file");
    if (override != null && !override.isBlank()) {
      Path path = Paths.get(override);
      if (Files.isRegularFile(path)) {
        return path.toAbsolutePath();
      }
      throw new IllegalStateException("keycloak.realm.file points at a missing file: " + path);
    }
    Path start = Paths.get(".").toAbsolutePath().normalize();
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve("infra/compose/keycloak/realm-agentflow.json");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "could not locate infra/compose/keycloak/realm-agentflow.json walking up from "
            + start
            + " — set the keycloak.realm.file system property to its absolute path");
  }

  /**
   * The container's base URL ({@code http://host:mappedPort}).
   *
   * @return the base URL
   */
  public String baseUrl() {
    return "http://" + container.getHost() + ":" + container.getMappedPort(8080);
  }

  /**
   * The realm's issuer URI — the {@code iss} claim the production decoder pins to. This is the
   * value the {@code agentflow.security.issuer-uri} property must equal in the IT.
   *
   * @return {@code baseUrl + "/realms/" + REALM}
   */
  public String issuerUri() {
    return baseUrl() + "/realms/" + REALM;
  }

  /**
   * The realm's OIDC discovery document (exactly what the app reads on first decode).
   *
   * @return parsed discovery document
   */
  public JsonNode discoveryDocument() {
    HttpResponse<String> response =
        request("GET", issuerUri() + "/.well-known/openid-configuration", null, null, null);
    check(response, "OIDC discovery");
    return parseJson(response.body());
  }

  /**
   * Authenticates against the master realm with the throwaway admin account and returns the admin
   * access token. Memoized so the contract test's admin calls share one token.
   *
   * @return the admin access token
   */
  /**
   * Fetches a fresh admin token on every call. The token expires after ~60s, and some tests sleep
   * past that (expiry proof) — a cached token would fail with 401 mid-test. A re-login costs one
   * small admin POST and keeps the fixture honest at any test duration.
   */
  public String adminToken() {
    String body =
        "client_id=admin-cli&grant_type=password&username="
            + urlEncode(ADMIN_USER)
            + "&password="
            + urlEncode(ADMIN_PASSWORD);
    HttpResponse<String> response =
        request(
            "POST",
            baseUrl() + "/realms/master/protocol/openid-connect/token",
            body,
            Map.of(
                "Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"),
            null);
    check(response, "admin login");
    return parseJson(response.body()).get("access_token").asText();
  }

  /**
   * Performs an admin-API GET on the realm.
   *
   * @param path admin API path starting with {@code /admin/realms/agentflow}
   * @return parsed JSON body
   */
  public JsonNode adminGet(String path) {
    HttpResponse<String> response = request("GET", baseUrl() + path, null, adminHeaders(), null);
    check(response, "GET " + path);
    return parseJson(response.body());
  }

  /**
   * Performs an admin-API PUT on the realm.
   *
   * @param path admin API path starting with {@code /admin/realms/agentflow}
   * @param body JSON body
   */
  public void adminPut(String path, String body) {
    HttpResponse<String> response = request("PUT", baseUrl() + path, body, adminHeaders(), null);
    check(response, "PUT " + path);
  }

  private Map<String, String> adminHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + adminToken());
    headers.put("Accept", "application/json");
    headers.put("Content-Type", "application/json");
    return headers;
  }

  /**
   * Creates (or repairs) a test user and assigns the realm roles the test declares. The tenant is
   * attached as the {@code tenant_id} user attribute, exactly as {@code
   * scripts/create-test-users.py} does; an absent tenant means the user is created without a {@code
   * tenant_id} attribute (the multi-tenant contract point).
   *
   * @param username the local-dev username (e.g. {@code tenant-a-member})
   * @param email the local email
   * @param tenantId the tenant UUID to attach, or {@code null} (no tenant claim)
   * @param roles the catalogue role names to assign
   * @return the user id
   */
  public String createTestUser(String username, String email, String tenantId, List<String> roles) {
    String path = "/admin/realms/" + REALM + "/users";
    JsonNode existing = adminGet(path + "?username=" + urlEncode(username));
    String userId;
    if (existing.isArray() && existing.size() > 0) {
      userId = existing.get(0).get("id").asText();
    } else {
      StringBuilder attributes = new StringBuilder();
      if (tenantId != null) {
        attributes
            .append(",\"attributes\":{\"tenant_id\":[\"")
            .append(escapeJson(tenantId))
            .append("\"]}");
      }
      String body =
          "{\"username\":\""
              + escapeJson(username)
              + "\",\"email\":\""
              + escapeJson(email)
              + "\",\"enabled\":true,\"firstName\":\"Test\",\"lastName\":\"User\""
              + attributes
              + "}";
      HttpResponse<String> response = request("POST", baseUrl() + path, body, adminHeaders(), null);
      check(response, "create user " + username);
      // Keycloak answers 201 Created with an empty body; recover the id via the username lookup.
      JsonNode users = adminGet(path + "?username=" + urlEncode(username));
      if (!users.isArray() || users.size() == 0) {
        throw new IllegalStateException("user " + username + " was not created");
      }
      userId = users.get(0).get("id").asText();
    }

    // Refresh the profile attributes (the tenant claim is never silently lost by a repair).
    String refresh =
        "{\"email\":\"" + escapeJson(email) + "\",\"firstName\":\"Test\",\"lastName\":\"User\"";
    if (tenantId != null) {
      refresh += ",\"attributes\":{\"tenant_id\":[\"" + escapeJson(tenantId) + "\"]}";
    }
    refresh += "}";
    adminPut("/admin/realms/" + REALM + "/users/" + userId, refresh);

    setPassword(userId, TEST_USER_PASSWORD);

    // Assign any role the user does not already hold (idempotent repair, same as S04-18).
    JsonNode realmRoles = adminGet("/admin/realms/" + REALM + "/roles");
    for (String roleName : roles) {
      JsonNode role = findRole(realmRoles, roleName);
      if (role == null) {
        throw new IllegalStateException(
            "catalogue role " + roleName + " is missing from realm " + REALM);
      }
      JsonNode userRoles =
          adminGet("/admin/realms/" + REALM + "/users/" + userId + "/role-mappings/realm");
      boolean present = false;
      if (userRoles.isArray()) {
        for (JsonNode r : userRoles) {
          if (roleName.equals(r.get("name").asText())) {
            present = true;
            break;
          }
        }
      }
      if (!present) {
        String body = "[" + role.toString() + "]";
        HttpResponse<String> response =
            request(
                "POST",
                baseUrl() + "/admin/realms/" + REALM + "/users/" + userId + "/role-mappings/realm",
                body,
                adminHeaders(),
                null);
        check(response, "assign role " + roleName);
      }
    }
    return userId;
  }

  /** Sets the deterministic test password (temporary:false) — never printed or logged. */
  private void setPassword(String userId, String password) {
    String body =
        "{\"type\":\"password\",\"value\":\"" + escapeJson(password) + "\",\"temporary\":false}";
    HttpResponse<String> response =
        request(
            "PUT",
            baseUrl() + "/admin/realms/" + REALM + "/users/" + userId + "/reset-password",
            body,
            adminHeaders(),
            null);
    check(response, "set password");
  }

  /**
   * Mints an access token for a test user via the S04-18 method: headless Authorization Code + PKCE
   * against the public {@code agentflow-web} client. Follows the login page, any required action,
   * and the token exchange exactly as {@code scripts/get-user-token.py} does.
   *
   * @param username the local test user (e.g. {@code tenant-a-member})
   * @return the access token
   * @throws IllegalStateException if the flow does not produce a token
   */
  public String mintUserToken(String username) {
    String verifier = base64Url(randomBytes(48));
    String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.UTF_8)));
    String authPath =
        "/realms/"
            + REALM
            + "/protocol/openid-connect/auth?"
            + "client_id="
            + CLIENT_ID
            + "&response_type=code"
            + "&redirect_uri="
            + urlEncode(REDIRECT_URI)
            + "&code_challenge="
            + challenge
            + "&code_challenge_method=S256";

    HttpResponse<String> page = request("GET", resolveUrl(authPath), null, browserHeaders(), null);
    if (page.statusCode() != 200) {
      throw new IllegalStateException(
          "unexpected status " + page.statusCode() + " on the auth endpoint");
    }
    String formAction = extractFormAction(page.body());

    // Keycloak's login form is bound to the session cookie the auth-page GET sets — keep it for the
    // credential submit (as the S04-18 helper does with its shared Client cookie).
    String cookie = mergeCookie(null, page);

    Map<String, String> formHeaders = new HashMap<>(browserHeaders());
    formHeaders.put("Content-Type", "application/x-www-form-urlencoded");
    HttpResponse<String> submit =
        request(
            "POST",
            resolveUrl(formAction),
            "username="
                + urlEncode(username)
                + "&password="
                + urlEncode(TEST_USER_PASSWORD)
                + "&credentialId=",
            formHeaders,
            cookie);
    cookie = mergeCookie(cookie, submit);

    int status = submit.statusCode();
    String location = submit.headers().firstValue("Location").orElse(null);

    // Follow the flow: a required action (e.g. profile verification) may ask for an email update —
    // submit what the user already has (same behavior as the S04-18 helper).
    for (int i = 0; i < 4; i++) {
      if (!(status == 301 || status == 302 || status == 303) || location == null) {
        break;
      }
      if (location.contains("code=") || location.contains("state=")) {
        break;
      }
      if (location.contains("required-action")) {
        HttpResponse<String> actionPage =
            request("GET", resolveUrl(location), null, browserHeaders(), cookie);
        cookie = mergeCookie(cookie, actionPage);
        String actionForm = extractFormAction(actionPage.body());
        String email = username + "@agentflow.local";
        HttpResponse<String> actionSubmit =
            request(
                "POST",
                resolveUrl(actionForm),
                "email=" + urlEncode(email) + "&firstName=Test&lastName=User",
                formHeaders,
                cookie);
        cookie = mergeCookie(cookie, actionSubmit);
        status = actionSubmit.statusCode();
        location = actionSubmit.headers().firstValue("Location").orElse(null);
        continue;
      }
      break;
    }

    if (location == null) {
      throw new IllegalStateException("login failed (last status " + status + ")");
    }
    String code = extractQueryParam(location, "code");
    if (code == null) {
      throw new IllegalStateException("no authorization code in the post-login redirect");
    }

    String tokenBody =
        "client_id="
            + CLIENT_ID
            + "&grant_type=authorization_code"
            + "&redirect_uri="
            + urlEncode(REDIRECT_URI)
            + "&code="
            + urlEncode(code)
            + "&code_verifier="
            + urlEncode(verifier);
    HttpResponse<String> tokenResponse =
        request(
            "POST",
            baseUrl() + "/realms/" + REALM + "/protocol/openid-connect/token",
            tokenBody,
            Map.of(
                "Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"),
            null);
    check(tokenResponse, "token exchange");
    String accessToken = parseJson(tokenResponse.body()).get("access_token").asText();
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalStateException("token exchange returned no access_token");
    }
    return accessToken;
  }

  /**
   * Sets the realm's {@code accessTokenLifespan} — used by the expiry proof. The fixture never
   * restores it: the test class owns its container, so the mutated realm dies with the class.
   *
   * @param seconds the lifespan in seconds
   */
  public void setAccessTokenLifespanSeconds(int seconds) {
    adminPut("/admin/realms/" + REALM, "{\"accessTokenLifespan\":" + seconds + "}");
  }

  /**
   * The realm's full config as the admin API returns it (used by the contract test to pin the
   * grant-type posture).
   *
   * @return parsed realm config
   */
  public JsonNode realmConfig() {
    return adminGet("/admin/realms/" + REALM);
  }

  @Override
  public void close() {
    container.stop();
    container.close();
  }

  // ------------------------------------------------------------------ helpers

  private Map<String, String> browserHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", "agentflow-test-client");
    return headers;
  }

  private String resolveUrl(String pathOrUrl) {
    String value = pathOrUrl == null ? "" : pathOrUrl.trim();
    if (value.startsWith("http://") || value.startsWith("https://")) {
      return value;
    }
    return baseUrl() + (value.startsWith("/") ? "" : "/") + value;
  }

  private HttpResponse<String> request(
      String method, String url, String bodyString, Map<String, String> headers, String cookie) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
    if (bodyString == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.method(
          method, HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8));
    }
    if (headers != null) {
      headers.forEach(builder::header);
    }
    if (cookie != null) {
      builder.header("Cookie", cookie);
    }
    try {
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(
          "HTTP " + method + " " + url + " failed: " + e.getMessage(), e);
    }
  }

  private JsonNode parseJson(String body) {
    try {
      return mapper.readTree(body);
    } catch (Exception e) {
      throw new IllegalStateException(
          "response body is not valid JSON: " + abbreviate(body, 200), e);
    }
  }

  private HttpResponse<String> check(HttpResponse<String> response, String what) {
    int code = response.statusCode();
    if (code / 100 != 2 && code != 201) {
      throw new IllegalStateException(
          what + ": HTTP " + code + ": " + abbreviate(response.body(), 300));
    }
    return response;
  }

  private String mergeCookie(String previous, HttpResponse<String> response) {
    String setCookie = response.headers().firstValue("Set-Cookie").orElse(null);
    if (setCookie == null) {
      return previous;
    }
    // Keep the first Set-Cookie (session) — Keycloak's flow needs it (as in the S04-18 helper).
    return setCookie.split(";")[0];
  }

  private String extractFormAction(String body) {
    Matcher matcher = FORM_ACTION.matcher(body);
    if (!matcher.find()) {
      throw new IllegalStateException("no login form found on the auth page");
    }
    return matcher.group(1).replace("&amp;", "&");
  }

  /**
   * Extracts an application/x-www-form-urlencoded query parameter from a (possibly relative)
   * Location.
   */
  private String extractQueryParam(String location, String name) {
    String query = location.contains("?") ? location.substring(location.indexOf('?') + 1) : "";
    for (String fragment : query.split("&")) {
      int idx = fragment.indexOf('=');
      if (idx > 0 && fragment.substring(0, idx).equals(name)) {
        return urlDecode(fragment.substring(idx + 1));
      }
    }
    return null;
  }

  private JsonNode findRole(JsonNode roles, String name) {
    for (JsonNode role : roles) {
      if (name.equals(role.get("name").asText())) {
        return role;
      }
    }
    return null;
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  private static String urlEncode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String urlDecode(String value) {
    try {
      return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return value;
    }
  }

  private static String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String abbreviate(String value, int max) {
    if (value == null) return "";
    return value.length() > max ? value.substring(0, max) + "…" : value;
  }
}
