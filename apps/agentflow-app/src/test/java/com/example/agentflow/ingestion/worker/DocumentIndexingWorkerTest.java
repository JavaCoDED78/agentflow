package com.example.agentflow.ingestion.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.agentflow.ingestion.application.IngestionEventKey;
import com.example.agentflow.ingestion.application.IngestionService;
import com.example.agentflow.ingestion.application.IngestionStatusDto;
import com.example.agentflow.messaging.application.InboxConsumeResult;
import com.example.agentflow.messaging.application.InboxConsumerAction;
import com.example.agentflow.messaging.application.InboxConsumerService;
import com.example.agentflow.messaging.domain.InboxMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the Kafka consumer loop of the indexing worker (S10-13) with a mocked consumer and
 * inbox boundary: the ack/hold contract — a durable outcome ({@code PROCESSED} / {@code
 * OTHER_CLAIMED}) commits the offset, a failed one ({@code RETRY} / {@code DEAD_LETTER}) is held
 * uncommitted and skipped in-session, an identity-less record is committed past, and records after
 * a held one are re-read, never dropped.
 */
class DocumentIndexingWorkerTest {

  private static final UUID EVENT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID DOCUMENT = UUID.randomUUID();

  private KafkaConsumer<String, String> consumer;
  private IngestionService ingestionService;
  private InboxConsumerService inboxConsumerService;
  private DocumentIndexingWorker worker;

  @BeforeEach
  void setUp() {
    consumer = mock(KafkaConsumer.class);
    ingestionService = mock(IngestionService.class);
    inboxConsumerService = mock(InboxConsumerService.class);
    worker =
        new DocumentIndexingWorker(
            consumer, ingestionService, inboxConsumerService, Duration.ofMillis(1));
  }

  private static String envelopeJson() {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"tenantId\":\""
        + TENANT
        + "\",\"payload\":{\"documentId\":\""
        + DOCUMENT
        + "\"}}";
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

  private static ConsumerRecord<String, String> record(long offset, String value) {
    return new ConsumerRecord<>(DocumentIndexingWorker.TOPIC, 0, offset, "key", value);
  }

  @Test
  @SuppressWarnings("unchecked")
  void a_processed_record_is_acknowledged_after_the_durable_outcome() {
    when(consumer.poll(any(Duration.class))).thenReturn(batch(record(0, envelopeJson())));
    when(inboxConsumerService.apply(anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              InboxConsumerAction action = invocation.getArgument(2);
              action.execute();
              return InboxConsumeResult.PROCESSED;
            });
    when(ingestionService.index(any(), eq(EVENT)))
        .thenReturn(new IngestionStatusDto(DOCUMENT, "INDEXED", "v1"));

    worker.pollOnce();

    // The pass ran with the event's identity and the outcome was the recorded status.
    verify(ingestionService).index(new IngestionEventKey(TENANT, DOCUMENT), EVENT);
    ArgumentCaptor<InboxMessage> claim = ArgumentCaptor.forClass(InboxMessage.class);
    verify(inboxConsumerService).apply(eq(DocumentIndexingWorker.TOPIC), claim.capture(), any());
    assertThat(claim.getValue().consumerName()).isEqualTo(DocumentIndexingWorker.CONSUMER_NAME);
    assertThat(claim.getValue().eventId()).isEqualTo(EVENT);
    // The durable outcome is acknowledged: the offset after the record is committed.
    verify(consumer)
        .commitSync(
            Map.of(new TopicPartition(DocumentIndexingWorker.TOPIC, 0), new OffsetAndMetadata(1)));
  }

  @Test
  void a_held_record_is_not_committed_and_is_skipped_in_session_only() {
    when(consumer.poll(any(Duration.class))).thenReturn(batch(record(5, envelopeJson())));
    when(inboxConsumerService.apply(anyString(), any(), any()))
        .thenReturn(InboxConsumeResult.RETRY);

    worker.pollOnce();

    verify(consumer, never()).commitSync(any(Map.class));
    // Skipped in-session (a restart redelivers: the inbox row exists, so it acks as a duplicate).
    verify(consumer).seek(new TopicPartition(DocumentIndexingWorker.TOPIC, 0), 6L);
    verifyNoInteractions(ingestionService);
  }

  @Test
  void a_dead_lettered_record_is_held_like_a_retried_one() {
    when(consumer.poll(any(Duration.class))).thenReturn(batch(record(0, envelopeJson())));
    when(inboxConsumerService.apply(anyString(), any(), any()))
        .thenReturn(InboxConsumeResult.DEAD_LETTER);

    worker.pollOnce();

    verify(consumer, never()).commitSync(any(Map.class));
    verify(consumer).seek(new TopicPartition(DocumentIndexingWorker.TOPIC, 0), 1L);
  }

  @Test
  void an_identity_less_record_is_committed_past() {
    when(consumer.poll(any(Duration.class))).thenReturn(batch(record(0, "not json")));

    worker.pollOnce();

    verify(consumer)
        .commitSync(
            Map.of(new TopicPartition(DocumentIndexingWorker.TOPIC, 0), new OffsetAndMetadata(1)));
    verifyNoInteractions(inboxConsumerService, ingestionService);
  }

  @Test
  void records_after_a_held_one_are_re_read_never_dropped() {
    when(consumer.poll(any(Duration.class)))
        .thenReturn(batch(record(0, envelopeJson()), record(1, envelopeJson())));
    when(inboxConsumerService.apply(anyString(), any(), any()))
        .thenReturn(InboxConsumeResult.RETRY);

    worker.pollOnce();

    // The first record is held (in-session skip to offset 1, uncommitted); the second was never
    // processed, so the partition is left positioned at it for the next poll.
    verify(consumer).seek(new TopicPartition(DocumentIndexingWorker.TOPIC, 0), 1L);
    verify(consumer, never()).commitSync(any(Map.class));
  }

  @Test
  void an_empty_poll_commits_nothing() {
    when(consumer.poll(any(Duration.class))).thenReturn(batch());

    worker.pollOnce();

    verify(consumer, never()).commitSync(any(Map.class));
    verify(consumer, never()).seek(any(TopicPartition.class), anyLong());
  }
}
