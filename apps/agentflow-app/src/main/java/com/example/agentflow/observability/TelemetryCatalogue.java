package com.example.agentflow.observability;

import java.util.List;
import java.util.Set;

/**
 * The single machine-checked source of truth for the AgentFlow telemetry surface (S13-19).
 *
 * <p>It is the authoritative catalogue the telemetry privacy test ({@link TelemetryPrivacyTest},
 * DB-free, run by {@code ./gradlew :apps:agentflow-app:check}) walks: every metric the application
 * emits through Micrometer, every span attribute and span name the code creates through
 * OpenTelemetry, and every MDC key the S13-09 encoder is allowed to carry. A forbidden label,
 * attribute, span name or log key fails the build before the signal can ship.
 *
 * <p>It is deliberately pure data (no Spring, no I/O, no business logic) — the same posture as the
 * S13-01 standards doc that owns the conventions:
 * [docs/observability/standards.md](docs/observability/standards.md) fixes the rules, this class
 * makes them checkable, and [docs/observability/metrics.md](docs/observability/metrics.md)
 * documents the same names for the operators. Keep the three in sync — a new meter, span attribute
 * or log key belongs in all three in one change.
 *
 * <p>The catalogue does not cover the Boot/Actuator meters ({@code http.server.requests}, {@code
 * jvm.*}, {@code hikaricp.*}): those are auto-configuration-managed, carry only bounded Boot tags
 * and are documented in the same metrics doc without an explicit entry here.
 */
public final class TelemetryCatalogue {

  private TelemetryCatalogue() {}

  /** The metric types the platform emits through its own builders. */
  public enum MetricType {
    COUNTER,
    TIMER,
    GAUGE
  }

  /** A closed label: a bounded key plus the closed set of values it may carry (lowercase). */
  public record Label(String key, Set<String> allowedValues) {

    public Label {
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException("label key must be non-blank");
      }
      if (allowedValues == null || allowedValues.isEmpty()) {
        throw new IllegalArgumentException(
            "label <" + key + "> needs a non-empty closed value set");
      }
    }
  }

  /** A catalogue metric: its name, its type and its (optional) bounded labels. */
  public record Metric(String name, MetricType type, String unit, List<Label> labels) {

    public Metric {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("metric name must be non-blank");
      }
      if (labels == null) {
        labels = List.of();
      }
    }

    /** Labels of this metric by key (defensive copy not needed — the catalogue is immutable). */
    public List<Label> labels() {
      return labels;
    }
  }

  /**
   * The full metric catalogue. Every Micrometer {@code Counter}/{@code Timer}/{@code Gauge} the
   * application registers is expected to appear here exactly once; the telemetry privacy test fails
   * CI when the source holds a meter without a catalogue entry, or a catalogue entry is not
   * registered anywhere in the source tree.
   *
   * <p>The pre-existing retrieval meters ({@code hybrid} / {@code half-results} / {@code duration},
   * S10-16, documented as such) and the two pre-existing workflow counters are kept verbatim —
   * renaming them would break the dashboards that already consume them.
   */
  public static final List<Metric> METRICS =
      List.of(
          // Workflow-execution family (S13-11, S07-09 pre-existing)
          metric(
              "agentflow.workflowexecution.run.total",
              MetricType.COUNTER,
              "count",
              label(
                  "state",
                  "queued",
                  "running",
                  "waiting_for_approval",
                  "retry_wait",
                  "succeeded",
                  "failed",
                  "cancel_requested",
                  "cancelled")),
          metric("agentflow.workflowexecution.run.duration.seconds", MetricType.TIMER, "seconds"),
          metric("agentflow.workflowexecution.step.duration.seconds", MetricType.TIMER, "seconds"),
          metric("agentflow.workflowexecution.step.retry.total", MetricType.COUNTER, "count"),
          metric("agentflow.workflowexecution.step.cancelled.total", MetricType.COUNTER, "count"),
          metric("agentflow.workflowexecution.lease.expired.total", MetricType.COUNTER, "count"),
          metric("agentflow.workflow.recoveries", MetricType.COUNTER, "count"),
          metric("agentflow.workflow.approvals.expired", MetricType.COUNTER, "count"),
          // Messaging family (S13-12)
          metric("agentflow.messaging.outbox.pending.events", MetricType.GAUGE, "count"),
          metric("agentflow.messaging.outbox.oldest.age.seconds", MetricType.GAUGE, "seconds"),
          metric(
              "agentflow.messaging.outbox.publish.attempts.total",
              MetricType.COUNTER,
              "count",
              label("outcome", "delivered", "transient_failure", "ambiguous", "permanent_failure")),
          metric("agentflow.messaging.outbox.publish.retries.total", MetricType.COUNTER, "count"),
          metric("agentflow.messaging.consumer.lag.count", MetricType.GAUGE, "count"),
          metric(
              "agentflow.messaging.consumer.processing.failures.total",
              MetricType.COUNTER,
              "count",
              label("outcome", "retry", "dead_letter")),
          // AI/RAG family (S13-13 plus the pre-existing S10-16 retrieval meters)
          metric("agentflow.ai.provider.chat.latency.seconds", MetricType.TIMER, "seconds"),
          metric(
              "agentflow.ai.provider.chat.errors.total",
              MetricType.COUNTER,
              "count",
              label(
                  "reason",
                  "rate_limit",
                  "server_error",
                  "timeout",
                  "connection",
                  "client_error",
                  "unknown",
                  "concurrency_limited")),
          metric("agentflow.ai.provider.chat.tokens.input.total", MetricType.COUNTER, "tokens"),
          metric("agentflow.ai.provider.chat.tokens.output.total", MetricType.COUNTER, "tokens"),
          metric("agentflow.ai.provider.embedding.latency.seconds", MetricType.TIMER, "seconds"),
          metric(
              "agentflow.ai.provider.embedding.errors.total",
              MetricType.COUNTER,
              "count",
              label(
                  "reason",
                  "rate_limit",
                  "server_error",
                  "timeout",
                  "connection",
                  "client_error",
                  "unknown",
                  "concurrency_limited")),
          metric("agentflow.ai.provider.embedding.tokens.total", MetricType.COUNTER, "tokens"),
          metric("agentflow.usage.estimated.cost.usd", MetricType.COUNTER, "usd"),
          metric(
              "agentflow.toolexecution.tool.duration.seconds",
              MetricType.TIMER,
              "seconds",
              label("outcome", "succeeded", "throttled", "timed_out", "faulted")),
          metric(
              "agentflow.knowledgebase.retrieval.chunks.returned.total",
              MetricType.COUNTER,
              "chunks"),
          metric("agentflow.ingestion.index.failures.total", MetricType.COUNTER, "passes"),
          metric("agentflow.knowledgebase.retrieval.hybrid", MetricType.COUNTER, "calls"),
          metric("agentflow.knowledgebase.retrieval.half-results", MetricType.COUNTER, "results"),
          metric("agentflow.knowledgebase.retrieval.duration", MetricType.TIMER, "seconds"),
          // Redis family (S12-19)
          metric("agentflow.redis.rate_limit.fallback_open", MetricType.COUNTER, "count"));

  /**
   * Label names that are forbidden in EVERY metric, in every position — high-cardinality
   * identifiers and content-carrying names ({@code tenantId}, {@code runId}, {@code userId}, {@code
   * documentName}, prompts, payloads, raw errors, trace/span ids). A label whose key is in this set
   * is an invariant violation, not a style issue.
   */
  public static final Set<String> FORBIDDEN_LABEL_NAMES =
      Set.of(
          "tenantId",
          "runId",
          "userId",
          "stepRunId",
          "documentId",
          "documentName",
          "prompt",
          "query",
          "token",
          "payload",
          "resourceId",
          "correlationId",
          "traceId",
          "spanId",
          "actor",
          "email",
          "ip",
          "hostname",
          "url");

  /**
   * Span attribute names that are forbidden — identifiers, content or credentials. Note the
   * deliberate absence of the causation attributes ({@code correlation_id} / {@code causation_id}),
   * which are the allowed trace shape for the messaging boundary; those are allowed here by {@link
   * #ALLOWED_ATTRIBUTE_NAMES} instead.
   */
  public static final Set<String> FORBIDDEN_ATTRIBUTE_NAMES =
      Set.of(
          "tenantId",
          "runId",
          "stepRunId",
          "documentId",
          "documentName",
          "userId",
          "agentId",
          "sessionId",
          "prompt",
          "query",
          "token",
          "payload",
          "resourceId",
          "actor",
          "email",
          "ip",
          "hostname",
          "url",
          "content",
          "body",
          "text",
          "message",
          "secret",
          "password",
          "authorization",
          "apiKey",
          "apikey",
          "api_key");

  /**
   * The closed vocabulary of span attribute names the code may set (standards.md §Trace
   * attributes): service identity, causation/correlation, operation identity and the bounded
   * AI/RAG/tool attributes. Anything outside this set is a forbidden attribute, never an
   * undocumented one.
   */
  public static final Set<String> ALLOWED_ATTRIBUTE_NAMES =
      Set.of(
          // service identity
          "service.name",
          "service.version",
          "deployment.environment",
          // causality (trace/log values only — never metric labels)
          "correlation_id",
          "causation_id",
          "trace_id",
          "span_id",
          // operation identity (bounded, closed)
          "agentflow.operation",
          "agentflow.step.type",
          "agentflow.step.status",
          "agentflow.run.state",
          // AI/RAG (bounded, never content)
          "agentflow.ai.provider",
          "agentflow.ai.model",
          "agentflow.ai.prompt_version",
          "agentflow.ai.budget_outcome",
          "agentflow.rag.retrieval.outcome",
          "agentflow.ingestion.outcome",
          // tool execution (bounded, never argument/result bodies)
          "agentflow.tool.name",
          "agentflow.tool.risk_class",
          "agentflow.tool.outcome");

  /**
   * The closed set of span names the code may start. A span name is a code-defined operation label,
   * never a payload (invariant 8) — the code is checked against this set.
   */
  public static final Set<String> ALLOWED_SPAN_NAMES =
      Set.of(
          "ResilientChatModel#chat",
          "ResilientEmbeddingModel#embed",
          "KafkaMessageDeliverer#deliver",
          "DocumentIndexingWorker#handle");

  /**
   * The closed set of MDC keys the S13-09 encoder may write (standards.md §Logs) — the pinned log
   * line contract, mirrored by {@link StructuredJsonLogstashEncoder#WHITELISTED_MDC_KEYS}.
   */
  public static final Set<String> ALLOWED_LOG_MDC_KEYS =
      Set.of("service", "environment", "traceId", "spanId", "correlationId");

  /** The longest label value-set the catalogue may declare (bounded, small, code-defined). */
  public static final int MAX_LABEL_VALUES = 32;

  private static Metric metric(String name, MetricType type, String unit, Label label) {
    return new Metric(name, type, unit, List.of(label));
  }

  private static Metric metric(String name, MetricType type, String unit) {
    return new Metric(name, type, unit, List.of());
  }

  private static Label label(String key, String... values) {
    return new Label(key, Set.of(values));
  }

  /** Find a catalogue entry by exact metric name; {@code null} when the name is unknown. */
  public static Metric metric(String name) {
    for (Metric metric : METRICS) {
      if (metric.name().equals(name)) {
        return metric;
      }
    }
    return null;
  }
}
