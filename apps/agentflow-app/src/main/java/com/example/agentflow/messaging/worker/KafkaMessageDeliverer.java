package com.example.agentflow.messaging.worker;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.application.MessageDeliverer;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Kafka-backed {@link MessageDeliverer} (S06-09): the transport adapter that forwards a claimed
 * outbox envelope to the broker with <b>reliable</b> send semantics.
 *
 * <p>The publisher (S06-07 / S06-08) never holds a database transaction across a network call, so
 * {@link #deliver} performs a single blocking send outside any transaction and classifies the
 * broker outcome so the publisher can apply its own bounded retry. It wires the standard Kafka
 * producer client settings from {@link AgentFlowProperties.Kafka.Producer} (documented by Spring
 * Kafka), tuned for reliability:
 *
 * <ul>
 *   <li><b>acks=all</b> &mdash; the broker confirms only once every in-sync replica holds the
 *       record, so a delivered envelope is durable before the publisher marks it {@code SENT}.
 *   <li><b>delivery-timeout</b> &mdash; the total time a send waits for confirmation; on timeout
 *       the outcome is <b>ambiguous</b> (the broker may or may not hold the envelope), so the row
 *       is re-published at a bounded-backoff instant &mdash; a duplicate, never a loss
 *       (at-least-once, ADR-003).
 *   <li><b>retries + retry-backoff</b> &mdash; the producer retries a request-failed fault
 *       internally; the publisher's own bounded retry (S06-07) sits on top.
 *   <li><b>batching</b> ({@code batch-size}, {@code linger-ms}, {@code buffer-memory}) &mdash;
 *       records are grouped for efficiency, bounded so a poll never buffers unbounded work.
 * </ul>
 *
 * <p>The envelope is sent to {@code outbox_events.topic} with {@code outbox_events.event_key} as
 * the partition key (so all records of one aggregate hit one partition &mdash; per-aggregate
 * ordering, docs/architecture/kafka-topics.md) and {@code outbox_events.headers} as correlation
 * headers, the payload as the value.
 *
 * <p><b>Not a substitute for the overall PostgreSQL/Kafka transaction (invariant 3).</b> Kafka
 * transactions or {@code enable.idempotence} are deliberately <i>not</i> used as the end-to-end
 * safety net: delivery is at-least-once, and correctness comes from the transactional outbox
 * (ADR-004) plus the consumer-side inbox dedup (S06-10), never from exactly-once Kafka processing.
 *
 * <p>The producer is created eagerly (its sender thread starts on construction) but a broker that
 * is unreachable does <i>not</i> fail construction &mdash; the first {@link #deliver} classifies it
 * as a transient/ambiguous fault, exactly as the publisher expects.
 */
@Service
@ConditionalOnProperty(prefix = "agentflow.kafka.producer", name = "enabled", havingValue = "true")
public class KafkaMessageDeliverer implements MessageDeliverer, Closeable {

  private static final Logger log = LoggerFactory.getLogger(KafkaMessageDeliverer.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final KafkaProducer<String, String> producer;
  private final long deliveryTimeoutMs;

  /**
   * Spring-managed constructor. Binds the reliability settings from {@code agentflow.kafka.*} and
   * builds the producer from them.
   *
   * @param properties the validated typed configuration, bound from {@code agentflow.*}
   */
  @Autowired
  public KafkaMessageDeliverer(AgentFlowProperties properties) {
    this(
        propsFrom(properties.getKafka().getProducer()),
        properties.getKafka().getProducer().getDeliveryTimeoutSeconds() * 1000L);
  }

  /**
   * Test constructor: build the producer from a raw client-properties map (e.g. pointing at a
   * Testcontainers broker).
   *
   * @param producerProps the Kafka producer client properties
   * @param deliveryTimeoutMs the send confirmation bound in milliseconds
   */
  KafkaMessageDeliverer(Map<String, Object> producerProps, long deliveryTimeoutMs) {
    this.producer = new KafkaProducer<>(new HashMap<>(producerProps));
    this.deliveryTimeoutMs = deliveryTimeoutMs;
  }

  @Override
  public DeliverResult deliver(OutboxEvent event) {
    ProducerRecord<String, String> record = toRecord(event);
    log.debug(
        "outbox event {}: forwarding {} to topic {} (key {})",
        event.eventId(),
        event.eventId(),
        record.topic(),
        record.key());

    Future<RecordMetadata> future;
    try {
      future =
          producer.send(
              record,
              (metadata, exception) -> {
                if (exception != null) {
                  log.warn(
                      "outbox event {}: broker rejected the send: {}",
                      event.eventId(),
                      exception.getMessage());
                }
              });
    } catch (RuntimeException synchronous) {
      // A malformed envelope can fail synchronously before the record is queued.
      return classifySynchronous(synchronous);
    }

    try {
      future.get(deliveryTimeoutMs, TimeUnit.MILLISECONDS);
      return DeliverResult.DELIVERED;
    } catch (TimeoutException timeout) {
      // Either our bound or the producer's own delivery.timeout.ms fired: the broker may or may
      // not hold the envelope. Never mark delivered &mdash; re-publish at a bounded-backoff
      // instant.
      log.warn(
          "outbox event {}: broker confirmation timed out after {} ms; outcome ambiguous, re-publish (at-least-once, S06-08)",
          event.eventId(),
          deliveryTimeoutMs);
      return DeliverResult.AMBIGUOUS;
    } catch (ExecutionException execution) {
      return classifyFailure(event.eventId(), execution.getCause());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      // Interrupted while waiting: the send outcome is undetermined.
      log.warn("outbox event {}: delivery interrupted; outcome ambiguous", event.eventId());
      return DeliverResult.AMBIGUOUS;
    }
  }

  /**
   * Classify a failure surfaced when the producer's future completed exceptionally.
   *
   * <ul>
   *   <li>Kafka's own delivery timeout (or our bound) &mdash; {@link DeliverResult#AMBIGUOUS}.
   *   <li>A clearly malformed record (null/invalid key, payload, or serializer argument) &mdash;
   *       {@link DeliverResult#PERMANENT_FAILURE} &mdash; retrying will never succeed.
   *   <li>A broker-side / network fault (unreachable broker, <i>not enough in-sync replicas</i>,
   *       throttling, connection reset) &mdash; {@link DeliverResult#TRANSIENT_FAILURE} &mdash;
   *       retryable, bounded by the publisher (S06-07 / S06-11).
   * </ul>
   *
   * @param eventId the event id (for logging)
   * @param cause the underlying fault, or {@code null} when undetermined
   */
  private DeliverResult classifyFailure(Object eventId, Throwable cause) {
    if (cause == null) {
      // An execution error with no cause is undetermined.
      log.warn(
          "outbox event {}: delivery failed with an undetermined outcome; re-publish", eventId);
      return DeliverResult.AMBIGUOUS;
    }
    if (cause instanceof TimeoutException) {
      return DeliverResult.AMBIGUOUS;
    }
    if (cause instanceof IllegalArgumentException
        || cause instanceof NullPointerException
        || cause instanceof IllegalStateException) {
      return DeliverResult.PERMANENT_FAILURE;
    }
    if (cause instanceof NotEnoughReplicasException) {
      // The broker could not accept the write right now (e.g. acks=all with fewer in-sync
      // replicas than required); retryable because a replica may return.
      return DeliverResult.TRANSIENT_FAILURE;
    }
    // Any other Kafka / transport fault is treated as retryable.
    return DeliverResult.TRANSIENT_FAILURE;
  }

  /** Classify a fault thrown by {@code send} itself, before the record is handed to the broker. */
  private DeliverResult classifySynchronous(RuntimeException synchronous) {
    if (synchronous instanceof IllegalArgumentException
        || synchronous instanceof NullPointerException
        || synchronous instanceof IllegalStateException) {
      log.warn(
          "outbox event {}: malformed envelope rejected synchronously: {}",
          /* eventId */ null,
          synchronous.getMessage());
      return DeliverResult.PERMANENT_FAILURE;
    }
    return DeliverResult.TRANSIENT_FAILURE;
  }

  /**
   * @return the Kafka producer client properties mapped from the typed configuration (the same keys
   *     Spring Kafka documents).
   */
  static Map<String, Object> propsFrom(AgentFlowProperties.Kafka.Producer config) {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
    props.put(ProducerConfig.ACKS_CONFIG, config.getAcks());
    // Kafka's producer config is strict about value types: the *_ms, *_retries and *_size settings
    // are 32-bit ints (a Long is rejected with a ConfigException), and buffer.memory is a 64-bit
    // long (an Integer is rejected). The typed configuration is adapted here so the producer
    // validates on construction (S06-09).
    props.put(ProducerConfig.RETRIES_CONFIG, config.getRetries());
    props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, (int) config.getRetryBackoffMs());
    props.put(
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
        (int) (config.getDeliveryTimeoutSeconds() * 1000L));
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, config.getBatchSize());
    props.put(ProducerConfig.LINGER_MS_CONFIG, (int) config.getLingerMs());
    props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, (long) config.getBufferMemory());
    props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, config.getCompressionType());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    return props;
  }

  /**
   * Build the producer record from the outbox row: {@code topic}, {@code event_key} as the key (so
   * the default partitioner routes by aggregate), {@code payload} as the value, and the parsed
   * {@code headers} as correlation headers.
   */
  private static ProducerRecord<String, String> toRecord(OutboxEvent event) {
    RecordHeaders headers = parseHeaders(event.headersJson());
    // partition null &mdash; the key-based default partitioner assigns the partition, giving
    // per-aggregate ordering (docs/architecture/kafka-topics.md).
    return new ProducerRecord<>(
        event.topic(), null, event.eventKey(), event.payloadJson(), headers);
  }

  /**
   * Parse the stored {@code headers} JSON object (correlation metadata: correlation/causation/
   * traceparent) into Kafka record headers. A null/blank/unparseable body yields no headers (never
   * a failure of the send) &mdash; correlation is correlation, not delivery.
   */
  private static RecordHeaders parseHeaders(String headersJson) {
    RecordHeaders headers = new RecordHeaders();
    if (headersJson == null || headersJson.isBlank()) {
      return headers;
    }
    try {
      Map<String, String> map =
          MAPPER.readValue(headersJson, new TypeReference<Map<String, String>>() {});
      for (Map.Entry<String, String> entry : map.entrySet()) {
        headers.add(
            new RecordHeader(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8)));
      }
    } catch (RuntimeException | JsonProcessingException notParsed) {
      log.warn(
          "outbox event {}: could not parse headers JSON ({}); sending without correlation headers",
          headersJson,
          notParsed.getMessage());
    }
    return headers;
  }

  /**
   * Close the underlying producer so in-flight records are flushed and the sender thread stops on
   * shutdown.
   */
  @PreDestroy
  @Override
  public void close() {
    producer.close();
  }
}
