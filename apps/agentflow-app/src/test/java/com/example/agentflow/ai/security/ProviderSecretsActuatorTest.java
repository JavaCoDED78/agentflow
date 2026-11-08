package com.example.agentflow.ai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * S09-03 guard, diagnostic-endpoint half: over a real {@code RANDOM_PORT} server, the canary key is
 * not echoed by any reachable {@code /actuator} endpoint and the sensitive configuration endpoints
 * are closed, so a configured provider key can never be read back through the management surface.
 *
 * <p>This complements {@code HealthEndpointsTest} (which pins the exposure allow-list for the whole
 * app). Here the assertion is made against the specific canary planted by {@link
 * CanaryLeakScanTest}, and the sensitive endpoints are re-checked from the {@code ai} security
 * suite. The suite boots the app on the {@code test} profile — with no {@code
 * agentflow.ai.provider=openai}, so no provider key is loaded at all — so it needs no model, API
 * key or network.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class ProviderSecretsActuatorTest {

  /** The exposed diagnostic endpoints; every other one must stay anonymous (404). */
  private static final String[] REACHABLE = {"/health", "/info"};

  /** The sensitive configuration endpoints that must not be reachable. */
  private static final String[] SENSITIVE = {"/env", "/configprops"};

  @LocalServerPort private int port;

  private final RestTemplate rest = new RestTemplate();

  private String canary() {
    try (InputStream in = getClass().getResourceAsStream("/secrets/canary-openai-key.txt")) {
      assertThat(in).as("the canary fixture must be on the test classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read the canary fixture", exception);
    }
  }

  @Test
  public void reachable_diagnostic_endpoints_do_not_expose_the_canary() {
    String canary = canary();
    for (String path : REACHABLE) {
      String body = rest.getForEntity(baseUrl() + path, String.class).getBody();
      assertThat(body == null ? "" : body)
          .as("the exposed endpoint %s must not echo the canary", path)
          .doesNotContain(canary);
    }
  }

  @Test
  public void sensitive_configuration_endpoints_are_closed() {
    // RestTemplate throws HttpClientErrorException.NotFound on a 404 rather than returning it.
    for (String path : SENSITIVE) {
      assertThatThrownBy(() -> rest.getForEntity(baseUrl() + path, String.class))
          .as("%s must be closed (404), so a configured key cannot be read back", path)
          .isInstanceOf(HttpClientErrorException.NotFound.class);
    }
  }

  private String baseUrl() {
    return "http://localhost:" + port + "/actuator";
  }
}
