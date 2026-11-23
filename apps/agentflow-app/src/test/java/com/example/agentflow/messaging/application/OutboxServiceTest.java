package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DB-free unit tests for the outbox boundary's trace-context threading (S13-10).
 *
 * <p>{@link OutboxService} is the single application-facing way a domain module appends a queued
 * event. When an event is queued from a traced request — or from a worker span (the indexing worker
 * makes its span current, so the {@code indexed} event inherits the worker's span) — the event must
 * carry the active span's W3C traceparent, otherwise the publish/consume legs of the trace would
 * strand as a fresh root. A caller that supplies its own traceparent (the HTTP create-run path)
 * always wins; a corrupt producer payload is left untouched (the deliverer warns and drops it); a
 * blank event gets the traceparent threaded in, with correlation/causation preserved when present.
 */
class OutboxServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID EVENT = UUID.randomUUID();

  private static OutboxEvent pendingEvent(String headersJson) {
    return OutboxEvent.pending(
        UUID.randomUUID(),
        EVENT,
        "workflow.run.requested.v1",
        "outbox-events.workflow.run.v1",
        "workflow_run",
        UUID.randomUUID(),
        TENANT,
        "{\"runId\":\"run-42\"}",
        headersJson);
  }

  @Test
  void enqueue_threads_the_active_spans_traceparent_into_an_unthreaded_event() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    OutboxService service = new OutboxService(repo);
    String correlationId = UUID.randomUUID().toString();
    try (RecordingSpanExporter.Fixture fixture = RecordingSpanExporter.fixture()) {
      Span span =
          MessagingTracing.startSpan(fixture.provider.get("test"), "request-op", null, null, null);
      String headersJson = CorrelationHeaders.of(correlationId, null, null).toJson();
      try (Scope ignored = span.makeCurrent()) {
        service.enqueue(pendingEvent(headersJson));
      } finally {
        span.end();
      }

      // The thread-context call happened inside the traced span, so the persisted headers now
      // carry the span's traceparent alongside the preserved correlationId.
      ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
      verify(repo).insert(captor.capture());
      OutboxEvent inserted = captor.getValue();
      assertThat(inserted.headersJson()).contains("traceparent");
      String traceparent = parseTraceparent(inserted.headersJson());
      assertThat(traceparent).isNotNull();
      assertThat(traceparent).contains("00-");
      assertThat(inserted.headersJson()).contains(correlationId);
    }
  }

  @Test
  void enqueue_leaves_a_caller_threaded_traceparent_untouched() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    OutboxService service = new OutboxService(repo);
    UUID runId = UUID.randomUUID();
    String explicit = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    String headersJson = CorrelationHeaders.of(runId.toString(), null, explicit).toJson();

    service.enqueue(pendingEvent(headersJson));

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(repo).insert(captor.capture());
    assertThat(captor.getValue().headersJson()).isEqualTo(headersJson);
  }

  @Test
  void enqueue_threads_a_traceparent_into_a_blank_headers_payload() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    OutboxService service = new OutboxService(repo);
    try (RecordingSpanExporter.Fixture fixture = RecordingSpanExporter.fixture()) {
      Span span =
          MessagingTracing.startSpan(fixture.provider.get("test"), "request-op", null, null, null);
      try (Scope ignored = span.makeCurrent()) {
        service.enqueue(pendingEvent(null));
      } finally {
        span.end();
      }

      ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
      verify(repo).insert(captor.capture());
      assertThat(parseTraceparent(captor.getValue().headersJson())).isNotNull();
    }
  }

  @Test
  void enqueue_leaves_an_event_unchanged_without_an_active_span() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    OutboxService service = new OutboxService(repo);
    String headersJson = CorrelationHeaders.of(UUID.randomUUID().toString(), null, null).toJson();

    service.enqueue(pendingEvent(headersJson));

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(repo).insert(captor.capture());
    assertThat(captor.getValue().headersJson()).isEqualTo(headersJson);
  }

  @Test
  void enqueue_leaves_a_corrupt_payload_untouched() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    OutboxService service = new OutboxService(repo);
    String corrupt = "{\"correlationId\":12345, not json";

    service.enqueue(pendingEvent(corrupt));

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(repo).insert(captor.capture());
    assertThat(captor.getValue().headersJson()).isEqualTo(corrupt);
  }

  private static String parseTraceparent(String headersJson) {
    CorrelationHeaders headers = CorrelationHeaders.parse(headersJson).orElse(null);
    return headers == null ? null : headers.traceparent();
  }
}
