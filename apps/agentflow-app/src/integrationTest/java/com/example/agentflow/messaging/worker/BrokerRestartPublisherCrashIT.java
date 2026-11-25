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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
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
 * Broker-restart and publisher-crash acceptance tests (S14-05) on <b>real PostgreSQL + real
 * Kafka</b> (Testcontainers), driving the production outbox publisher end to end.
 *
 * <p>The guarantee under test is the Sprint 14 acceptance criterion — the event is <b>eventually
 * delivered</b> and the duplicate delivery is <b>handled idempotently</b> (at-least-once, ADR-003;
 * no double business effect). Two failure modes are exercised against a live broker:
 *
 * <ul>
 *   <li><b>The broker restarts at the moment of publication</b> — the publish attempt is held over
 *       the container stop, so the send provably happens while Kafka is being stopped; the row
 *       survives the restart as a durable {@code FAILED} (never lost, never falsely {@code SENT}),
 *       and after the restart the recovery poll drains it — delivered, inbox-deduped, exactly one
 *       business transition.
 *   <li><b>The publisher dies after the broker ack, before the database update</b> — the producer
 *       acknowledges the envelope, then the publisher process is simulated as crashed (the send is
 *       made, then the poll throws); the row is left {@code CLAIMED} and stale, the recovery pass
 *       re-claims and re-publishes it (a duplicate, never a loss), and the inbox dedups both copies
 *       to a single transition.
 * </ul>
 *
 * <p>Both tests model the failure at the persistence seam, not by hand-editing a row: the
 * publisher's own claim/publish/outcome path (S06-07 / S06-08) is what leaves the row in its
 * post-failure state, and the recovery pass is the production {@link
 * OutboxPublisherService#pollOnce()} on a fresh deliverer. A fresh tenant and topic per test keep
 * the two independent on the shared containers.
 */
@Testcontainers
class BrokerRestartPublisherCrashIT {

  private static final DockerImageName KAFKA_IMAGE =
      DockerImageName.parse("apache/kafka:4.3.1").asCompatibleSubstituteFor("apache/kafka");

  private static final String EVENT_TYPE = "workflow.run.requested.v1";
  private static final String AGGREGATE_TYPE = "workflow_run";
  private static final String CONSUMER = "workflowexecution:run-requested";
  private static final long DELIVERY_TIMEOUT_MS = 60_000L;
  private static final long FAST_FAILURE_TIMEOUT_MS = 8_000L;

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  @Container static final KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);

  private String topic;
  private JdbcTemplate jdbc;
  private OutboxEventRepository outbox;

  @BeforeEach
  void bootRealPostgresAndSchema() {
    // Flyway creates tenants / outbox_events / inbox_messages exactly as CI does. Idempotent on the
    // repeatable @BeforeEach run.
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
  }

  // --- broker restart at the moment of publication ------------------------------------------

  /**
   * S14-05 (broker restart): the broker is stopped while a publish attempt is in flight, the row
   * survives the restart as a durable {@code FAILED}, and after the restart the recovery poll
   * delivers it — the broker ends with one copy, the inbox dedups it and the business transition
   * runs exactly once.
   *
   * <p>The send is gated on a latch so it provably happens while the broker is down: the publisher
   * thread reaches the send (armed), the broker is stopped, then the send is released. The producer
   * is pointed at the (now dead) bootstrap servers, so the attempt fails fast (retries=0, short
   * timeouts) and is classified retryable — never a hang.
   */
  @Test
  @Timeout(value = 240, unit = TimeUnit.SECONDS)
  void broker_restart_at_the_moment_of_publication_delivers_the_event_after_recovery()
      throws Exception {
    UUID tenant = freshTenant();
    UUID runId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    seedPending(tenant, runId, eventId, topic);

    // The bootstrap servers the broker had before the restart: the publish attempt is held over
    // the stop, so the send is made against this (now dead) address.
    String oldBootstrap = kafka.getBootstrapServers();

    CountDownLatch armed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> pollError = new AtomicReference<>();

    try (KafkaMessageDeliverer down = deliveringTo(oldBootstrap, true)) {
      MessageDeliverer gated =
          event -> {
            armed.countDown();
            awaitQuietly(release, 30, TimeUnit.SECONDS);
            // The send is made while the broker is down — the publish attempt happens at the
            // moment of the restart.
            return down.deliver(event);
          };
      OutboxPublisherService publisher = publisherAt(gated);
      Thread publisherThread =
          new Thread(
              () -> {
                try {
                  publisher.pollOnce();
                } catch (Throwable t) {
                  pollError.set(t);
                }
              });
      publisherThread.start();

      assertThat(armed.await(30, TimeUnit.SECONDS))
          .as("the publisher must reach the send before the broker is stopped")
          .isTrue();

      kafka.stop(); // broker goes down mid-publication
      release.countDown(); // release the send into the down window
      publisherThread.join(90_000);
      assertThat(publisherThread.isAlive())
          .as("the publisher thread must finish after the refused send")
          .isFalse();
    }

    assertThat(pollError.get()).as("the poll must not throw when the broker is refused").isNull();

    // The durable row survived the restart: attempted once, failed transiently, never SENT, never
    // lost — it stays in PostgreSQL as the outbox.
    assertThat(selectStatus(eventId)).isEqualTo("FAILED");
    assertThat(selectAttempts(eventId)).isEqualTo(1);
    assertThat(countProcessed(eventId)).isZero();

    // The broker comes back: the stale FAILED row (its next-attempt instant is aged into the past)
    // is re-selected, claimed and delivered to the fresh broker.
    kafka.start();
    String newBootstrap = kafka.getBootstrapServers();
    jdbc.update(
        "UPDATE outbox_events SET next_attempt_at = now() - INTERVAL '1 second' WHERE event_id = ?",
        eventId);
    try (KafkaMessageDeliverer up = deliveringTo(newBootstrap, false)) {
      assertThat(publisherAt(up).pollOnce()).isEqualTo(1);
    }
    assertThat(selectStatus(eventId)).isEqualTo("SENT");

    // The broker holds exactly one copy; the consumer inbox-dedups it and the business transition
    // runs exactly once.
    assertThat(consumeAll(topic)).hasSize(1);
    AtomicInteger transitions = new AtomicInteger();
    assertThat(consume(eventId, transitions)).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(transitions.get()).isEqualTo(1);
    assertThat(countProcessed(eventId)).isEqualTo(1);
    // The outbox fully drained.
    assertThat(outbox.selectForClaim(1000)).isEmpty();
  }

  // --- publisher crash after broker ack, before the database update ----------------------------

  /**
   * S14-05 (crash window): the publisher delivers to the broker and is killed before recording the
   * outcome. The row stays {@code CLAIMED} while the broker holds the envelope; the recovery pass
   * re-claims it (stale claim) and re-publishes it — a duplicate, never a loss — and the inbox
   * dedups both copies to a single business transition.
   */
  @Test
  @Timeout(value = 240, unit = TimeUnit.SECONDS)
  void publisher_crash_after_broker_ack_before_db_update_republishes_as_duplicate()
      throws Exception {
    UUID tenant = freshTenant();
    UUID runId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID rowId = seedPending(tenant, runId, eventId, topic);

    // Pass 1 — the publisher delivers to the broker (broker ack) and is then simulated as crashed:
    // the send happens, the poll throws, the row is left CLAIMED (exactly the S06-08 crash window,
    // crashing the poll in-process — the durable row is left by the production claim path, not by a
    // hand-edit).
    try (KafkaMessageDeliverer real = deliveringTo(kafka.getBootstrapServers(), false)) {
      AtomicBoolean crashed = new AtomicBoolean(false);
      MessageDeliverer crashing =
          event -> {
            MessageDeliverer.DeliverResult result = real.deliver(event);
            if (result == MessageDeliverer.DeliverResult.DELIVERED
                && crashed.compareAndSet(false, true)) {
              throw new IllegalStateException(
                  "publisher crashed after broker ack, before the database update");
            }
            return result;
          };
      OutboxPublisherService crashingPublisher = publisherAt(crashing);
      try {
        crashingPublisher.pollOnce();
      } catch (IllegalStateException expected) {
        // The crash is the simulated process death; the durable row is left CLAIMED.
      }
      assertThat(crashed.get()).as("the publisher must have crashed after the broker ack").isTrue();
    }

    assertThat(selectStatus(eventId)).isEqualTo("CLAIMED");
    assertThat(selectAttempts(eventId)).isEqualTo(1);
    // The broker holds exactly one copy — the envelope the crashed publisher did deliver.
    assertThat(consumeAll(topic)).hasSize(1);

    // Age the claim so the stale-claim bound is reached (the crashed publisher may have died long
    // before this recovery pass).
    jdbc.update(
        "UPDATE outbox_events SET claimed_at = now() - INTERVAL '1 day' WHERE id = ?", rowId);

    // Pass 2 — recovery: the stale claim is re-claimed, re-published (a duplicate) and recorded
    // SENT.
    try (KafkaMessageDeliverer recover = deliveringTo(kafka.getBootstrapServers(), false)) {
      assertThat(publisherAt(recover).pollOnce()).isEqualTo(1);
    }
    assertThat(selectStatus(eventId)).isEqualTo("SENT");

    // The broker now holds two copies of the same envelope.
    assertThat(consumeAll(topic)).hasSize(2);

    // The inbox dedups both: the business transition runs exactly once.
    AtomicInteger transitions = new AtomicInteger();
    assertThat(consume(eventId, transitions)).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(consume(eventId, transitions)).isEqualTo(InboxConsumeResult.OTHER_CLAIMED);
    assertThat(transitions.get()).isEqualTo(1);
    assertThat(countProcessed(eventId)).isEqualTo(1);
    assertThat(outbox.selectForClaim(1000)).isEmpty();
  }

  // --- wiring and fixtures ----------------------------------------------------------------------

  /** Insert a fresh tenant (never reused — the two tests are isolated). */
  private UUID freshTenant() {
    UUID tenant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenants (id, slug) VALUES (?, ?)",
        ps -> {
          ps.setObject(1, tenant);
          ps.setString(2, "tenant-" + tenant);
        });
    return tenant;
  }

  /** Insert one {@code PENDING} outbox row for a fresh run and return its row id. */
  private UUID seedPending(UUID tenant, UUID runId, UUID eventId, String topic) {
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
   *     event_id}
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
   * @return a deliverer that publishes to the given broker through the production adapter. {@code
   *     fastFailure} makes the producer fail fast (retries=0, short timeouts) for a dead port — the
   *     S06-14 outage posture.
   */
  private KafkaMessageDeliverer deliveringTo(String bootstrapServers, boolean fastFailure) {
    if (fastFailure) {
      return new KafkaMessageDeliverer(deadPortProps(bootstrapServers), FAST_FAILURE_TIMEOUT_MS);
    }
    return new KafkaMessageDeliverer(producerProps(bootstrapServers), DELIVERY_TIMEOUT_MS);
  }

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
   * Producer properties for the dead-broker half of the outage posture: {@code retries=0} so the
   * client does not resend, and a short {@code request.timeout.ms} so a request fault surfaces
   * quickly (an 8s delivery bound is only valid with a request bound below it). {@code
   * connection.timeout.ms} / {@code max.block.ms} keep a connection-refused transport fault fast —
   * the same posture as {@code KafkaMessageDelivererIT}'s dead-port test.
   */
  private static Map<String, Object> deadPortProps(String bootstrapServers) {
    Map<String, Object> props = producerProps(bootstrapServers);
    props.put(ProducerConfig.RETRIES_CONFIG, 0);
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
    props.put("connection.timeout.ms", 5_000);
    props.put("max.block.ms", 10_000);
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) FAST_FAILURE_TIMEOUT_MS);
    return props;
  }

  /**
   * @return a fresh, unkeyed consumer that joins a unique group, starts at the earliest offset and
   *     never auto-commits, so the poll returns every record on the topic exactly once
   */
  private KafkaConsumer<String, String> consumer(String topic) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "broker-restart-it-" + UUID.randomUUID());
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

  private static void awaitQuietly(CountDownLatch latch, long timeout, TimeUnit unit) {
    try {
      latch.await(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
