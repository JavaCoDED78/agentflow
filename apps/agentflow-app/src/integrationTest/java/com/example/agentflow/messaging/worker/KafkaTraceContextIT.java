package com.example.agentflow.messaging.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.agentflow.messaging.application.CorrelationHeaders;
import com.example.agentflow.messaging.application.MessageDeliverer;
import com.example.agentflow.messaging.application.MessagingTracing;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for the S13-10 trace-context contract against a real KRaft broker.
 *
 * <p>The unit tests ({@code MessagingTracingTest}, {@code OutboxServiceTest}, {@code
 * DocumentIndexingWorkerTest}) prove the propagation in isolation; this test proves what the unit
 * tests cannot — that the legs of one trace <b>join on one trace id</b> across a real Kafka
 * boundary: the API-shaped request span, the producer span ({@code KafkaMessageDeliverer#deliver})
 * and the consumer span ({@code DocumentIndexingWorker#handle}) all share the trace, the producer
 * and consumer are parented on the request span, and the correlation/causation ids survive the wire
 * as headers <em>and</em> as span attributes.
 *
 * <p>The outbox leg runs the real production path ({@code OutboxService#enqueue} threading the
 * current span into the event's headers), with the repository mocked only to capture the persisted
 * event — nothing else about the production code is stubbed.
 */
@Testcontainers
public class KafkaTraceContextIT {

  private static final DockerImageName KAFKA_IMAGE =
      DockerImageName.parse("apache/kafka:4.3.1").asCompatibleSubstituteFor("apache/kafka");

  // A fresh, unique topic per test run: the producer creates it and the consumer in this test is
  // the only reader of its records.
  private static final String TOPIC = "agentflow.trace-context-it.v1";

  @Container private final KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);

  /** In-memory span sink recording every ended span. */
  private static final class RecordingSpanExporter implements SpanExporter {
    private final List<SpanData> spans = new CopyOnWriteArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> spansToExport) {
      spans.addAll(spansToExport);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }

    List<SpanData> spans() {
      return List.copyOf(spans);
    }
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  public void request_producer_and_consumer_spans_share_one_trace_across_the_broker()
      throws Exception {
    String correlationId = "corr-it-42";
    String causationId = "cau-it-42";
    UUID tenantId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID eventUuid = UUID.randomUUID();

    RecordingSpanExporter exporter = new RecordingSpanExporter();
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.builder(exporter).build())
            .build();
    Tracer tracer = tracerProvider.get("com.example.agentflow.test");

    String traceparent = null;
    String recordCorrelationId = null;
    String recordCausationId = null;
    CorrelationHeaders persistedHeaders = null;

    // The API leg: one request span, made current exactly like a request handler would.
    Span request = tracer.spanBuilder("API#request").startSpan();
    try (Scope ignored = request.makeCurrent()) {
      // The outbox leg, through the real production path: the current span is threaded into the
      // event's headers by OutboxService#enqueue (no correlation/causation set by the caller).
      OutboxEventRepository repo = mock(OutboxEventRepository.class);
      OutboxService service = new OutboxService(repo);
      OutboxEvent event =
          OutboxEvent.pending(
              eventUuid,
              eventId,
              "workflow.run.requested.v1",
              TOPIC,
              "workflow_run",
              aggregateId,
              tenantId,
              "{\"runId\":\"run-42\"}",
              "{\"correlationId\":\""
                  + correlationId
                  + "\",\"causationId\":\""
                  + causationId
                  + "\"}");

      service.enqueue(event);

      ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
      verify(repo).insert(captor.capture());
      OutboxEvent persisted = captor.getValue();

      // The persisted headers carry the request span's traceparent, i.e. the trace is threaded
      // even though the caller never supplied one.
      persistedHeaders = CorrelationHeaders.parse(persisted.headersJson()).orElseThrow();
      assertThat(persistedHeaders.traceparent()).isNotBlank();
      assertThat(persistedHeaders.correlationId()).isEqualTo(correlationId);
      assertThat(persistedHeaders.causationId()).isEqualTo(causationId);

      // The producer leg: a real KafkaMessageDeliverer publishing to a real broker.
      try (KafkaMessageDeliverer deliverer =
          new KafkaMessageDeliverer(
              producerProps(kafka.getBootstrapServers(), 60_000L), 60_000L, tracerProvider)) {
        assertThat(deliverer.deliver(persisted))
            .isEqualTo(MessageDeliverer.DeliverResult.DELIVERED);
      }

      // The consumer leg: consume the record, mirror the worker — start a span parented on the
      // record's traceparent and record the correlation/causation as attributes.
      try (KafkaConsumer<String, String> consumer = consumer(TOPIC)) {
        consumer.subscribe(List.of(TOPIC));
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(30));
        assertThat(records.count()).isGreaterThan(0);
        ConsumerRecord<String, String> record = records.records(TOPIC).iterator().next();
        assertThat(record.value()).isEqualTo(persisted.payloadJson());
        traceparent = headerValue(record, "traceparent");
        recordCorrelationId = headerValue(record, "correlationId");
        recordCausationId = headerValue(record, "causationId");
        assertThat(traceparent).isEqualTo(persistedHeaders.traceparent());
        assertThat(recordCorrelationId).isEqualTo(correlationId);
        assertThat(recordCausationId).isEqualTo(causationId);
      }

      Span consumerSpan =
          MessagingTracing.startSpan(
              tracerProvider.get("com.example.agentflow.ingestion"),
              "DocumentIndexingWorker#handle",
              MessagingTracing.parentContext(traceparent),
              recordCorrelationId,
              recordCausationId);
      consumerSpan.end();
    } finally {
      request.end();
    }

    // ---- assertions: three spans, one trace -------------------------------------------------
    List<SpanData> spans = exporter.spans();
    SpanData apiRequest = find(spans, "API#request");
    SpanData producer = find(spans, "KafkaMessageDeliverer#deliver");
    SpanData consumer = find(spans, "DocumentIndexingWorker#handle");

    assertThat(producer.getTraceId()).isEqualTo(apiRequest.getTraceId());
    assertThat(consumer.getTraceId()).isEqualTo(apiRequest.getTraceId());

    // The producer and consumer spans are parented on the API request span (via the traceparent
    // threaded into the record at enqueue time), not on each other.
    assertThat(producer.getParentSpanContext().getSpanId())
        .isEqualTo(apiRequest.getSpanContext().getSpanId());
    assertThat(consumer.getParentSpanContext().getSpanId())
        .isEqualTo(apiRequest.getSpanContext().getSpanId());

    // The correlation/causation ids survive the wire as record headers and as span attributes.
    assertThat(producer.getAttributes().get(AttributeKey.stringKey("correlation_id")))
        .isEqualTo(correlationId);
    assertThat(producer.getAttributes().get(AttributeKey.stringKey("causation_id")))
        .isEqualTo(causationId);
    assertThat(consumer.getAttributes().get(AttributeKey.stringKey("correlation_id")))
        .isEqualTo(correlationId);
    assertThat(consumer.getAttributes().get(AttributeKey.stringKey("causation_id")))
        .isEqualTo(causationId);

    // The wire carries the request span's trace context — nothing invented (the trace/span ids
    // match the request span; the flags byte is whatever the SDK sampled, not something we invent).
    assertThat(traceparent)
        .as("the record carries the request span's traceparent")
        .startsWith(
            "00-"
                + apiRequest.getSpanContext().getTraceId()
                + "-"
                + apiRequest.getSpanContext().getSpanId()
                + "-");
    assertThat(persistedHeaders.traceparent()).isEqualTo(traceparent);

    tracerProvider.close();
  }

  /** Find the (unique) ended span with the given name. */
  private static SpanData find(List<SpanData> spans, String name) {
    return spans.stream()
        .filter(s -> name.equals(s.getName()))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("no span named " + name + " in " + spans.size() + " spans"));
  }

  private static Map<String, Object> producerProps(
      String bootstrapServers, long deliveryTimeoutMs) {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) deliveryTimeoutMs);
    return props;
  }

  private KafkaConsumer<String, String> consumer(String topic) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-trace-context-it-" + UUID.randomUUID());
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    return new KafkaConsumer<>(props);
  }

  private static String headerValue(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
