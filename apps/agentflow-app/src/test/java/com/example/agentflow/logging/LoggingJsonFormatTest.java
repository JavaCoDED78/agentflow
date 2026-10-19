package com.example.agentflow.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end proof for S01-19 (standardized JSON logging).
 *
 * <p>The logstash-logback-encoder renders each event as a JSON object, the {@link
 * MaskingJsonGeneratorDecorator} masks secret-like values, and the current thread's {@code MDC}
 * ({@code service}/{@code environment}) is written. This test wires the same encoder/decorator the
 * {@code logback-spring.xml} uses and asserts the serialized output is parseable JSON carrying MDC
 * and masking a secret embedded in a message. ({@code LoggingContextProperties} seeds {@code
 * service}/{@code environment} in production; {@code S13} adds {@code traceId}/{@code spanId} via
 * OTel.)
 */
class LoggingJsonFormatTest {

  private static final ObjectMapper mapper = new ObjectMapper();

  private LoggerContext context;
  private LogstashEncoder encoder;
  private ListAppender<ILoggingEvent> events;
  private Logger logger;

  @BeforeEach
  void setUp() {
    context = new LoggerContext();
    // Mirrors production: Spring Boot/logback attaches the SLF4J MDC adapter to
    // the LoggerContext at startup; a bare new LoggerContext() leaves it null.
    context.setMDCAdapter(MDC.getMDCAdapter());
    context.start();

    encoder = new LogstashEncoder();
    encoder.setContext(context);
    encoder.setIncludeMdc(true);

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
  void emitsStructuredJsonWithMdcAndMasksSecrets() throws java.io.IOException {
    // Seed the same MDC the LoggingContextProperties bean seeds in production.
    MDC.put("service", "agentflow-app");
    MDC.put("environment", "local");

    logger.info(
        "Authenticating with Bearer abc.def.ghi, bearer=eyJhbGc.payload.sig and api_key=sk_test_123456");
    logger.info("plain message");

    assertThat(events.list).hasSize(2);

    String logs = new String(encodeAll(events.list), StandardCharsets.UTF_8);

    // Parse each rendered event.
    String[] lines = logs.trim().split("\r?\n");
    assertThat(lines).hasSize(2);

    JsonNode first = mapper.readTree(lines[0]);

    // Standard JSON log fields are present.
    assertThat(first.hasNonNull("@timestamp")).isTrue();
    assertThat(first.path("level").stringValue()).isEqualTo("INFO");
    assertThat(first.path("logger_name").stringValue()).contains("LoggingJsonFormatTest");
    assertThat(first.path("message").stringValue()).contains("Authenticating with Bearer");

    // service / environment come from the MDC.
    assertThat(first.path("service").stringValue()).isEqualTo("agentflow-app");
    assertThat(first.path("environment").stringValue()).isEqualTo("local");

    // Secrets embedded in a message are masked before they are serialized.
    assertThat(logs).contains("Bearer ***");
    assertThat(logs).contains("bearer=***");
    assertThat(logs).contains("api_key=***");
    assertThat(logs).doesNotContain("abc.def.ghi");
    assertThat(logs).doesNotContain("eyJhbGc.payload.sig");
    assertThat(logs).doesNotContain("sk_test_123456");
  }

  private byte[] encodeAll(java.util.List<ILoggingEvent> list) throws java.io.IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (ILoggingEvent event : list) {
      out.write(encoder.encode(event));
    }
    return out.toByteArray();
  }
}
