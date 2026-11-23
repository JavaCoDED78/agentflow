package com.example.agentflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * S13-03 guard: a test HTTP request is visible in the trace backend with stable service attributes,
 * and no span ever carries the raw request body.
 *
 * <p>The application context boots with the real {@code spring-boot-starter-opentelemetry}
 * auto-configuration; {@link #otlpEndpoint(DynamicPropertyRegistry)} points the OTLP span exporter
 * at an in-process JDK {@link HttpServer} that records every exported payload, so the test inspects
 * the actual protobuf a Collector would receive (no exporter seam is mocked).
 *
 * <p>Two criteria are pinned over a real export:
 *
 * <ol>
 *   <li><b>Visibility + stable service attributes</b> — a {@code GET /actuator/health} request
 *       produces an exported span named after the HTTP route ({@code get /actuator/health} on the
 *       wire — Micrometer names HTTP spans with a lowercase method; the route name appears verbatim
 *       because protobuf encodes strings as raw UTF-8), and the auto-configured {@link Resource}
 *       carries {@code service.name=agentflow-app} (the application name) and {@code
 *       deployment.environment=test} (bounded, configuration-fixed per the S13-01 standards) — the
 *       same resource that is attached to every exported span.
 *   <li><b>No raw request body</b> — a POST whose body embeds a unique marker is exported too, and
 *       the marker appears nowhere in any captured OTLP payload (attributes, events, status
 *       messages — anything), so request bodies can never reach the trace backend.
 * </ol>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "agentflow.http.port=0")
@ActiveProfiles("test")
class OtlpTracingExportTest {

  /** Every OTLP request body the in-process collector received, in arrival order. */
  private static final List<byte[]> EXPORTED_PAYLOADS = new CopyOnWriteArrayList<>();

  private static final HttpServer OTLP_SERVER = startOtlpServer();

  @LocalServerPort private int port;

  @Autowired private SdkTracerProvider tracerProvider;

  @Autowired private Resource resource;

  private static HttpServer startOtlpServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
      server.createContext(
          "/",
          exchange -> {
            EXPORTED_PAYLOADS.add(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(HttpStatus.OK.value(), -1);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException("cannot start the in-process OTLP endpoint", e);
    }
  }

  @AfterAll
  static void stopOtlpServer() {
    OTLP_SERVER.stop(0);
  }

  /**
   * Points the real OTLP span exporter at the in-process collector before the context starts and
   * pins the bounded environment attribute, exactly as a profile would.
   */
  @DynamicPropertySource
  static void otlpEndpoint(DynamicPropertyRegistry registry) {
    registry.add(
        "management.opentelemetry.tracing.export.otlp.endpoint",
        () -> "http://localhost:" + OTLP_SERVER.getAddress().getPort() + "/v1/traces");
    // Plain protobuf bodies so the captured payloads are directly searchable.
    registry.add("management.opentelemetry.tracing.export.otlp.compression", () -> "none");
    registry.add(
        "management.opentelemetry.resource-attributes.deployment.environment", () -> "test");
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  /** Flushes the batch span processor so every recorded span is exported before asserting. */
  private void flush() {
    boolean completed = tracerProvider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess();
    assertThat(completed).isTrue();
  }

  /**
   * Renders a captured OTLP payload for string assertions. Protobuf encodes string fields as
   * length-prefixed raw UTF-8, so any ASCII string carried anywhere in the export (attribute value,
   * event, status) survives verbatim; non-ASCII bytes decode as the replacement character, which
   * cannot collide with the ASCII markers asserted here.
   */
  private static List<String> renderedPayloads() {
    return EXPORTED_PAYLOADS.stream()
        .map(payload -> new String(payload, StandardCharsets.UTF_8))
        .toList();
  }

  @Test
  void an_http_request_is_exported_with_stable_service_attributes() {
    var response = rest().getForEntity(baseUrl() + "/actuator/health", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    flush();
    assertThat(EXPORTED_PAYLOADS).isNotEmpty();

    // The request is visible in the trace backend as a span named after the HTTP route
    // (Micrometer's wire naming is "<lowercase method> <route>").
    assertThat(renderedPayloads()).anyMatch(payload -> payload.contains("get /actuator/health"));

    // Stable service identity (S13-01): the application name and a bounded, configuration-fixed
    // environment, from the auto-configured Resource that every exported span carries.
    assertThat(resource.getAttribute(AttributeKey.stringKey("service.name")))
        .isEqualTo("agentflow-app");
    assertThat(resource.getAttribute(AttributeKey.stringKey("deployment.environment")))
        .isEqualTo("test");
  }

  @Test
  void spans_never_carry_the_raw_request_body() {
    String marker = "s13-03-raw-body-marker-" + UUID.randomUUID();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> entity = new HttpEntity<>("{\"probe\":\"" + marker + "\"}", headers);

    // POST with a unique body marker: whatever the endpoint answers, it is an observed HTTP
    // request that must be exported as a span.
    rest().exchange(baseUrl() + "/api/v1/runs", HttpMethod.POST, entity, String.class);

    flush();
    assertThat(EXPORTED_PAYLOADS).isNotEmpty();
    // The raw body must not reach the trace backend in any form: not in an attribute value, not
    // in an event, not anywhere in any captured OTLP payload.
    renderedPayloads().forEach(payload -> assertThat(payload).doesNotContain(marker));
    // The POST request itself is visible as a span — its route is recorded in the span name and
    // the http.url / uri attributes.
    assertThat(renderedPayloads()).anyMatch(payload -> payload.contains("/api/v1/runs"));
  }

  private static RestTemplate rest() {
    RestTemplate template = new RestTemplate();
    template.setErrorHandler(new NoOpResponseErrorHandler());
    return template;
  }
}
