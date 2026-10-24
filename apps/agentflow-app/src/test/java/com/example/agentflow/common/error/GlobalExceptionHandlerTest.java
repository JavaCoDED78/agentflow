package com.example.agentflow.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the global error handler (S03-11) renders a stable {@link ProblemDetails} envelope for
 * each {@link com.example.agentflow.common.ErrorClass}: {@code validation}, {@code not_found},
 * {@code conflict} and {@code internal}.
 *
 * <p>Assertions run against a real HTTP server ({@code RANDOM_PORT}), so they exercise the actual
 * exposed web layer and the Jackson 3.x serialization of the RFC 9457 envelope, not a mock. The
 * throwaway {@link StubErrorController} depends only on Spring types (no domain type), so {@code
 * common} still compiles on itself — the architecture gate stays green.
 *
 * <p>Each case checks the raw JSON, so this also proves the {@code error_class}, {@code
 * correlation_id} and {@code instance} keys are emitted in the taxonomy's snake_case (the Jackson
 * 3.x {@code @JsonProperty} annotations), and that {@code internal} never leaks the raw exception
 * message.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;

  private final RestTemplate rest = new RestTemplate();

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  @Test
  void validation_failure_maps_to_400_and_lists_the_field() throws Exception {
    String body = post("/api/v1/test/valid", "{\"name\":\"\"}");
    JsonNode json = objectMapper.readTree(body);

    assertThat(json.get("status").asInt()).isEqualTo(400);
    assertThat(json.get("error_class").asString()).isEqualTo("validation");
    assertThat(json.get("retryable").asBoolean()).isFalse();
    assertThat(json.get("type").asString()).isEqualTo("about:blank");
    assertThat(json.get("instance").asString()).isEqualTo("/api/v1/test/valid");
    assertThat(json.get("detail").asString()).contains("name");
    assertThat(json.get("correlation_id").asString()).matches("[0-9a-f-]{36}");
  }

  @Test
  void malformed_json_body_maps_to_400_validation() throws Exception {
    String body = post("/api/v1/test/valid", "{ this is not json");
    JsonNode json = objectMapper.readTree(body);

    assertThat(json.get("status").asInt()).isEqualTo(400);
    assertThat(json.get("error_class").asString()).isEqualTo("validation");
    assertThat(json.get("retryable").asBoolean()).isFalse();
  }

  @Test
  void not_found_maps_to_404_and_is_permanent() throws Exception {
    String body = get("/api/v1/test/missing");
    JsonNode json = objectMapper.readTree(body);

    assertThat(json.get("status").asInt()).isEqualTo(404);
    assertThat(json.get("error_class").asString()).isEqualTo("not_found");
    assertThat(json.get("retryable").asBoolean()).isFalse();
  }

  @Test
  void conflict_maps_to_409_conflict() throws Exception {
    String body = get("/api/v1/test/conflict");
    JsonNode json = objectMapper.readTree(body);

    assertThat(json.get("status").asInt()).isEqualTo(409);
    assertThat(json.get("error_class").asString()).isEqualTo("conflict");
    assertThat(json.get("retryable").asBoolean()).isFalse();
    assertThat(json.get("detail").asString()).isEqualTo("resource already exists");
    assertThat(json.get("instance").asString()).isEqualTo("/api/v1/test/conflict");
  }

  @Test
  void unexpected_error_maps_to_500_without_leaking_the_message() throws Exception {
    String body = get("/api/v1/test/internal");
    JsonNode json = objectMapper.readTree(body);

    assertThat(json.get("status").asInt()).isEqualTo(500);
    assertThat(json.get("error_class").asString()).isEqualTo("internal");
    assertThat(json.get("detail").asString()).isEqualTo("The request is invalid");
    assertThat(json.get("correlation_id").asString()).matches("[0-9a-f-]{36}");
    assertThat(body).doesNotContain("postgres");
    assertThat(body).doesNotContain("connection failed");
  }

  /** POSTs {@code payload} to {@code path} as {@code application/json} and returns the raw body. */
  private String post(String path, String payload) {
    String url = baseUrl() + path;
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> entity = new HttpEntity<>(payload, headers);
    try {
      return rest.postForObject(url, entity, String.class);
    } catch (HttpClientErrorException | HttpServerErrorException ex) {
      return ex.getResponseBodyAsString();
    }
  }

  /** GETs {@code path} and returns the raw response body for any status. */
  private String get(String path) {
    String url = baseUrl() + path;
    try {
      return rest.getForEntity(url, String.class).getBody();
    } catch (HttpClientErrorException | HttpServerErrorException ex) {
      return ex.getResponseBodyAsString();
    }
  }
}
