package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies the safe health endpoints introduced in S01-18 over a real HTTP server (RANDOM_PORT), so
 * the assertions exercise the actual exposed web layer rather than a mock.
 *
 * <p>Only {@code health} and {@code info} are exposed over HTTP, while the sensitive {@code env},
 * {@code configprops} and {@code beans} endpoints are outside the exposure allow-list, so they are
 * not reachable and answer {@code 404} anonymously. Liveness and readiness are separate health
 * probes used by the orchestrator. The secured security chain (S04-11) keeps these probes reachable
 * without a token for the orchestrator and requires a bearer token for everything else — least
 * exposure.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class HealthEndpointsTest {

  @LocalServerPort private int port;

  private final RestTemplate rest = new RestTemplate();

  private String baseUrl() {
    return "http://localhost:" + port + "/actuator";
  }

  @Test
  public void health_is_exposed() throws Exception {
    assertThat(rest.getForEntity(baseUrl() + "/health", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  public void info_is_exposed() throws Exception {
    assertThat(rest.getForEntity(baseUrl() + "/info", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  public void liveness_probe_is_exposed() throws Exception {
    assertThat(rest.getForEntity(baseUrl() + "/health/liveness", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  public void readiness_probe_is_exposed() throws Exception {
    assertThat(rest.getForEntity(baseUrl() + "/health/readiness", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  public void sensitive_endpoints_are_closed() throws Exception {
    // RestTemplate throws HttpClientErrorException.NotFound on a 404 rather than returning it.
    assertThatThrownBy(() -> rest.getForEntity(baseUrl() + "/env", String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    assertThatThrownBy(() -> rest.getForEntity(baseUrl() + "/configprops", String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    assertThatThrownBy(() -> rest.getForEntity(baseUrl() + "/beans", String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    assertThatThrownBy(() -> rest.getForEntity(baseUrl() + "/mappings", String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
  }
}
