package com.example.agentflow.ingestion.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.ingestion.application.IngestionService;
import com.example.agentflow.ingestion.application.IngestionStatusDto;
import com.example.agentflow.messaging.application.InboxConsumeResult;
import com.example.agentflow.messaging.application.InboxConsumerAction;
import com.example.agentflow.messaging.application.InboxConsumerService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the S13-12 consumer-lag gauge on {@link DocumentIndexingWorker}: after every poll
 * pass the gauge reads the live consumer (the retained-but-unprocessed record count across the
 * assigned partitions, in records) and never fails the worker. The consumer is a mock.
 */
class DocumentIndexingWorkerLagTest {

  private static final UUID EVENT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID DOCUMENT = UUID.randomUUID();
  private static final TopicPartition PARTITION =
      new TopicPartition(DocumentIndexingWorker.TOPIC, 0);

  private static String envelopeJson() {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"tenantId\":\""
        + TENANT
        + "\",\"payload\":{\"documentId\":\""
        + DOCUMENT
        + "\"}}";
  }

  private static ConsumerRecord<String, String> record(long offset) {
    return new ConsumerRecord<>(DocumentIndexingWorker.TOPIC, 0, offset, "key", envelopeJson());
  }

  private static ConsumerRecords<String, String> batch(ConsumerRecord<String, String>... records) {
    Map<TopicPartition, List<ConsumerRecord<String, String>>> byPartition = new HashMap<>();
    for (ConsumerRecord<String, String> record : records) {
      byPartition
          .computeIfAbsent(
              new TopicPartition(record.topic(), record.partition()), tp -> new ArrayList<>())
          .add(record);
    }
    return new ConsumerRecords<>(byPartition);
  }

  @Test
  void the_gauge_reads_the_live_consumer_after_each_poll_pass() {
    KafkaConsumer<String, String> consumer = mock(KafkaConsumer.class);
    when(consumer.poll(any(Duration.class))).thenReturn(batch());
    when(consumer.assignment()).thenReturn(Set.of(PARTITION));
    when(consumer.endOffsets(Set.of(PARTITION))).thenReturn(Map.of(PARTITION, 10L));
    when(consumer.position(PARTITION)).thenReturn(3L);

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DocumentIndexingWorker worker =
        new DocumentIndexingWorker(
            consumer,
            mock(IngestionService.class),
            mock(InboxConsumerService.class),
            Duration.ofMillis(1),
            null,
            registry);

    worker.pollOnce();

    Gauge gauge = registry.find(DocumentIndexingWorker.CONSUMER_LAG_METRIC).gauge();
    assertThat(gauge).isNotNull();
    // 10 end offset - 3 position = 7 retained-but-unprocessed records
    assertThat(gauge.value()).isEqualTo(7.0);
  }

  @Test
  void the_gauge_reflects_the_post_poll_position_when_a_record_was_acknowledged() {
    KafkaConsumer<String, String> consumer = mock(KafkaConsumer.class);
    when(consumer.poll(any(Duration.class))).thenReturn(batch(record(0)));
    when(consumer.assignment()).thenReturn(Set.of(PARTITION));
    when(consumer.endOffsets(Set.of(PARTITION))).thenReturn(Map.of(PARTITION, 5L));
    when(consumer.position(PARTITION)).thenReturn(1L);

    InboxConsumerService inboxConsumerService = mock(InboxConsumerService.class);
    when(inboxConsumerService.apply(anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              InboxConsumerAction action = invocation.getArgument(2);
              action.execute();
              return InboxConsumeResult.PROCESSED;
            });
    IngestionService ingestionService = mock(IngestionService.class);
    when(ingestionService.index(any(), eq(EVENT)))
        .thenReturn(new IngestionStatusDto(DOCUMENT, "INDEXED", "v1"));

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DocumentIndexingWorker worker =
        new DocumentIndexingWorker(
            consumer, ingestionService, inboxConsumerService, Duration.ofMillis(1), null, registry);

    worker.pollOnce();

    Gauge gauge = registry.find(DocumentIndexingWorker.CONSUMER_LAG_METRIC).gauge();
    // 5 end offset - 1 position = 4 (the acked record does not appear in the gauge)
    assertThat(gauge.value()).isEqualTo(4.0);
  }

  @Test
  void the_gauge_reads_zero_when_the_assignment_is_empty() {
    KafkaConsumer<String, String> consumer = mock(KafkaConsumer.class);
    when(consumer.poll(any(Duration.class))).thenReturn(batch());
    when(consumer.assignment()).thenReturn(Set.of());

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DocumentIndexingWorker worker =
        new DocumentIndexingWorker(
            consumer,
            mock(IngestionService.class),
            mock(InboxConsumerService.class),
            Duration.ofMillis(1),
            null,
            registry);

    worker.pollOnce();

    assertThat(registry.find(DocumentIndexingWorker.CONSUMER_LAG_METRIC).gauge().value())
        .isEqualTo(0.0);
  }

  @Test
  void a_broker_failure_inside_the_lag_read_never_fails_the_worker() {
    KafkaConsumer<String, String> consumer = mock(KafkaConsumer.class);
    when(consumer.poll(any(Duration.class))).thenReturn(batch());
    when(consumer.assignment()).thenReturn(Set.of(PARTITION));
    when(consumer.endOffsets(Set.of(PARTITION)))
        .thenThrow(new IllegalStateException("broker gone"));

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DocumentIndexingWorker worker =
        new DocumentIndexingWorker(
            consumer,
            mock(IngestionService.class),
            mock(InboxConsumerService.class),
            Duration.ofMillis(1),
            null,
            registry);

    // the poll itself succeeds; the gauge keeps its previous (0) value
    worker.pollOnce();

    assertThat(registry.find(DocumentIndexingWorker.CONSUMER_LAG_METRIC).gauge().value())
        .isEqualTo(0.0);
  }

  @Test
  void a_null_registry_construction_registers_no_gauge_but_still_polls() {
    KafkaConsumer<String, String> consumer = mock(KafkaConsumer.class);
    when(consumer.poll(any(Duration.class))).thenReturn(batch());

    DocumentIndexingWorker worker =
        new DocumentIndexingWorker(
            consumer,
            mock(IngestionService.class),
            mock(InboxConsumerService.class),
            Duration.ofMillis(1),
            null,
            null);

    worker.pollOnce(); // must not fail on metering
  }
}
