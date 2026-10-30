package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * DB-free unit tests for the polling outbox publisher (S06-07), {@link OutboxPublisherService}.
 *
 * <p>The {@link OutboxEventRepository} is a Mockito mock (matching the DB-free {@code messaging}
 * suite convention) and a small in-memory {@link MessageDeliverer} stands in for the Kafka adapter
 * (wired in S06-09). A fixed {@link Clock} makes the bounded-retry {@code next_attempt_at}
 * deterministic. These tests prove the publisher <em>emits</em> the right claim/record calls in the
 * right order &mdash; the real-DB claim atomicity (two publishers never double-claim or lose a row)
 * is proven by {@code OutboxPublisherConcurrencyIT} on a real PostgreSQL.
 */
class OutboxPublisherServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID RUN = UUID.randomUUID();
  private static final OffsetDateTime NOW = OffsetDateTime.now();
  private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), java.time.ZoneOffset.UTC);

  @Test
  void poll_without_a_deliverer_is_a_no_op_and_leaves_rows_pending() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    OutboxPublisherService service =
        new OutboxPublisherService(repo, null, CLOCK, props(50, 5, 1, 60, 300));

    int published = service.pollOnce();

    assertThat(published).isZero();
    // Nothing is selected or claimed: the rows stay PENDING and are not lost (S06-07).
    verify(repo, never()).selectForClaim(anyInt());
    verify(repo, never()).claim(any(), anyLong());
  }

  @Test
  void poll_without_a_database_with_a_deliverer_reports_unavailable() {
    // A deliverer is present but no database: the poll fails loudly (503), mirroring the write path
    // (S01-02), rather than pretending to publish.
    OutboxPublisherService service =
        new OutboxPublisherService(
            null,
            event -> MessageDeliverer.DeliverResult.DELIVERED,
            CLOCK,
            props(50, 5, 1, 60, 300));

    assertThatThrownBy(service::pollOnce)
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void poll_selects_a_bounded_batch_and_marks_a_delivered_row_sent() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    UUID id = UUID.randomUUID();
    OutboxEvent event = claimed(id, 1);
    when(repo.selectForClaim(50)).thenReturn(List.of(event));
    when(repo.claim(eq(List.of(id)), anyLong())).thenReturn(List.of(event));

    OutboxPublisherService service =
        new OutboxPublisherService(
            repo, e -> MessageDeliverer.DeliverResult.DELIVERED, CLOCK, props(50, 5, 1, 60, 300));

    int published = service.pollOnce();

    assertThat(published).isEqualTo(1);
    verify(repo).selectForClaim(50); // the batch bound is respected
    verify(repo).claim(eq(List.of(id)), anyLong());
    verify(repo).markSent(id); // CLAIMED -> SENT
    verify(repo, never()).markFailed(any(), anyInt(), any(), any());
  }

  @Test
  void poll_records_a_transient_failure_with_a_backoff_instant_within_the_bound() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    UUID id = UUID.randomUUID();
    OutboxEvent event = claimed(id, 1);
    when(repo.selectForClaim(anyInt())).thenReturn(List.of(event));
    when(repo.claim(any(), anyLong())).thenReturn(List.of(event));

    // maxAttempts = 3, base delay = 2s: the first failure (attempt 1) retries at now + 2s.
    OutboxPublisherService service =
        new OutboxPublisherService(
            repo,
            e -> MessageDeliverer.DeliverResult.TRANSIENT_FAILURE,
            CLOCK,
            props(50, 3, 2, 60, 300));

    service.pollOnce();

    // the backoff instant is computed in UTC by the service; derive the expected from CLOCK so the
    // offset matches (NOW lives in the JVM default zone, the service uses ZoneOffset.UTC).
    OffsetDateTime expected =
        OffsetDateTime.ofInstant(CLOCK.instant().plusSeconds(2), ZoneOffset.UTC);
    verify(repo).markFailed(eq(id), eq(1), eq(expected), any());
    verify(repo, never()).markSent(any());
  }

  @Test
  void poll_holds_the_row_terminal_after_the_first_attempt_when_max_attempts_is_one() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    UUID id = UUID.randomUUID();
    OutboxEvent event = claimed(id, 1);
    when(repo.selectForClaim(anyInt())).thenReturn(List.of(event));
    when(repo.claim(any(), anyLong())).thenReturn(List.of(event));

    // maxAttempts = 1: a single transient failure exhausts the bound, so the retry instant is NULL
    // (terminal for the auto-poll, visible for the DLQ, S06-11).
    OutboxPublisherService service =
        new OutboxPublisherService(
            repo,
            e -> MessageDeliverer.DeliverResult.TRANSIENT_FAILURE,
            CLOCK,
            props(50, 1, 1, 60, 300));

    service.pollOnce();

    ArgumentCaptor<OffsetDateTime> next = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(repo).markFailed(eq(id), eq(1), next.capture(), any());
    // the next_attempt_at must be NULL: a terminal hold, not a scheduled retry
    assertThat(next.getValue()).isNull();
  }

  @Test
  void poll_marks_a_permanent_failure_terminal() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    UUID id = UUID.randomUUID();
    OutboxEvent event = claimed(id, 1);
    when(repo.selectForClaim(anyInt())).thenReturn(List.of(event));
    when(repo.claim(any(), anyLong())).thenReturn(List.of(event));

    OutboxPublisherService service =
        new OutboxPublisherService(
            repo,
            e -> MessageDeliverer.DeliverResult.PERMANENT_FAILURE,
            CLOCK,
            props(50, 5, 1, 60, 300));

    service.pollOnce();

    ArgumentCaptor<OffsetDateTime> next = ArgumentCaptor.forClass(OffsetDateTime.class);
    // Terminal hold (next_attempt_at NULL): held for the DLQ instead of retrying forever (S06-11).
    verify(repo).markFailed(eq(id), eq(1), next.capture(), any());
    assertThat(next.getValue()).isNull();
  }

  @Test
  void poll_records_ambiguous_timeout_as_a_retry_within_the_bound() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    UUID id = UUID.randomUUID();
    OutboxEvent event = claimed(id, 1);
    when(repo.selectForClaim(anyInt())).thenReturn(List.of(event));
    when(repo.claim(any(), anyLong())).thenReturn(List.of(event));

    // maxAttempts = 3, base delay = 2s: the first ambiguous outcome (attempt 1) re-publishes at
    // now + 2s; the row is never marked SENT (the broker may not hold it — S06-08).
    OutboxPublisherService service =
        new OutboxPublisherService(
            repo, e -> MessageDeliverer.DeliverResult.AMBIGUOUS, CLOCK, props(50, 3, 2, 60, 300));

    service.pollOnce();

    OffsetDateTime expected =
        OffsetDateTime.ofInstant(CLOCK.instant().plusSeconds(2), ZoneOffset.UTC);
    verify(repo).markFailed(eq(id), eq(1), eq(expected), any());
    verify(repo, never()).markSent(any());
  }

  @Test
  void poll_holds_ambiguous_terminal_after_exhausting_attempts() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    UUID id = UUID.randomUUID();
    OutboxEvent event = claimed(id, 1);
    when(repo.selectForClaim(anyInt())).thenReturn(List.of(event));
    when(repo.claim(any(), anyLong())).thenReturn(List.of(event));

    // maxAttempts = 1: a single ambiguous outcome exhausts the bound, so the retry instant is NULL
    // (terminal for the auto-poll, visible for the DLQ, S06-11); the event is not lost, only held.
    OutboxPublisherService service =
        new OutboxPublisherService(
            repo, e -> MessageDeliverer.DeliverResult.AMBIGUOUS, CLOCK, props(50, 1, 1, 60, 300));

    service.pollOnce();

    ArgumentCaptor<OffsetDateTime> next = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(repo).markFailed(eq(id), eq(1), next.capture(), any());
    assertThat(next.getValue()).isNull();
  }

  @Test
  void poll_publishes_nothing_when_a_concurrent_publisher_already_claimed_every_candidate() {
    OutboxEventRepository repo = mock(OutboxEventRepository.class);
    List<OutboxEvent> candidates = List.of(claimed(UUID.randomUUID(), 1));
    when(repo.selectForClaim(anyInt())).thenReturn(candidates);
    when(repo.claim(any(), anyLong())).thenReturn(List.of()); // every candidate lost the claim race

    OutboxPublisherService service =
        new OutboxPublisherService(
            repo, e -> MessageDeliverer.DeliverResult.DELIVERED, CLOCK, props(50, 5, 1, 60, 300));

    int published = service.pollOnce();

    assertThat(published).isZero();
    // The candidate that lost the claim is neither published nor marked by this publisher.
    verify(repo, never()).markSent(any());
    verify(repo, never()).markFailed(any(), anyInt(), any(), any());
  }

  /** Builds a {@code CLAIMED} outbox row carrying the post-claim {@code publishAttempts}. */
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

  /** Reflection helper: set the private publisher knobs, like {@code AgentFlowPropertiesTest}. */
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
