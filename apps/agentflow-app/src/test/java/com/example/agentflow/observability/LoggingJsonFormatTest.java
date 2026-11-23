package com.example.agentflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end proof for S01-19 / S13-09 (standardized JSON structured logs).
 *
 * <p>The production wiring ({@code logback-spring.xml}) — the {@link StructuredJsonLogstashEncoder}
 * plus the {@link MaskingJsonGeneratorDecorator} with {@link SecretRedactingValueMasker} — is the
 * one place the log-line contract lives. This test wires the same encoder/decorator and asserts the
 * serialized output is parseable JSON that: carries {@code @timestamp}, {@code severity}, {@code
 * message}, {@code logger_name}, {@code thread_name}; carries exactly the whitelisted MDC keys
 * ({@code service}, {@code environment}, {@code traceId}, {@code spanId}, {@code correlationId}) —
 * a stray high-cardinality id never reaches the line — and never carries the canary fixture.
 */
class LoggingJsonFormatTest {

  private static final ObjectMapper mapper = new ObjectMapper();
  private static final String CANARY_RESOURCE = "/secrets/canary-openai-key.txt";

  private LoggerContext context;
  private StructuredJsonLogstashEncoder encoder;
  private ListAppender<ILoggingEvent> events;
  private Logger logger;

  @BeforeEach
  void setUp() {
    context = new LoggerContext();
    // Mirrors production: Spring Boot/logback attaches the SLF4J MDC adapter to
    // the LoggerContext at startup; a bare new LoggerContext() leaves it null.
    context.setMDCAdapter(MDC.getMDCAdapter());
    context.start();

    encoder = new StructuredJsonLogstashEncoder();
    encoder.setContext(context);

    MaskingJsonGeneratorDecorator decorator = new MaskingJsonGeneratorDecorator();
    decorator.setDefaultMask("***");
    // Register the same ValueMasker the logback-spring.xml <valueMasker> uses so
    // secret-like tokens embedded in a message value are redacted.
    decorator.addValueMasker(new SecretRedactingValueMasker());
    decorator.start();
    encoder.addDecorator(decorator);
    encoder.start();

    events = new ListAppender<>();
    events.setContext(context);
    events.start();

    this.logger = context.getLogger(LoggingJsonFormatTest.class);
    this.logger.setLevel(Level.INFO);
    this.logger.addAppender(events);
  }

  @AfterEach
  void tearDown() {
    events.stop();
    MDC.clear();
  }

  @Test
  void emits_structured_json_with_mdc_and_masks_secrets() throws java.io.IOException {
    // Seed the same MDC the LoggingContextProperties bean seeds in production, plus the
    // correlation context S13-01/S13-03 produce.
    MDC.put("service", "agentflow-app");
    MDC.put("environment", "local");
    MDC.put("traceId", "5b8c1f9a2d4e6f8a1b3c5d7e9f0a2b4c6d8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b");
    MDC.put("spanId", "a1b2c3d4e5f607182d3f4e5f607182d3");
    MDC.put("correlationId", "c4e8a2d1-9f2b-4c7a-9e1d-3a5b7c9d1e2f");
    // Stray high-cardinality ids that must never reach the JSON line (whitelist guard).
    MDC.put("tenant_id", "tenant-must-not-leak");
    MDC.put("run_id", "run-must-not-leak");
    MDC.put("user_id", "user-must-not-leak");

    logger.info(
        "Authenticating with Bearer abc.def.ghi, bearer=eyJhbGc.payload.sig and api_key=sk-test-value-12345");

    assertThat(events.list).hasSize(1);

    String logs = new String(encodeAll(events.list), StandardCharsets.UTF_8);

    // Parse each rendered event.
    String[] lines = logs.trim().split("\r?\n");
    assertThat(lines).hasSize(1);

    JsonNode first = mapper.readTree(lines[0]);

    // The S13-09 field contract: @timestamp, severity, message, logger_name, thread_name.
    assertThat(first.hasNonNull("@timestamp")).as("@timestamp must be present").isTrue();
    assertThat(first.path("severity").stringValue()).isEqualTo("INFO");
    assertThat(first.path("level_value").isMissingNode())
        .as("level_value must be dropped")
        .isTrue();
    assertThat(first.path("level").isMissingNode())
        .as("raw level must be renamed to severity")
        .isTrue();
    assertThat(first.path("logger_name").stringValue()).contains("LoggingJsonFormatTest");
    assertThat(first.path("message").stringValue()).contains("Authenticating with Bearer");
    assertThat(first.path("thread_name").stringValue()).isNotBlank();

    // service / environment / correlation ids come from the MDC.
    assertThat(first.path("service").stringValue()).isEqualTo("agentflow-app");
    assertThat(first.path("environment").stringValue()).isEqualTo("local");
    assertThat(first.path("traceId").stringValue())
        .isEqualTo("5b8c1f9a2d4e6f8a1b3c5d7e9f0a2b4c6d8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b");
    assertThat(first.path("spanId").stringValue()).isEqualTo("a1b2c3d4e5f607182d3f4e5f607182d3");
    assertThat(first.path("correlationId").stringValue())
        .isEqualTo("c4e8a2d1-9f2b-4c7a-9e1d-3a5b7c9d1e2f");

    // Stray high-cardinality MDC keys never reach the line.
    assertThat(logs).doesNotContain("tenant-must-not-leak");
    assertThat(logs).doesNotContain("run-must-not-leak");
    assertThat(logs).doesNotContain("user-must-not-leak");
    assertThat(logs).doesNotContain("tenant_id");
    assertThat(logs).doesNotContain("run_id");
    assertThat(logs).doesNotContain("user_id");

    // Secrets embedded in a message are masked before they are serialized.
    assertThat(logs).contains("Bearer ***");
    assertThat(logs).contains("bearer=***");
    assertThat(logs).contains("api_key=***");
    assertThat(logs).doesNotContain("abc.def.ghi");
    assertThat(logs).doesNotContain("eyJhbGc.payload.sig");
  }

  @Test
  void canary_fixture_never_reaches_the_serialized_log_line() throws java.io.IOException {
    // The S13-09 acceptance criterion: the canary secret, placed in a log message, must never
    // reach the wire. The fixture lives only in src/test/resources (sanctioned, never committed
    // as a source constant) so this test cannot trip its own git scan.
    String canary;
    try (java.io.InputStream in = getClass().getResourceAsStream(CANARY_RESOURCE)) {
      assertThat(in).as("the canary fixture must be on the test classpath").isNotNull();
      canary = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
    assertThat(canary).isNotBlank();

    MDC.put("service", "agentflow-app");
    MDC.put("environment", "local");

    logger.info("calling model with apiKey=" + canary + " before the call");

    assertThat(events.list).hasSize(1);
    String logs = new String(encodeAll(events.list), StandardCharsets.UTF_8);

    assertThat(logs).doesNotContain(canary);
    assertThat(logs).contains("***");
  }

  private byte[] encodeAll(java.util.List<ILoggingEvent> list) throws java.io.IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (ILoggingEvent event : list) {
      out.write(encoder.encode(event));
    }
    return out.toByteArray();
  }
}
