package com.example.agentflow.messaging.application;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;

/**
 * W3C trace-context plumbing for the messaging boundary (S13-10): the single place that threads a
 * span across the outbox &rarr; Kafka &rarr; consumer boundary (and into the producer side) so a
 * trace started inside the HTTP request reaches the worker and the next publish span.
 *
 * <p>The propagated contract is deliberately minimal — one W3C {@code traceparent} (trace-id +
 * span-id + flags), the same shape {@link CorrelationHeaders} already forwards as the {@code
 * traceparent} correlation field. Correlation/causation ids are threaded as bounded,
 * closed-vocabulary span attributes ({@code correlation_id} / {@code causation_id}), never as
 * metric labels (metrics never carry identifiers — invariant 9). No payload, document text or
 * secret is ever placed into a span (invariant 8).
 *
 * <p>Pure library code (no Spring / Kafka), so it is trivially unit-testable without a broker, a
 * database or a context-carrier.
 */
public final class MessagingTracing {

  /** Span attribute for the operation-chain correlation id. */
  public static final String CORRELATION_ID_ATTRIBUTE = "correlation_id";

  /** Span attribute for the causation (triggering event) id. */
  public static final String CAUSATION_ID_ATTRIBUTE = "causation_id";

  private static final TextMapSetter<Map<String, String>> GENERIC_SETTER =
      (carrier, key, value) -> carrier.put(key, value);

  private static final TextMapGetter<Map<String, String>> GENERIC_GETTER =
      new TextMapGetter<Map<String, String>>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier.get(key);
        }
      };

  private MessagingTracing() {}

  /**
   * Return the W3C traceparent of the currently active span, or {@code null} when there is no valid
   * active span (background threads, non-traced contexts, disabled tracing). The value is the exact
   * {@code traceparent} string a downstream consumer would use as its parent — ready to be stored
   * in {@link CorrelationHeaders#traceparent()} or forwarded as a record header.
   */
  public static String currentTraceparent() {
    SpanContext context = Span.current().getSpanContext();
    if (context == null || !context.isValid()) {
      return null;
    }
    Map<String, String> carrier = new HashMap<>();
    W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, GENERIC_SETTER);
    String traceparent = carrier.get("traceparent");
    return (traceparent == null || traceparent.isBlank()) ? null : traceparent;
  }

  /**
   * Parse a W3C traceparent string into a context usable as the parent of a new span. {@code null}
   * / blank / malformed input yields {@link Context#root()} — the caller then creates a root span,
   * which is the honest fallback (a consumer with no valid trace parent cannot be told one that
   * does not exist).
   *
   * @param traceparent the raw W3C traceparent, or {@code null}/blank/malformed
   * @return a context whose span parent is the given trace-id/span-id, or {@link Context#root()}
   */
  public static Context parentContext(String traceparent) {
    if (traceparent == null || traceparent.isBlank()) {
      return Context.root();
    }
    Map<String, String> carrier = new HashMap<>();
    carrier.put("traceparent", traceparent);
    return W3CTraceContextPropagator.getInstance().extract(Context.root(), carrier, GENERIC_GETTER);
  }

  /**
   * Start the named span with the given parent and correlation/causation attributes.
   *
   * @param tracer the tracer for the messaging boundary, or {@code null} when tracing is disabled
   * @param name the span name (a closed operation vocabulary, never a payload — invariant 8)
   * @param parent the extracted parent context (see {@link #parentContext(String)})
   * @param correlationId the correlation id, or {@code null}/blank to omit the attribute
   * @param causationId the causation id, or {@code null}/blank to omit the attribute
   * @return the new span, or {@code null} when {@code tracer} is {@code null}
   */
  public static Span startSpan(
      Tracer tracer, String name, Context parent, String correlationId, String causationId) {
    if (tracer == null) {
      return null;
    }
    SpanBuilder builder =
        tracer.spanBuilder(name).setParent(parent == null ? Context.root() : parent);
    if (correlationId != null && !correlationId.isBlank()) {
      builder.setAttribute(AttributeKey.stringKey(CORRELATION_ID_ATTRIBUTE), correlationId);
    }
    if (causationId != null && !causationId.isBlank()) {
      builder.setAttribute(AttributeKey.stringKey(CAUSATION_ID_ATTRIBUTE), causationId);
    }
    return builder.startSpan();
  }
}
