package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MessagingTracing} (S13-10) — the W3C trace-context plumbing of the
 * messaging boundary. Uses an in-memory {@code SdkTracerProvider} (see {@link
 * RecordingSpanExporter}) so the assertions are about the spans the SDK actually creates, never a
 * mock.
 */
class MessagingTracingTest {

  private static final String VALID_TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Test
  void currentTraceparent_is_null_when_no_span_is_active() {
    assertThat(MessagingTracing.currentTraceparent()).isNull();
  }

  @Test
  void currentTraceparent_returns_the_active_spans_context() {
    try (RecordingSpanExporter.Fixture fixture = RecordingSpanExporter.fixture()) {
      Span span =
          MessagingTracing.startSpan(
              fixture.provider.get("test"), "test-op", Context.root(), null, null);
      SpanContext ownContext = span.getSpanContext();
      try (Scope ignored = span.makeCurrent()) {
        String traceparent = MessagingTracing.currentTraceparent();
        assertThat(traceparent).isNotNull();
        SpanContext parsed = parseTraceparent(traceparent);
        assertThat(parsed.getTraceId()).isEqualTo(ownContext.getTraceId());
        assertThat(parsed.getSpanId()).isEqualTo(ownContext.getSpanId());
      } finally {
        span.end();
      }
    }
  }

  private static SpanContext parseTraceparent(String traceparent) {
    return io.opentelemetry.api.trace.Span.fromContext(
            io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()
                .extract(
                    Context.root(),
                    java.util.Map.of("traceparent", traceparent),
                    new io.opentelemetry.context.propagation.TextMapGetter<
                        java.util.Map<String, String>>() {
                      @Override
                      public Iterable<String> keys(java.util.Map<String, String> carrier) {
                        return carrier.keySet();
                      }

                      @Override
                      public String get(java.util.Map<String, String> carrier, String key) {
                        return carrier.get(key);
                      }
                    }))
        .getSpanContext();
  }

  @Test
  void currentTraceparent_returns_the_current_span_not_the_parent() {
    try (RecordingSpanExporter.Fixture fixture = RecordingSpanExporter.fixture()) {
      Context parent = MessagingTracing.parentContext(VALID_TRACEPARENT);
      Span span =
          MessagingTracing.startSpan(fixture.provider.get("test"), "child-op", parent, null, null);
      SpanContext ownContext = span.getSpanContext();
      try (Scope ignored = span.makeCurrent()) {
        String traceparent = MessagingTracing.currentTraceparent();
        assertThat(traceparent).contains(ownContext.getTraceId()).contains(ownContext.getSpanId());
      } finally {
        span.end();
      }
    }
  }

  @Test
  void parentContext_blank_or_null_yields_a_root_context() {
    assertThat(MessagingTracing.parentContext(null)).isEqualTo(Context.root());
    assertThat(MessagingTracing.parentContext("")).isEqualTo(Context.root());
    assertThat(MessagingTracing.parentContext("   ")).isEqualTo(Context.root());
  }

  @Test
  void parentContext_malformed_yields_no_parent_span() {
    Context context = MessagingTracing.parentContext("garbage");
    assertThat(Span.fromContext(context).getSpanContext().isValid()).isFalse();
  }

  @Test
  void parentContext_valid_yields_remote_span_context() {
    Context context = MessagingTracing.parentContext(VALID_TRACEPARENT);
    SpanContext parent = Span.fromContext(context).getSpanContext();
    assertThat(parent.isValid()).isTrue();
    assertThat(parent.getTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(parent.getSpanId()).isEqualTo("00f067aa0ba902b7");
    assertThat(parent.isSampled()).isTrue();
    assertThat(parent.isRemote()).isTrue();
  }

  @Test
  void startSpan_is_null_without_a_tracer() {
    assertThat(MessagingTracing.startSpan(null, "op", Context.root(), null, null)).isNull();
  }

  @Test
  void startSpan_sets_name_parent_and_correlation_attributes() {
    try (RecordingSpanExporter.Fixture fixture = RecordingSpanExporter.fixture()) {
      Context parent = MessagingTracing.parentContext(VALID_TRACEPARENT);
      Span span =
          MessagingTracing.startSpan(
              fixture.provider.get("test"),
              "messaging-boundary-op",
              parent,
              "correlation-123",
              "causation-456");
      span.end();

      List<SpanData> spans = fixture.spans();
      assertThat(spans).hasSize(1);
      SpanData recorded = spans.get(0);
      assertThat(recorded.getName()).isEqualTo("messaging-boundary-op");
      assertThat(recorded.getParentSpanContext().isValid()).isTrue();
      assertThat(recorded.getParentSpanContext().getTraceId())
          .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
      assertThat(recorded.getParentSpanContext().getSpanId()).isEqualTo("00f067aa0ba902b7");
      assertThat(recorded.getAttributes().get(AttributeKey.stringKey("correlation_id")))
          .isEqualTo("correlation-123");
      assertThat(recorded.getAttributes().get(AttributeKey.stringKey("causation_id")))
          .isEqualTo("causation-456");
    }
  }

  @Test
  void startSpan_omits_absent_or_blank_correlation_attributes() {
    try (RecordingSpanExporter.Fixture fixture = RecordingSpanExporter.fixture()) {
      Span span =
          MessagingTracing.startSpan(fixture.provider.get("test"), "op", Context.root(), "", "   ");
      span.end();

      List<SpanData> spans = fixture.spans();
      assertThat(spans).hasSize(1);
      SpanData recorded = spans.get(0);
      assertThat(recorded.getAttributes().get(AttributeKey.stringKey("correlation_id"))).isNull();
      assertThat(recorded.getAttributes().get(AttributeKey.stringKey("causation_id"))).isNull();
    }
  }

  @Test
  void startSpan_root_context_has_no_parent() {
    try (RecordingSpanExporter.Fixture fixture = RecordingSpanExporter.fixture()) {
      Span span =
          MessagingTracing.startSpan(
              fixture.provider.get("test"), "root-op", Context.root(), null, null);
      span.end();

      List<SpanData> spans = fixture.spans();
      assertThat(spans).hasSize(1);
      assertThat(spans.get(0).getParentSpanContext().isValid()).isFalse();
    }
  }
}
