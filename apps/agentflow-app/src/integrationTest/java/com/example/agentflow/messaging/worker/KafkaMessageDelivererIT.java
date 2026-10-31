package com.example.agentflow.messaging.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.application.MessageDeliverer;
import com.example.agentflow.messaging.domain.OutboxEvent;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for {@link KafkaMessageDeliverer} (S06-09) against a real KRaft broker.
 *
 * <p>Runs on the same {@code apache/kafka} image as Compose on the fast, already-pulled tag {@code
 * apache/kafka:4.3.1}. It exercises the reliability contract the publisher relies on:
 *
 * <ul>
 *   <li>The envelope is forwarded with its topic, per-aggregate partition key, value and
 *       correlation headers intact &mdash; the transport adapter forwards the outbox row as-is.
 *   <li>A broker that is <b>unreachable</b> is classified {@link
 *       MessageDeliverer.DeliverResult#TRANSIENT_FAILURE} (retryable, bounded) &mdash; never
 *       delivered, never a hang.
 * </ul>
 *
 * <p>The producer is built through the package-private test constructor, pointing at the
 * Testcontainers broker, so the test does not depend on the Spring context or the {@code
 * agentflow.kafka.*} binding. The broker runs on KRaft (single combined broker+controller node).
 */
@Testcontainers
public class KafkaMessageDelivererIT {

  private static final DockerImageName KAFKA_IMAGE =
      DockerImageName.parse("apache/kafka:4.3.1").asCompatibleSubstituteFor("apache/kafka");

  @Container private final KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);

  /** A fresh PENDING envelope with a payload and two correlation headers. */
  private final OutboxEvent event =
      OutboxEvent.pending(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "workflow.run.requested.v1",
          "outbox-events.workflow.run.v1",
          "workflow_run",
          UUID.randomUUID(),
          UUID.randomUUID(),
          "{\"runId\":\"run-42\"}",
          "{\"correlationId\":\"abc-123\",\"traceparent\":\"00-0af7651916cd43ddb403ba\"}");

  /**
   * Deliver an envelope to the broker and consume it back: the record carries the right topic, the
   * per-aggregate key, the value and both correlation headers the producer parsed from the row.
   */
  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  public void delivers_envelope_with_key_topic_value_and_correlation_headers() throws Exception {
    try (KafkaMessageDeliverer deliverer =
        new KafkaMessageDeliverer(
            producerProps(kafka.getBootstrapServers(), 60_000L, null), 60_000L)) {
      assertThat(deliverer.deliver(event)).isEqualTo(MessageDeliverer.DeliverResult.DELIVERED);
    }

    try (KafkaConsumer<String, String> consumer = consumer(event.topic())) {
      consumer.subscribe(List.of(event.topic()));
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(15));
      assertThat(records.count()).isGreaterThan(0);
      ConsumerRecord<String, String> record = records.records(event.topic()).iterator().next();
      assertThat(record.topic()).isEqualTo(event.topic());
      assertThat(record.key()).isEqualTo(event.eventKey());
      assertThat(record.value()).isEqualTo(event.payloadJson());
      assertThat(headerValue(record, "correlationId")).isEqualTo("abc-123");
      assertThat(headerValue(record, "traceparent")).isEqualTo("00-0af7651916cd43ddb403ba");
    }
  }

  /**
   * A producer pointed at a broker that is not listening must classify the attempt as a retryable
   * transient failure, not delivered and not a timeout/ambiguous hang: the publisher then retries
   * at a bounded-backoff instant. A just-released ephemeral port is not listening, so the
   * connection-refused transport fault surfaces once the producer's metadata-establishment bound
   * ({@code max.block.ms}) fires; it is capped well below this method's {@code @Timeout(45s)} so
   * the fault fails fast (-> TRANSIENT_FAILURE) instead of the test timing out while the producer
   * waits on metadata for its default 60s.
   */
  @Test
  @Timeout(value = 45, unit = TimeUnit.SECONDS)
  public void classifies_unreachable_broker_as_transient_failure() throws Exception {
    try (KafkaMessageDeliverer deliverer =
        new KafkaMessageDeliverer(unreachableProps(freeClosedPort()), 60_000L)) {
      assertThat(deliverer.deliver(event))
          .isEqualTo(MessageDeliverer.DeliverResult.TRANSIENT_FAILURE);
    }
  }

  /**
   * @return a TCP port that is not accepting connections: an ephemeral socket is bound and closed
   *     so the port is highly unlikely to be listening, guaranteeing an immediate connection
   *     refusal
   */
  private static int freeClosedPort() {
    try (ServerSocket probe = new ServerSocket(0)) {
      probe.setReuseAddress(true);
      return probe.getLocalPort();
    } catch (IOException notClosed) {
      throw new IllegalStateException("could not find a closed port", notClosed);
    }
  }

  /**
   * Producer properties for the unreachable-broker test: {@code retries=0} so the client does not
   * resend, and a short {@code request.timeout.ms} so a request fault surfaces quickly. The
   * connection-refused transport fault is what {@code KafkaMessageDeliverer} classifies as {@link
   * MessageDeliverer.DeliverResult#TRANSIENT_FAILURE}.
   *
   * @param port a port with nothing listening
   * @return the producer client properties pointing at a dead broker
   */
  private static Map<String, Object> unreachableProps(int port) {
    // The failure must be observed as an OS-level connection refused (a transport fault ->
    // TRANSIENT_FAILURE), not as Kafka's connection timeout (which Kafka surfaces as a timeout ->
    // ambiguous). retries=0 so the client does not resend the request.
    Map<String, Object> props = producerProps("127.0.0.1:" + port, 8_000L, 0);
    props.put("connection.timeout.ms", 5000);
    // Kafka rejects a delivery timeout shorter than linger.ms + request.timeout.ms (request
    // timeout defaults to 30s). The short 8s delivery timeout the test needs is only valid with a
    // shorter request timeout, so cap it below 8s.
    props.put("request.timeout.ms", 5000);
    // A permanently unreachable broker makes the producer wait on metadata establishment up to
    // max.block.ms (default 60s) before the connection-refused transport fault surfaces. That bound
    // is kept well below the method's @Timeout(45s) so the fault fails fast (-> TRANSIENT_FAILURE)
    // instead of the test timing out on an otherwise fast transport failure.
    props.put("max.block.ms", 10_000);
    return props;
  }

  /**
   * The production configuration path: the Spring bean builds the producer from {@code agentflow.*}
   * via {@link KafkaMessageDeliverer#propsFrom}. Building a {@code KafkaProducer} validates every
   * value &mdash; Kafka's {@code ConfigDef} rejects an incompatible Java type with a {@code
   * ConfigException}, so a wrong binding would fail producer construction. No broker is needed:
   * construction validates but never connects.
   */
  @Test
  public void producer_config_validates_against_producer_client() {
    try (KafkaMessageDeliverer deliverer =
        new KafkaMessageDeliverer(
            KafkaMessageDeliverer.propsFrom(new AgentFlowProperties.Kafka.Producer()), 60_000L)) {
      // The producer validated its configuration on construction; a ConfigException would
      // propagate and fail the test.
      assertThat(deliverer).isNotNull();
    }
  }

  /**
   * Build a producer client-properties map. {@code retries} is {@code null} to leave the Kafka
   * client default (retry a request fault) and {@code 0} for the unreachable-broker test (fail
   * immediately).
   *
   * @param bootstrapServers the broker the producer connects to
   * @param deliveryTimeoutMs the send confirmation bound
   * @param retries the producer retry count, or {@code null} for the client default
   * @return the Kafka producer client properties
   */
  private static Map<String, Object> producerProps(
      String bootstrapServers, long deliveryTimeoutMs, Integer retries) {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) deliveryTimeoutMs);
    if (retries != null) {
      props.put(ProducerConfig.RETRIES_CONFIG, retries);
    }
    return props;
  }

  /**
   * Build a consumer that joins a unique group, starts at the earliest offset and never
   * auto-commits, so the poll returns every record on the topic exactly once.
   *
   * @param topic the topic to subscribe to
   * @return a configured, un-keyed consumer
   */
  private KafkaConsumer<String, String> consumer(String topic) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-deliverer-it-" + UUID.randomUUID());
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    return new KafkaConsumer<>(props);
  }

  /**
   * @return the header value decoded as UTF-8, or {@code null} when the header is absent
   */
  private static String headerValue(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    if (header == null) {
      return null;
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
