package com.example.agentflow.ingestion.worker;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.ingestion.application.IngestionEventKey;
import com.example.agentflow.ingestion.application.IngestionService;
import com.example.agentflow.ingestion.application.IngestionStatusDto;
import com.example.agentflow.messaging.application.DlqService;
import com.example.agentflow.messaging.application.InboxConsumeResult;
import com.example.agentflow.messaging.application.InboxConsumerService;
import com.example.agentflow.messaging.application.InboxDedupService;
import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The indexing worker (S10-13): the Kafka consumer of {@code
 * agentflow.document.ingestion-requested.v1} that drives the extract &rarr; chunk &rarr; embed
 * &rarr; index pipeline, idempotently.
 *
 * <p><b>Ack only after a durable outcome (invariant 3).</b> Every record is funnelled through the
 * consumer error-handling boundary ({@link InboxConsumerService}, S06-11): the {@code
 * (consumer_name, event_id)} inbox claim, the indexing pass and the inbox completion commit in one
 * transaction, so a duplicate delivery (at-least-once, {@code ADR-003}) is deduplicated before any
 * side effect re-runs. {@code PROCESSED} / {@code OTHER_CLAIMED} acknowledge (offset committed);
 * {@code RETRY} / {@code DEAD_LETTER} are held — the offset is <em>not</em> committed and the DLQ
 * record keeps the failure for investigation (S06-11).
 *
 * <p><b>A held record is skipped in-session only.</b> The worker seeks past it without committing
 * (a restart redelivers it; the inbox then reports {@code OTHER_CLAIMED} and it is acknowledged),
 * and re-reads every record after it on the next poll — a held record never silently drops the rest
 * of the batch. An envelope with no event identity at all (malformed beyond the contract) is logged
 * and committed past: it has no dedup anchor, so skipping is the only choice that does not block
 * the partition forever.
 *
 * <p><b>No release call is needed on failure.</b> The document claim ({@code QUEUED -> PROCESSING})
 * is part of the indexing pass inside the inbox transaction: a failed pass rolls the claim back
 * with everything else, so the document returns to its prior state on its own — the lifecycle's
 * "worker releases the lease" is the transaction rollback here (the document lease columns remain
 * the recorded follow-up of {@code document-lifecycle.md}).
 *
 * <p>The consumer uses the bare Kafka client (the {@code KafkaMessageDeliverer} precedent, S06-09)
 * with manual offset commits, the {@code agentflow.ingestion-worker} group and {@code
 * auto.offset.reset=earliest} (a fresh group replays — safe: every step is idempotent). It joins
 * the cluster of {@code agentflow.kafka.producer.bootstrap-servers} (the broker is one cluster; the
 * group and the topic are fixed by {@code docs/architecture/kafka-topics.md}). The bean exists only
 * in the {@code ingestion-worker} role ({@code agentflow.worker.ingestion-loop-enabled}) and only
 * when a {@link DataSource} exists, so the {@code api} role and the DB-less skeleton never
 * construct a consumer.
 */
@Component
@ConditionalOnProperty(
    prefix = "agentflow.worker",
    name = "ingestion-loop-enabled",
    havingValue = "true")
@ConditionalOnBean(DataSource.class)
public class DocumentIndexingWorker {

  private static final Logger log = LoggerFactory.getLogger(DocumentIndexingWorker.class);

  /** The inbox consumer identity — the group {@code docs/architecture/kafka-topics.md} fixes. */
  static final String CONSUMER_NAME = "agentflow.ingestion-worker";

  /** The versioned topic this worker consumes (the event contract). */
  static final String TOPIC = "agentflow.document.ingestion-requested.v1";

  private final KafkaConsumer<String, String> consumer;
  private final IngestionService ingestionService;
  private final InboxConsumerService inboxConsumerService;
  private final Duration pollTimeout;

  /**
   * Spring-managed constructor: builds the consumer from {@code agentflow.kafka.producer.*
   * (bootstrap-servers)} and the worker settings, and assembles the inbox boundary from the
   * messaging beans (the {@code InboxConsumerService} is constructed at the worker layer, never a
   * Spring bean itself, S06-11).
   *
   * @param properties the validated agent-flow configuration
   * @param ingestionService the indexing use case
   * @param inboxRepository the inbox write/read path
   * @param dlqService the dead-letter store
   * @param dataSource the application data source (the worker exists only with one)
   */
  @Autowired
  public DocumentIndexingWorker(
      AgentFlowProperties properties,
      IngestionService ingestionService,
      InboxMessageRepository inboxRepository,
      DlqService dlqService,
      DataSource dataSource) {
    this(
        new KafkaConsumer<>(consumerProps(properties)),
        ingestionService,
        new InboxConsumerService(
            new InboxDedupService(
                inboxRepository,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                properties.getWorker().getIngestionMaxAttempts()),
            dlqService,
            inboxRepository),
        Duration.ofMillis(properties.getWorker().getIngestionPollTimeoutMs()));
  }

  /**
   * Test constructor: drive the worker with a pre-built consumer (for example one pointed at a
   * Testcontainers broker) and a pre-assembled inbox boundary.
   *
   * @param consumer the Kafka consumer (manual commits)
   * @param ingestionService the indexing use case
   * @param inboxConsumerService the assembled inbox boundary
   * @param pollTimeout the long-poll timeout of {@link #pollOnce()}
   */
  DocumentIndexingWorker(
      KafkaConsumer<String, String> consumer,
      IngestionService ingestionService,
      InboxConsumerService inboxConsumerService,
      Duration pollTimeout) {
    this.consumer = consumer;
    this.ingestionService = ingestionService;
    this.inboxConsumerService = inboxConsumerService;
    this.pollTimeout = pollTimeout;
  }

  /**
   * Poll once and handle every returned record: acknowledge the durable outcomes, hold the first
   * failed one (in-session skip, uncommitted offset) and re-read the rest next poll.
   */
  void pollOnce() {
    ConsumerRecords<String, String> records = consumer.poll(pollTimeout);
    if (records.isEmpty()) {
      return;
    }
    Map<TopicPartition, OffsetAndMetadata> acked = new HashMap<>();
    Set<TopicPartition> repositioned = new HashSet<>();
    boolean held = false;
    for (ConsumerRecord<String, String> record : records) {
      TopicPartition partition = new TopicPartition(record.topic(), record.partition());
      if (held) {
        // A previous record was held: leave this one unprocessed and re-read it next poll (the
        // position moved past the whole batch at poll time, so rewind to its first unprocessed
        // record — once per partition).
        if (repositioned.add(partition)) {
          consumer.seek(partition, record.offset());
        }
        continue;
      }
      if (handle(record) == HandleOutcome.ACK) {
        acked.put(partition, new OffsetAndMetadata(record.offset() + 1));
      } else {
        consumer.seek(partition, record.offset() + 1);
        repositioned.add(partition);
        held = true;
      }
    }
    if (!acked.isEmpty()) {
      consumer.commitSync(acked);
    }
  }

  /**
   * Handle one record: parse the envelope, claim it in the inbox and run the indexing pass inside
   * the claim transaction.
   *
   * @return {@link HandleOutcome#ACK} when the outcome is durable (processed, duplicate, or an
   *     identity-less record skipped past); {@link HandleOutcome#HELD} when the pass failed and the
   *     record is held uncommitted (DLQ-recorded)
   */
  private HandleOutcome handle(ConsumerRecord<String, String> record) {
    Optional<IngestionEventEnvelope> envelope = IngestionEventEnvelope.parse(record.value());
    if (envelope.isEmpty()) {
      log.error(
          "ingestion record {}-{} offset {} has no event identity (eventId/tenantId/payload.documentId);"
              + " skipping past it — the envelope contract is violated",
          record.topic(),
          record.partition(),
          record.offset());
      return HandleOutcome.ACK;
    }
    IngestionEventEnvelope parsed = envelope.get();
    InboxMessage claim = InboxMessage.claiming(CONSUMER_NAME, parsed.eventId());
    InboxConsumeResult result =
        inboxConsumerService.apply(
            TOPIC,
            claim,
            () -> {
              IngestionStatusDto status =
                  ingestionService.index(
                      new IngestionEventKey(parsed.tenantId(), parsed.documentId()),
                      parsed.eventId());
              return status.status();
            });
    log.debug(
        "ingestion event {} (document {}): {}", parsed.eventId(), parsed.documentId(), result);
    return switch (result) {
      case PROCESSED, OTHER_CLAIMED -> HandleOutcome.ACK;
      case RETRY, DEAD_LETTER -> HandleOutcome.HELD;
    };
  }

  /**
   * Interrupt a long poll so the loop notices the shutdown flag ( {@link
   * IngestionWorkerLifecycle#stop()}); the in-flight record still finishes — {@code wakeup} aborts
   * the wait, never the processing.
   */
  void wakeup() {
    consumer.wakeup();
  }

  /** Close the consumer (flushes nothing — commits are manual — and stops the network threads). */
  @PreDestroy
  void close() {
    consumer.close();
  }

  /** The consumer client properties: one cluster, the documented group, manual commits. */
  private static Properties consumerProps(AgentFlowProperties properties) {
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        properties.getKafka().getProducer().getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, CONSUMER_NAME);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    // A fresh group replays from the earliest retained record: every step of the pipeline is
    // idempotent, so replay is safe; "none" would fail a first deploy and "latest" could drop an
    // event between the deploy and the first poll.
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    return props;
  }

  /** Whether a handled record's outcome is durable enough to acknowledge. */
  private enum HandleOutcome {
    ACK,
    HELD
  }
}
