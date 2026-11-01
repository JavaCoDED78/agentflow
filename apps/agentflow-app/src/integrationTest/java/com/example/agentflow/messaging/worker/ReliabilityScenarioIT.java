package com.example.agentflow.messaging.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.application.InboxConsumeResult;
import com.example.agentflow.messaging.application.InboxConsumerAction;
import com.example.agentflow.messaging.application.InboxDedupService;
import com.example.agentflow.messaging.application.MessageDeliverer;
import com.example.agentflow.messaging.application.OutboxPublisherService;
import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end reliability acceptance tests for Sprint 6 messaging (S06-14) on <b>real PostgreSQL +
 * real Kafka</b> (Testcontainers). Each test exercises the full at-least-once pipeline the platform
 * relies on &mdash; transactional outbox (ADR-004) &rarr; polling publisher (S06-07 / S06-08)
 * &rarr; broker &rarr; inbox dedup (S06-10, ADR-003) &mdash; under the three failure modes the plan
 * models:
 *
 * <ul>
 *   <li><b>Kafka outage after createRun, then recovery</b> &mdash; the durable outbox row survives
 *       a broker that is unreachable (classified a retryable transient fault, S06-09 / S06-07),
 *       stays in the outbox, and is delivered and processed once Kafka is back. A run is never lost
 *       because Kafka was down when it was created (PostgreSQL is the source of truth, invariant
 *       2).
 *   <li><b>A duplicate envelope</b> &mdash; the same {@code event_id} delivered to the broker twice
 *       (a re-publish of an ambiguous / outage-affected send, S06-08) is consumed and inbox-deduped
 *       so it drives <b>exactly one</b> business transition &mdash; never a repeat (S06-10,
 *       ADR-003).
 *   <li><b>A crash between the broker acknowledgement and the outbox DB update</b> &mdash; the row
 *       is left {@code CLAIMED} and stale after the broker received it; the recovery re-publishes
 *       it (a duplicate, never a loss) and the inbox dedups it to one transition.
 * </ul>
 *
 * <p>Proven end-to-end, not by mock:
 *
 * <ul>
 *   <li>a real Flyway-migrated schema ({@code tenants}, {@code outbox_events}, {@code
 *       inbox_messages});
 *   <li>a real {@link JdbcTemplate} against the container;
 *   <li>a real {@link KafkaMessageDeliverer} (the production transport adapter, through its
 *       package-private test constructor) against a real {@link KafkaContainer};
 *   <li>a real {@link InboxDedupService} applying the business transition, fenced by the composite
 *       {@code inbox_messages} primary key.
 * </ul>
 *
 * <p>A fresh, per-method {@code topic} keeps the three tests independent (Kafka auto-creates the
 * topic and a group with {@code earliest} would otherwise read every earlier method's records).
 */
@Testcontainers
public class ReliabilityScenarioIT {

  private static final DockerImageName KAFKA_IMAGE =
      DockerImageName.parse("apache/kafka:4.3.1").asCompatibleSubstituteFor("apache/kafka");

  private static final String EVENT_TYPE = "workflow.run.requested.v1";
  private static final String AGGREGATE_TYPE = "workflow_run";
  private static final String CONSUMER = "workflowexecution:run-requested";
  private static final long DELIVERY_TIMEOUT_MS = 60_000L;

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  @Container static final KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);

  private String topic;
  private JdbcTemplate jdbc;
  private OutboxEventRepository outbox;

  @BeforeEach
  void bootRealPostgresAndSchema() {
    // Flyway creates tenants / outbox_events / inbox_messages exactly as CI does (S06-03 / S06-04 /
    // S06-08). Idempotent on the repeatable @BeforeEach run.
    org.flywaydb.core.Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:migrations")
        .load()
        .migrate();

    topic = "agentflow.workflow.run-requested.v1-" + UUID.randomUUID();
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(ds);
    outbox = new OutboxEventRepository(jdbc);

    // A tenant row so outbox_events.tenant_id (FK tenants.id) is satisfiable. ON CONFLICT keeps the
    // @BeforeEach fixture re-runnable across tests in the JVM.
    jdbc.update(
        "INSERT INTO tenants (id, slug) VALUES (?, ?) ON CONFLICT (slug) DO NOTHING",
        UUID.randomUUID(),
        "tenant-it");
  }

  /**
   * S06-14 acceptance (Kafka outage): the run is durable in PostgreSQL when it is created, the
   * outbox event survives the broker outage as a retryable row, and once Kafka is back the event is
   * delivered, consumed and drives its run transition <b>exactly once</b> &mdash; nothing is lost
   * and no business transition repeats.
   */
  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void run_survives_a_kafka_outage_and_is_processed_after_recovery() throws Exception {
    UUID runId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    seedPending(runId, eventId);

    // Kafka is DOWN: a publisher pointed at a refused port classifies the attempt as a retryable
    // transient fault (S06-09) and the durable outbox row is retained (CLAIMED -> FAILED), not
    // delivered and not lost.
    int deadPort = freeClosedPort();
    try (KafkaMessageDeliverer down = failingToDeadPort(deadPort)) {
      // The row is attempted (published++ on every claimed row) but nothing reaches the broker.
      assertThat(publisherAt(down).pollOnce()).isPositive();
    }
    assertThat(selectStatus(eventId)).isEqualTo("FAILED");
    assertThat(selectAttempts(eventId)).isEqualTo(1);
    assertThat(countProcessed(eventId)).isZero();

    // The bounded-retry instant has arrived: age next_attempt_at into the past so the recovery poll
    // re-selects the due FAILED row (naturally true after the configured base delay of 1s).
    jdbc.update(
        "UPDATE outbox_events SET next_attempt_at = now() - INTERVAL '1 second' WHERE event_id = ?",
        eventId);

    // Kafka is UP again: the retryable row is re-selected, claimed and delivered to the live
    // broker.
    AtomicInteger transitions = new AtomicInteger();
    try (KafkaMessageDeliverer up = deliveringTo(kafka.getBootstrapServers())) {
      assertThat(publisherAt(up).pollOnce()).isEqualTo(1);
    }
    assertThat(selectStatus(eventId)).isEqualTo("SENT");

    // The consumer receives the single envelope and applies the run transition exactly once.
    assertThat(consumeAll(topic)).hasSize(1);
    assertThat(consume(eventId, transitions)).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(transitions.get()).isEqualTo(1);
    assertThat(countProcessed(eventId)).isEqualTo(1);
    // The outbox fully drained and the business transition committed once.
    assertThat(outbox.selectForClaim(1000)).isEmpty();
  }

  /**
   * S06-14 acceptance (duplicate): the same {@code event_id} delivered to the broker twice is
   * consumed and inbox-deduped so it drives <b>exactly one</b> business transition &mdash; the
   * second delivery is {@link InboxConsumeResult#OTHER_CLAIMED} and re-runs nothing (S06-10,
   * ADR-003).
   */
  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void a_duplicate_envelope_is_consumed_only_once() throws Exception {
    UUID runId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    // At-least-once: the SAME envelope (same event_id, same per-aggregate key) is published twice
    // to
    // the broker &mdash; what a re-publish of an ambiguous / outage-affected send produces
    // (S06-08).
    try (KafkaProducer<String, String> producer = newProducer()) {
      ProducerRecord<String, String> first =
          new ProducerRecord<>(topic, "tenant:" + runId, "{\"runId\":\"" + runId + "\"}");
      ProducerRecord<String, String> second =
          new ProducerRecord<>(topic, "tenant:" + runId, "{\"runId\":\"" + runId + "\"}");
      producer.send(first).get();
      producer.send(second).get();
    }

    // Both copies are on the broker; each is fed to the inbox dedup for the same (consumer, event).
    assertThat(consumeAll(topic)).hasSize(2);
    AtomicInteger transitions = new AtomicInteger();
    InboxConsumeResult first = consume(eventId, transitions);
    InboxConsumeResult second = consume(eventId, transitions);

    assertThat(first).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(second).isEqualTo(InboxConsumeResult.OTHER_CLAIMED);
    assertThat(transitions.get()).isEqualTo(1);
    // One inbox row, committed once: no duplicate transition, no duplicate row.
    assertThat(countProcessed(eventId)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_messages WHERE event_id = ?", Integer.class, eventId))
        .isEqualTo(1);
  }

  /**
   * S06-14 acceptance (crash window): a publisher that crashed between the broker acknowledgement
   * and the outbox DB update (S06-08) leaves the row {@code CLAIMED} and stale while the broker
   * already holds the envelope. The recovery re-claims and re-publishes it (a duplicate, never a
   * loss), so the broker ends with two copies that the inbox dedups to a single transition &mdash;
   * the run is processed once, with no loss and no repeat.
   */
  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void a_crash_between_broker_ack_and_db_update_republishes_without_loss_or_repeat()
      throws Exception {
    UUID runId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID rowId = seedPending(runId, eventId);

    // Pass 1 — the publisher delivers to the broker (broker ack) and records SENT. The broker now
    // holds envelope #1.
    try (KafkaMessageDeliverer first = deliveringTo(kafka.getBootstrapServers())) {
      assertThat(publisherAt(first).pollOnce()).isEqualTo(1);
    }
    assertThat(selectStatus(eventId)).isEqualTo("SENT");

    // Simulate the crash before the outcome update: the row reverts to a stale {@code CLAIMED}
    // (broker has it, the outbox did not record SENT); the recovery pass re-claims it and
    // re-publishes
    // it. Aging claimed_at models the elapsed time before the stale-claim bound is reached.
    jdbc.update(
        "UPDATE outbox_events SET status = 'CLAIMED', published_at = NULL, "
            + "claimed_at = now() - INTERVAL '1 day' WHERE id = ?",
        rowId);

    // Pass 2 — recovery re-publishes (duplicate, at-least-once) and records SENT. The broker now
    // holds two copies of the same event.
    try (KafkaMessageDeliverer recover = deliveringTo(kafka.getBootstrapServers())) {
      assertThat(publisherAt(recover).pollOnce()).isEqualTo(1);
    }
    assertThat(selectStatus(eventId)).isEqualTo("SENT");

    // Two copies on the broker -> the inbox dedups them to a single business transition.
    assertThat(consumeAll(topic)).hasSize(2);
    AtomicInteger transitions = new AtomicInteger();
    assertThat(consume(eventId, transitions)).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(consume(eventId, transitions)).isEqualTo(InboxConsumeResult.OTHER_CLAIMED);
    assertThat(transitions.get()).isEqualTo(1);
    assertThat(countProcessed(eventId)).isEqualTo(1);
    assertThat(outbox.selectForClaim(1000)).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Fixtures and helpers
  // ---------------------------------------------------------------------------

  /** Insert one {@code PENDING} outbox row for a fresh run and return its row id. */
  private UUID seedPending(UUID runId, UUID eventId) {
    UUID tenant = jdbc.queryForObject("SELECT id FROM tenants", UUID.class);
    return outbox.insert(
        OutboxEvent.pending(
            UUID.randomUUID(),
            eventId,
            EVENT_TYPE,
            topic,
            AGGREGATE_TYPE,
            runId,
            tenant,
            "{\"runId\":\"" + runId + "\"}",
            null));
  }

  /**
   * @return the outbox {@code status} of the event, or null when the row is absent.
   */
  private String selectStatus(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);
  }

  /**
   * @return the {@code publish_attempts} of the event's outbox row.
   */
  private int selectAttempts(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT publish_attempts FROM outbox_events WHERE event_id = ?", Integer.class, eventId);
  }

  /**
   * @return the number of the event's inbox rows the consumer has processed, scoped to this {@code
   *     event_id} (the shared container accumulates inbox rows across tests in the class, so a
   *     consumer-only count would be polluted by other tests' events)
   */
  private int countProcessed(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM inbox_messages WHERE consumer_name = ? AND event_id = ? "
            + "AND status = 'PROCESSED'",
        Integer.class,
        CONSUMER,
        eventId);
  }

  /**
   * Run the business transition for a claimed event through the real inbox dedup helper: only the
   * first claim runs the action, so a duplicate delivery returns {@link
   * InboxConsumeResult#OTHER_CLAIMED}.
   *
   * @param eventId the envelope id (the inbox dedup anchor)
   * @param transitions the counter incremented by the (at most once) business transition
   * @return the {@link InboxConsumeResult} the consumer would acknowledge / redeliver / dead-letter
   */
  private InboxConsumeResult consume(UUID eventId, AtomicInteger transitions) {
    return newInboxDedup()
        .consume(
            CONSUMER,
            InboxMessage.claiming(CONSUMER, eventId),
            new InboxConsumerAction() {
              @Override
              public String execute() {
                transitions.incrementAndGet();
                return "prior=QUEUED new=RUNNING";
              }
            });
  }

  /**
   * Build an {@link InboxDedupService} over a fresh connection to the same PostgreSQL container.
   */
  private InboxDedupService newInboxDedup() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    return new InboxDedupService(
        new InboxMessageRepository(new JdbcTemplate(ds)),
        new TransactionTemplate(new DataSourceTransactionManager(ds)),
        InboxDedupService.DEFAULT_MAX_ATTEMPTS);
  }

  /** Build an outbox publisher that drains {@code outbox_events} through the given deliverer. */
  private OutboxPublisherService publisherAt(MessageDeliverer deliverer) {
    return new OutboxPublisherService(outbox, deliverer, null, new AgentFlowProperties());
  }

  /**
   * @return a deliverer that publishes to the live broker through the production adapter.
   */
  private KafkaMessageDeliverer deliveringTo(String bootstrapServers) {
    return new KafkaMessageDeliverer(producerProps(bootstrapServers), DELIVERY_TIMEOUT_MS);
  }

  /**
   * @return a deliverer pointed at a dead port: the producer's connection-refused transport fault
   *     is classified {@link MessageDeliverer#TRANSIENT_FAILURE} (S06-09), modelling a broker
   *     outage
   */
  private KafkaMessageDeliverer failingToDeadPort(int port) {
    return new KafkaMessageDeliverer(deadPortProps(port), DELIVERY_TIMEOUT_MS);
  }

  /**
   * @return a fresh, unkeyed consumer that joins a unique group, starts at the earliest offset and
   *     never auto-commits, so the poll returns every record on the topic exactly once
   */
  private KafkaConsumer<String, String> consumer(String topic) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "reliability-it-" + UUID.randomUUID());
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    return new KafkaConsumer<>(props);
  }

  /**
   * @return every record on the topic, polling until the broker is idle (capped at 100)
   */
  private List<ConsumerRecord<String, String>> consumeAll(String topic) {
    List<ConsumerRecord<String, String>> records = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = consumer(topic)) {
      consumer.subscribe(List.of(topic));
      while (records.size() < 100) {
        ConsumerRecords<String, String> batch = consumer.poll(Duration.ofSeconds(5));
        if (batch.isEmpty()) {
          break;
        }
        for (ConsumerRecord<String, String> record : batch.records(topic)) {
          records.add(record);
        }
      }
    }
    return records;
  }

  /**
   * @return producer client properties for the live broker (acks=all, real delivery bound).
   */
  private static Map<String, Object> producerProps(String bootstrapServers) {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) DELIVERY_TIMEOUT_MS);
    return props;
  }

  /**
   * @return producer client properties for a dead port: {@code retries=0} and short timeouts so the
   *     connection-refused transport fault surfaces quickly as {@code TRANSIENT_FAILURE} (as in
   *     {@link KafkaMessageDelivererIT}), never a hang or a timeout
   */
  private static Map<String, Object> deadPortProps(int port) {
    Map<String, Object> props = producerProps("127.0.0.1:" + port);
    props.put(ProducerConfig.RETRIES_CONFIG, 0);
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
    props.put("connection.timeout.ms", 5_000);
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
    return props;
  }

  /**
   * @return a Kafka producer against the live broker with string key/value serializers.
   */
  private KafkaProducer<String, String> newProducer() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    return new KafkaProducer<>(props);
  }

  /**
   * @return a TCP port with nothing listening: an ephemeral socket bound and closed so the port is
   *     highly unlikely to be accepting, guaranteeing an immediate connection refusal
   */
  private static int freeClosedPort() {
    try (ServerSocket probe = new ServerSocket(0)) {
      probe.setReuseAddress(true);
      return probe.getLocalPort();
    } catch (java.io.IOException notClosed) {
      throw new IllegalStateException("could not find a closed port", notClosed);
    }
  }
}
