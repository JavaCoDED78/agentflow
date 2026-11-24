package com.example.agentflow.ingestion.worker;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.ingestion.application.IngestionEventKey;
import com.example.agentflow.ingestion.application.IngestionService;
import com.example.agentflow.ingestion.application.IngestionStatusDto;
import com.example.agentflow.messaging.application.DlqService;
import com.example.agentflow.messaging.application.InboxConsumeResult;
import com.example.agentflow.messaging.application.InboxConsumerService;
import com.example.agentflow.messaging.application.InboxDedupService;
import com.example.agentflow.messaging.application.MessagingTracing;
import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
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
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 *
 * <p><b>S13-12 instrumentation.</b> {@code agentflow.messaging.consumer.lag.count} is a gauge over
 * the live consumer, re-read after every poll pass (the retained-but-unprocessed record count
 * across the assigned partitions, in records; no labels). Consumer-side failures are counted by the
 * {@code InboxConsumerService} on the shared registry ({@code agentflow.messaging.consumer.
 * processing.failures.total{outcome}} — bounded disposition label, never an identifier). No
 * identifier appears in any label (invariant 9); metering never fails a poll.
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

  /**
   * The consumer-lag gauge (S13-12): the total retained-but-unprocessed record count across the
   * worker's assigned partitions, in records. A gauge over the live consumer, re-read after every
   * poll pass — no label, the whole loop is the only tagged consumer surface.
   */
  static final String CONSUMER_LAG_METRIC = "agentflow.messaging.consumer.lag.count";

  private final KafkaConsumer<String, String> consumer;
  private final IngestionService ingestionService;
  private final InboxConsumerService inboxConsumerService;
  private final Duration pollTimeout;
  private final Tracer tracer;
  private final MeterRegistry meterRegistry;
  private final java.util.concurrent.atomic.AtomicLong lastKnownLag =
      new java.util.concurrent.atomic.AtomicLong(0L);

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
   * @param tracerProvider the OTel SDK tracer provider, or {@code null} (no spans)
   * @param meterRegistry the registry the S13-12 lag gauge registers on; may be {@code null} (a
   *     throwaway registry and no gauge — S13-11 idiom)
   */
  @Autowired
  public DocumentIndexingWorker(
      AgentFlowProperties properties,
      IngestionService ingestionService,
      InboxMessageRepository inboxRepository,
      DlqService dlqService,
      DataSource dataSource,
      ObjectProvider<SdkTracerProvider> tracerProvider,
      @Autowired(required = false) MeterRegistry meterRegistry) {
    this(
        new KafkaConsumer<>(consumerProps(properties)),
        ingestionService,
        new InboxConsumerService(
            new InboxDedupService(
                inboxRepository,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                properties.getWorker().getIngestionMaxAttempts()),
            dlqService,
            inboxRepository,
            meterRegistry),
        Duration.ofMillis(properties.getWorker().getIngestionPollTimeoutMs()),
        tracerProvider.getIfAvailable(),
        meterRegistry);
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
    this(consumer, ingestionService, inboxConsumerService, pollTimeout, null, null);
  }

  /**
   * Test constructor with an explicit OTel tracer provider (e.g. an in-memory provider recording
   * spans in a unit/integration test).
   *
   * @param consumer the Kafka consumer (manual commits)
   * @param ingestionService the indexing use case
   * @param inboxConsumerService the assembled inbox boundary
   * @param pollTimeout the long-poll timeout of {@link #pollOnce()}
   * @param tracerProvider the OTel SDK tracer provider, or {@code null} (no spans)
   */
  DocumentIndexingWorker(
      KafkaConsumer<String, String> consumer,
      IngestionService ingestionService,
      InboxConsumerService inboxConsumerService,
      Duration pollTimeout,
      SdkTracerProvider tracerProvider) {
    this(consumer, ingestionService, inboxConsumerService, pollTimeout, tracerProvider, null);
  }

  /**
   * Full constructor: also injects the {@link MeterRegistry} the lag gauge registers on. A {@code
   * null} registry is treated as "no shared registry" (S13-11 idiom): the consumer-failure counters
   * fall back to a throwaway one and the lag gauge is simply not registered.
   *
   * @param consumer the Kafka consumer (manual commits)
   * @param ingestionService the indexing use case
   * @param inboxConsumerService the assembled inbox boundary
   * @param pollTimeout the long-poll timeout of {@link #pollOnce()}
   * @param tracerProvider the OTel SDK tracer provider, or {@code null} (no spans)
   * @param meterRegistry the registry the lag gauge registers on; may be {@code null}
   */
  DocumentIndexingWorker(
      KafkaConsumer<String, String> consumer,
      IngestionService ingestionService,
      InboxConsumerService inboxConsumerService,
      Duration pollTimeout,
      SdkTracerProvider tracerProvider,
      MeterRegistry meterRegistry) {
    this.consumer = consumer;
    this.ingestionService = ingestionService;
    this.inboxConsumerService = inboxConsumerService;
    this.pollTimeout = pollTimeout;
    this.tracer =
        tracerProvider == null ? null : tracerProvider.get("com.example.agentflow.ingestion");
    this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
    if (meterRegistry != null) {
      registerLagGauge();
    }
  }

  /**
   * Poll once and handle every returned record: acknowledge the durable outcomes, hold the first
   * failed one (in-session skip, uncommitted offset) and re-read the rest next poll.
   */
  void pollOnce() {
    try {
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
    } finally {
      recordConsumerLag();
    }
  }

  /**
   * Register the S13-12 consumer-lag gauge with the shared registry: a periodic read of the live
   * consumer (the retained-but-unprocessed record count across the assigned partitions, in
   * records), never a cached counter. The gauge re-reads the last recorded value, so the producer
   * side of the gauge never touches the broker.
   */
  private void registerLagGauge() {
    Gauge.builder(CONSUMER_LAG_METRIC, this, (worker) -> (double) worker.currentLag())
        .description(
            "Retained-but-unprocessed record count across the assigned partitions (S13-12); no labels")
        .register(meterRegistry);
  }

  /** The latest recorded lag (S13-12): the total across the assigned partitions, or 0. */
  private long currentLag() {
    return lastKnownLag.get();
  }

  /**
   * Re-read the lag from the live consumer after a poll pass. Never fails the worker: a broker
   * failure is logged and the gauge keeps the previous value (a scrape reports the last known lag
   * rather than a silent 0).
   */
  private void recordConsumerLag() {
    try {
      Set<TopicPartition> assignment = consumer.assignment();
      if (assignment == null || assignment.isEmpty()) {
        lastKnownLag.set(0L);
        return;
      }
      Map<TopicPartition, Long> endOffsets = consumer.endOffsets(assignment);
      long lag = 0L;
      for (TopicPartition partition : assignment) {
        long end = endOffsets.getOrDefault(partition, 0L);
        long position = consumer.position(partition);
        if (position < 0) {
          position = 0L;
        }
        lag += Math.max(0L, end - position);
      }
      lastKnownLag.set(lag);
    } catch (RuntimeException e) {
      log.debug("consumer lag metric failed: {}", e.getMessage());
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
    // Pull the trace/correlation context off the record before doing any work: an explicit
    // traceparent (the request that produced the event, S13-10) makes this span a child of that
    // request; absent/invalid headers honestly yield a root span. The correlation/causation ids
    // ride on the span as bounded attributes — never on metrics labels.
    String traceparent = headerOf(record.headers(), "traceparent");
    String correlationId = headerOf(record.headers(), "correlationId");
    String causationId = headerOf(record.headers(), "causationId");
    Span span =
        MessagingTracing.startSpan(
            tracer,
            "DocumentIndexingWorker#handle",
            MessagingTracing.parentContext(traceparent),
            correlationId,
            causationId);
    try {
      if (span == null) {
        return handleInSpan(record);
      }
      // Make the span current so the producer side of the indexing pass (the outbox enqueue for
      // the "indexed" event, etc.) threads the worker's span as its parent too.
      try (Scope scope = span.makeCurrent()) {
        return handleInSpan(record);
      }
    } finally {
      if (span != null) {
        span.end();
      }
    }
  }

  /** The indexing pass proper (unchanged), run inside the span. */
  private HandleOutcome handleInSpan(ConsumerRecord<String, String> record) {
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

  /** The last header value of the named record header, or {@code null} when absent. */
  private static String headerOf(Headers headers, String name) {
    var header = headers == null ? null : headers.lastHeader(name);
    if (header == null || header.value() == null) {
      return null;
    }
    String value = new String(header.value(), StandardCharsets.UTF_8);
    return value.isBlank() ? null : value;
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
