package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.InboxMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for the S13-12 consumer-failure counter on {@link InboxConsumerService}: one
 * increment per failed business change, tagged with the bounded disposition ({@code retry} / {@code
 * dead_letter}). The {@link InboxDedupService} is a mock; the {@link DlqService} is a mock; the
 * registry is a real {@link SimpleMeterRegistry}.
 */
class InboxConsumerMetricsTest {

  private final InboxDedupService inboxDedup = mock(InboxDedupService.class);
  private final DlqService dlq = mock(DlqService.class);

  private final InboxMessage claim = InboxMessage.claiming("agent:run:step", UUID.randomUUID());
  private final String topic = "agent-run.v1";

  @Test
  void apply_on_retry_records_the_failure_counter_tagged_with_retry() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(inboxDedup.consume(anyString(), any(), any())).thenReturn(InboxConsumeResult.RETRY);

    InboxConsumeResult result =
        new InboxConsumerService(inboxDedup, dlq, null, registry).apply(topic, claim, () -> "ok");

    assertThat(result).isEqualTo(InboxConsumeResult.RETRY);
    assertThat(
            registry
                .find(InboxConsumerService.PROCESSING_FAILURES_METRIC)
                .tag("outcome", "retry")
                .counter()
                .count())
        .isEqualTo(1.0);
    verify(dlq).record(anyString(), any(), anyString(), any(), any(), anyInt(), any());
  }

  @Test
  void apply_on_dead_letter_records_the_failure_counter_tagged_with_dead_letter() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(inboxDedup.consume(anyString(), any(), any())).thenReturn(InboxConsumeResult.DEAD_LETTER);

    InboxConsumeResult result =
        new InboxConsumerService(inboxDedup, dlq, null, registry).apply(topic, claim, () -> "ok");

    assertThat(result).isEqualTo(InboxConsumeResult.DEAD_LETTER);
    assertThat(
            registry
                .find(InboxConsumerService.PROCESSING_FAILURES_METRIC)
                .tag("outcome", "dead_letter")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void apply_on_processed_records_no_failure_counter() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(inboxDedup.consume(anyString(), any(), any())).thenReturn(InboxConsumeResult.PROCESSED);

    InboxConsumeResult result =
        new InboxConsumerService(inboxDedup, dlq, null, registry).apply(topic, claim, () -> "ok");

    assertThat(result).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(registry.find(InboxConsumerService.PROCESSING_FAILURES_METRIC).counter()).isNull();
  }

  @Test
  void apply_on_other_claimed_records_no_failure_counter() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(inboxDedup.consume(anyString(), any(), any()))
        .thenReturn(InboxConsumeResult.OTHER_CLAIMED);

    new InboxConsumerService(inboxDedup, dlq, null, registry).apply(topic, claim, () -> "ok");

    assertThat(registry.find(InboxConsumerService.PROCESSING_FAILURES_METRIC).counter()).isNull();
  }

  @Test
  void apply_with_a_null_registry_does_not_fail() {
    when(inboxDedup.consume(anyString(), any(), any())).thenReturn(InboxConsumeResult.RETRY);

    InboxConsumeResult result =
        new InboxConsumerService(inboxDedup, dlq, null).apply(topic, claim, () -> "ok");

    assertThat(result).isEqualTo(InboxConsumeResult.RETRY);
    verify(dlq).record(anyString(), any(), anyString(), any(), any(), anyInt(), any());
  }
}
