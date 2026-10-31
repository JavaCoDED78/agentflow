package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.DlqMessage;
import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for {@link InboxConsumerService} (S06-11): it records the consumer's failed outcome
 * to the DLQ only on a {@link InboxConsumeResult#RETRY} / {@link InboxConsumeResult#DEAD_LETTER}
 * disposition, and never on a {@link InboxConsumeResult#PROCESSED} / {@link
 * InboxConsumeResult#OTHER_CLAIMED} outcome (ack after durable outcome, invariant 3). The {@link
 * DlqService} is a mock and the {@link InboxMessageRepository} is {@code null} (the attempt count /
 * last error fall back to {@code 0}/{@code null}).
 */
class InboxConsumerServiceTest {

  private final InboxDedupService inboxDedup = mock(InboxDedupService.class);
  private final DlqService dlq = mock(DlqService.class);
  private final InboxMessageRepository inbox = null; // no read path: attempts/last_error fall back

  private final InboxMessage claim = InboxMessage.claiming("agent:run:step", UUID.randomUUID());
  private final String topic = "agent-run.v1";

  private InboxConsumerService service() {
    return new InboxConsumerService(inboxDedup, dlq, inbox);
  }

  @Test
  void apply_on_processed_does_not_record_to_dlq() {
    when(inboxDedup.consume(anyString(), any(), any())).thenReturn(InboxConsumeResult.PROCESSED);

    InboxConsumeResult result = service().apply(topic, claim, () -> "ok");

    assertThat(result).isEqualTo(InboxConsumeResult.PROCESSED);
    verify(dlq, never()).record(anyString(), any(), anyString(), any(), anyInt(), any());
  }

  @Test
  void apply_on_other_claimed_does_not_record_to_dlq() {
    when(inboxDedup.consume(anyString(), any(), any()))
        .thenReturn(InboxConsumeResult.OTHER_CLAIMED);

    InboxConsumeResult result = service().apply(topic, claim, () -> "ok");

    assertThat(result).isEqualTo(InboxConsumeResult.OTHER_CLAIMED);
    verify(dlq, never()).record(anyString(), any(), anyString(), any(), anyInt(), any());
  }

  @Test
  void apply_on_retry_records_a_retry_disposition_to_dlq() {
    when(inboxDedup.consume(anyString(), any(), any())).thenReturn(InboxConsumeResult.RETRY);

    InboxConsumeResult result =
        service()
            .apply(
                topic,
                claim,
                () -> {
                  throw new RuntimeException("transient broker fault");
                });

    assertThat(result).isEqualTo(InboxConsumeResult.RETRY);
    verify(dlq)
        .record(topic, claim.eventId(), claim.consumerName(), DlqMessage.Status.RETRY, 0, null);
  }

  @Test
  void apply_on_dead_letter_records_a_dead_letter_disposition_to_dlq() {
    when(inboxDedup.consume(anyString(), any(), any())).thenReturn(InboxConsumeResult.DEAD_LETTER);

    InboxConsumeResult result =
        service()
            .apply(
                topic,
                claim,
                () -> {
                  throw new IllegalArgumentException("malformed envelope");
                });

    assertThat(result).isEqualTo(InboxConsumeResult.DEAD_LETTER);
    verify(dlq)
        .record(
            topic, claim.eventId(), claim.consumerName(), DlqMessage.Status.DEAD_LETTER, 0, null);
  }
}
