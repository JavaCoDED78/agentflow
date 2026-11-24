package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import com.example.agentflow.messaging.persistence.OutboxPendingSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for the S13-12 messaging meters on {@link OutboxPublisherService}: the bounded
 * publish-attempt counter, the retry counter, and the two gauges that read the repository at scrape
 * time. The {@link OutboxEventRepository} is a mock (the messaging suite's DB-free convention); the
 * registry is a real {@link SimpleMeterRegistry} so the meters are asserted on by name and tag.
 *
 * <p>The meter assertions prove the instrumented path <em>emits</em> the right counters on the
 * right outcome; the real-database due-set semantics are proven by the integration suite.
 */
class OutboxPublisherMetricsTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID RUN = UUID.randomUUID();
  private static final OffsetDateTime NOW = OffsetDateTime.now();
  private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), java.time.ZoneOffset.UTC);

  @Test
  void poll_records_the_publish_attempt_counter_tagged_with_the_outcome() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    UUID id = UUID.randomUUID();
    OutboxEvent event = claimed(id, 1);
    when(repo.selectForClaim(anyInt())).thenReturn(List.of(event));
    when(repo.claim(any(), anyLong())).thenReturn(List.of(event));

    OutboxPublisherService service =
        new OutboxPublisherService(
            repo,
            e -> MessageDeliverer.DeliverResult.DELIVERED,
            CLOCK,
            props(50, 5, 1, 60, 300),
            registry);

    service.pollOnce();

    assertThat(
            registry
                .find(OutboxPublisherService.PUBLISH_ATTEMPTS_METRIC)
                .tag("outcome", "delivered")
                .counter()
                .count())
        .isEqualTo(1.0);
    // a delivered row is not a retry: the counter is never registered
    assertThat(registry.find(OutboxPublisherService.PUBLISH_RETRIES_METRIC).meters()).isEmpty();
  }

  @Test
  void poll_records_the_retry_counter_on_a_retryable_transient_failure() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    UUID id = UUID.randomUUID();
    OutboxEvent event = claimed(id, 1);
    when(repo.selectForClaim(anyInt())).thenReturn(List.of(event));
    when(repo.claim(any(), anyLong())).thenReturn(List.of(event));

    // maxAttempts = 3: the transient failure retries, so the retry counter increments
    OutboxPublisherService service =
        new OutboxPublisherService(
            repo,
            e -> MessageDeliverer.DeliverResult.TRANSIENT_FAILURE,
            CLOCK,
            props(50, 3, 1, 60, 300),
            registry);

    service.pollOnce();

    assertThat(
            registry
                .find(OutboxPublisherService.PUBLISH_ATTEMPTS_METRIC)
                .tag("outcome", "transient_failure")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(registry.find(OutboxPublisherService.PUBLISH_RETRIES_METRIC).counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void poll_does_not_record_a_retry_for_a_terminal_hold() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    UUID id = UUID.randomUUID();
    OutboxEvent event = claimed(id, 1);
    when(repo.selectForClaim(anyInt())).thenReturn(List.of(event));
    when(repo.claim(any(), anyLong())).thenReturn(List.of(event));

    // maxAttempts = 1: one transient failure exhausts the bound — a terminal hold, not a retry
    OutboxPublisherService service =
        new OutboxPublisherService(
            repo,
            e -> MessageDeliverer.DeliverResult.TRANSIENT_FAILURE,
            CLOCK,
            props(50, 1, 1, 60, 300),
            registry);

    service.pollOnce();

    assertThat(
            registry
                .find(OutboxPublisherService.PUBLISH_ATTEMPTS_METRIC)
                .tag("outcome", "transient_failure")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(registry.find(OutboxPublisherService.PUBLISH_RETRIES_METRIC).meters()).isEmpty();
  }

  @Test
  void poll_with_a_database_but_no_deliverer_is_a_no_op_and_emits_no_meters() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    OutboxPublisherService service =
        new OutboxPublisherService(repo, null, CLOCK, props(50, 5, 1, 60, 300), registry);

    service.pollOnce();

    assertThat(registry.find(OutboxPublisherService.PUBLISH_ATTEMPTS_METRIC).counter()).isNull();
    assertThat(registry.find(OutboxPublisherService.PUBLISH_RETRIES_METRIC).counter()).isNull();
  }

  @Test
  void pending_gauges_read_the_repository_at_scrape_time() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    when(repo.pendingSnapshot(300L)).thenReturn(new OutboxPendingSnapshot(7L, 42.5d));

    new OutboxPublisherService(
        repo, mock(MessageDeliverer.class), CLOCK, props(50, 5, 1, 60, 300), registry);

    // the gauges are only evaluated at scrape time
    Gauge pendingGauge = registry.find(OutboxPublisherService.PENDING_EVENTS_METRIC).gauge();
    Gauge oldestGauge = registry.find(OutboxPublisherService.OLDEST_AGE_METRIC).gauge();
    assertThat(pendingGauge).isNotNull();
    assertThat(oldestGauge).isNotNull();
    assertThat(pendingGauge.value()).isEqualTo(7.0);
    assertThat(oldestGauge.value()).isEqualTo(42.5);

    // the scrape re-reads the repository (the snapshot is live, never a cached counter):
    // one scrape per gauge
    verify(repo, org.mockito.Mockito.times(2)).pendingSnapshot(300L);
  }

  @Test
  void pending_gauge_reads_zero_when_the_repository_reports_an_empty_set() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    when(repo.pendingSnapshot(anyLong())).thenReturn(new OutboxPendingSnapshot(0L, 0.0d));

    new OutboxPublisherService(
        repo, mock(MessageDeliverer.class), CLOCK, props(50, 5, 1, 60, 300), registry);

    Gauge gauge = registry.find(OutboxPublisherService.PENDING_EVENTS_METRIC).gauge();
    assertThat(gauge.value()).isEqualTo(0.0);
  }

  @Test
  void a_null_repository_leaves_no_pending_gauges_registered() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    // no database: the poll would fail loudly, but the construction must still register
    // nothing (the gauges read the repository, so a null repository has nothing to read)
    new OutboxPublisherService(null, null, CLOCK, props(50, 5, 1, 60, 300), registry);

    assertThat(registry.find(OutboxPublisherService.PENDING_EVENTS_METRIC).gauge()).isNull();
    assertThat(registry.find(OutboxPublisherService.OLDEST_AGE_METRIC).gauge()).isNull();
  }

  @Test
  void a_null_registry_construction_does_not_fail_a_poll() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    UUID id = UUID.randomUUID();
    OutboxEvent event = claimed(id, 1);
    when(repo.selectForClaim(anyInt())).thenReturn(List.of(event));
    when(repo.claim(any(), anyLong())).thenReturn(List.of(event));

    // the 4-arg constructor passes a null registry — the poll must still emit (into a throwaway
    // one) and never fail on metering
    OutboxPublisherService service =
        new OutboxPublisherService(
            repo, e -> MessageDeliverer.DeliverResult.DELIVERED, CLOCK, props(50, 5, 1, 60, 300));

    int published = service.pollOnce();

    assertThat(published).isEqualTo(1);
    verify(repo).markSent(id);
  }

  private static OutboxEvent claimed(UUID id, int publishAttempts) {
    return new OutboxEvent(
        id,
        id,
        TENANT,
        "workflow_run",
        RUN,
        "agentflow.workflow.run-requested.v1",
        TENANT + ":" + RUN,
        "{\"runId\":\"" + RUN + "\"}",
        null,
        OutboxEvent.State.CLAIMED,
        publishAttempts,
        null,
        null,
        NOW,
        null);
  }

  private static AgentFlowProperties props(
      int batch, int max, long base, long maxDelay, long claimTimeout) {
    AgentFlowProperties properties = new AgentFlowProperties();
    AgentFlowProperties.Outbox.Publisher pub = properties.getOutbox().getPublisher();
    setInt(pub, "batchSize", batch);
    setInt(pub, "maxAttempts", max);
    setLong(pub, "baseDelaySeconds", base);
    setLong(pub, "maxDelaySeconds", maxDelay);
    setLong(pub, "claimTimeoutSeconds", claimTimeout);
    return properties;
  }

  private static void setInt(Object target, String field, int value) {
    try {
      java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      f.setInt(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void setLong(Object target, String field, long value) {
    try {
      java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      f.setLong(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
