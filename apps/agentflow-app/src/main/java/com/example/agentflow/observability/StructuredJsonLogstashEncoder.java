package com.example.agentflow.observability;

import java.util.List;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.fieldnames.LogstashCommonFieldNames;

/**
 * The standard AgentFlow JSON log encoder (S13-09).
 *
 * <p>It extends the logstash-logback-encoder's {@link LogstashEncoder} with exactly the field
 * contract the task fixes:
 *
 * <ul>
 *   <li>{@code @timestamp} — the standard (logstash-canonical) timestamp field, unchanged;
 *   <li>{@code severity} — the level rendered as {@code severity} (not {@code level}), the
 *       vocabulary the {@code docs/observability/standards.md} log-line contract uses; the
 *       redundant numeric {@code level_value} is dropped;
 *   <li>{@code service}, {@code environment}, {@code traceId}, {@code spanId} and {@code
 *       correlationId} — written inline from the MDC, and <em>only</em> these five keys: the
 *       whitelist is closed on purpose, so any other MDC entry (tenant/run/user/document ids,
 *       request headers, a future debug key) can never reach a log line, and the standard fields
 *       ({@code message}, {@code logger_name}, …) carry no stray identifiers either;
 *   <li>everything else the default encoder produces (message, logger_name, thread_name,
 *       {@code @timestamp}, the exception/stack trace when present) stays unchanged.
 * </ul>
 *
 * <p>The encoder does no redaction itself — {@link SecretRedactingValueMasker} (via the {@code
 * MaskingJsonGeneratorDecorator} registered in {@code logback-spring.xml}) masks secret-like values
 * before serialization, so the wire output never carries a canary/key. This class only fixes the
 * <em>shape</em> of the line.
 */
public class StructuredJsonLogstashEncoder extends LogstashEncoder {

  /** The closed set of MDC keys the log line is allowed to carry (S13-09). */
  public static final List<String> WHITELISTED_MDC_KEYS =
      List.of("service", "environment", "traceId", "spanId", "correlationId");

  public StructuredJsonLogstashEncoder() {
    super();
    getFieldNames().setLevel("severity");
    getFieldNames().setLevelValue(LogstashCommonFieldNames.IGNORE_FIELD_INDICATOR);
    setIncludeMdcKeyNames(WHITELISTED_MDC_KEYS);
  }
}
