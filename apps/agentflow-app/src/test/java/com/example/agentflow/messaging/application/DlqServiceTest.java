package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.DlqMessage;
import com.example.agentflow.messaging.persistence.DlqMessageRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DB-free tests for {@link DlqService} (S06-11): with a {@link DlqMessageRepository} it builds a
 * {@link DlqMessage} with the right fields and returns the repository's upsert result; with a
 * {@code null} repository (no database configured, S01-02) it returns {@code false} and records
 * nothing (never throws, so the skeleton still boots).
 */
class DlqServiceTest {

  private final DlqMessageRepository repository = mock(DlqMessageRepository.class);
  private final DlqService service = new DlqService(repository);

  private static final UUID EVENT_ID = UUID.randomUUID();
  private static final String TOPIC = "agent-run.v1";
  private static final String CONSUMER = "agent:run:step";

  @Test
  void record_builds_the_dlq_record_and_returns_the_upsert_result() {
    DlqMessage.Status status = DlqMessage.Status.DEAD_LETTER;
    when(repository.record(any(DlqMessage.class))).thenReturn(true);

    boolean inserted = service.record(TOPIC, EVENT_ID, CONSUMER, status, 3, "malformed envelope");

    assertThat(inserted).isTrue();
    ArgumentCaptor<DlqMessage> captured = ArgumentCaptor.forClass(DlqMessage.class);
    verify(repository).record(captured.capture());
    DlqMessage record = captured.getValue();
    assertThat(record.eventId()).isEqualTo(EVENT_ID);
    assertThat(record.topic()).isEqualTo(TOPIC);
    assertThat(record.consumerName()).isEqualTo(CONSUMER);
    assertThat(record.status()).isEqualTo(status);
    assertThat(record.attempts()).isEqualTo(3);
    assertThat(record.lastError()).isEqualTo("malformed envelope");
  }

  @Test
  void record_returns_false_when_the_record_already_exists() {
    // A duplicate Kafka delivery produces a duplicate (consumer, event) pair; the idempotent upsert
    // collapses it to one record (invariant 3 / ADR-003), so the DLQ record is not appended twice.
    when(repository.record(any(DlqMessage.class))).thenReturn(false);

    boolean inserted =
        service.record(TOPIC, EVENT_ID, CONSUMER, DlqMessage.Status.RETRY, 2, "retry");

    assertThat(inserted).isFalse();
  }

  @Test
  void record_with_null_repository_returns_false_and_records_nothing() {
    DlqService serviceNoDb = new DlqService(null);

    boolean inserted =
        serviceNoDb.record(TOPIC, EVENT_ID, CONSUMER, DlqMessage.Status.DEAD_LETTER, 0, null);

    assertThat(inserted).isFalse();
    verify(repository, never()).record(any(DlqMessage.class));
  }
}
