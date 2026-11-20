package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.messaging.domain.DlqMessage;
import com.example.agentflow.messaging.domain.DlqRedriveAttempt;
import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.DlqMessageRepository;
import com.example.agentflow.messaging.persistence.DlqRedriveAttemptRepository;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * DB-free tests for {@link DlqRedriveService} (S12-12): the repositories and the audit port are
 * mocked, and the transaction runs over a mocked {@link DataSource} (the repos never touch it), so
 * the service is exercised at the control-flow level. The service is a {@code @Service} that builds
 * its own {@link TransactionTemplate} from the injected datasource, exactly as the app wires it —
 * so the same constructor is used here and in the integration test.
 */
class DlqRedriveServiceTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");
  private static final UUID DLQ_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ORIGINAL_EVENT =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID AGGREGATE = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final String TOPIC = "agent-run.v1";
  private static final String CONSUMER = "agent:run:step";
  private static final String REASON = "verified and retrying";
  private static final String ACTOR = "operator";

  private final DlqMessageRepository dlq = mock(DlqMessageRepository.class);
  private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
  private final InboxMessageRepository inbox = mock(InboxMessageRepository.class);
  private final DlqRedriveAttemptRepository redriveAttempts =
      mock(DlqRedriveAttemptRepository.class);
  private final AuditService audit = mock(AuditService.class);
  private final DataSource dataSource = mock(DataSource.class);
  private final Connection connection = mock(Connection.class);

  private DlqRedriveService service() {
    try {
      doReturn(connection).when(dataSource).getConnection();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return new DlqRedriveService(dlq, outbox, inbox, redriveAttempts, audit, dataSource);
  }

  private DlqRedriveRequest request(String reason) {
    return new DlqRedriveRequest(reason);
  }

  private DlqMessage dlqMessage() {
    return DlqMessage.read(
        DLQ_ID,
        ORIGINAL_EVENT,
        TOPIC,
        CONSUMER,
        DlqMessage.Status.DEAD_LETTER,
        DlqMessage.ErrorClass.PERMANENT,
        3,
        "redacted failure",
        java.time.OffsetDateTime.parse("2026-11-14T12:00:00Z"));
  }

  private OutboxEvent originalEnvelope() {
    return OutboxEvent.pending(
        UUID.randomUUID(),
        ORIGINAL_EVENT,
        "agent-run.requested.v1",
        TOPIC,
        "workflow_run",
        AGGREGATE,
        TENANT,
        "{\"payload\":true}",
        "{\"traceparent\":\"00-abc\"}");
  }

  @Test
  void redrive_republishes_under_a_fresh_event_id_records_the_attempt_and_audits() {
    when(dlq.findById(DLQ_ID)).thenReturn(Optional.of(dlqMessage()));
    when(outbox.findByEventId(ORIGINAL_EVENT)).thenReturn(Optional.of(originalEnvelope()));
    when(inbox.findByConsumerAndEvent(CONSUMER, ORIGINAL_EVENT)).thenReturn(Optional.empty());
    when(redriveAttempts.findByDlqId(DLQ_ID)).thenReturn(Optional.empty());

    DlqRedriveResult result = service().redrive(DLQ_ID, TENANT, ACTOR, request(REASON));

    assertThat(result.dlqId()).isEqualTo(DLQ_ID);
    assertThat(result.originalEventId()).isEqualTo(ORIGINAL_EVENT);
    assertThat(result.requeuedEventId()).isNotNull();
    // the re-published envelope uses a FRESH event id, so the inbox re-runs the business change
    // once (invariant 3) — never the same failed event id.
    assertThat(result.requeuedEventId()).isNotEqualTo(ORIGINAL_EVENT);

    ArgumentCaptor<OutboxEvent> requeued = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outbox).insert(requeued.capture());
    assertThat(requeued.getValue().eventId()).isEqualTo(result.requeuedEventId());
    assertThat(requeued.getValue().tenantId()).isEqualTo(TENANT);
    assertThat(requeued.getValue().payloadJson()).isEqualTo(originalEnvelope().payloadJson());
    verify(redriveAttempts).insert(any(DlqRedriveAttempt.class));
    verify(audit).record(TENANT, ACTOR, AuditAction.READ, AuditResourceType.DLQ, DLQ_ID, REASON);
  }

  @Test
  void a_blank_reason_is_a_400() {
    DlqRedriveService service = service();
    when(dlq.findById(DLQ_ID)).thenReturn(Optional.of(dlqMessage()));

    assertThatThrownBy(() -> service.redrive(DLQ_ID, TENANT, ACTOR, new DlqRedriveRequest("  ")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

    verifyNoInteractions(outbox);
    verifyNoInteractions(redriveAttempts);
  }

  @Test
  void a_missing_dlq_record_is_a_404_and_republishes_nothing() {
    when(dlq.findById(DLQ_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().redrive(DLQ_ID, TENANT, ACTOR, request(REASON)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

    verifyNoInteractions(outbox);
    verifyNoInteractions(redriveAttempts);
    verify(audit, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void a_missing_original_envelope_is_a_404() {
    when(dlq.findById(DLQ_ID)).thenReturn(Optional.of(dlqMessage()));
    when(outbox.findByEventId(ORIGINAL_EVENT)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().redrive(DLQ_ID, TENANT, ACTOR, request(REASON)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

    verify(redriveAttempts, never()).insert(any());
  }

  @Test
  void an_already_processed_inbox_is_refused_409() {
    when(dlq.findById(DLQ_ID)).thenReturn(Optional.of(dlqMessage()));
    when(outbox.findByEventId(ORIGINAL_EVENT)).thenReturn(Optional.of(originalEnvelope()));
    when(inbox.findByConsumerAndEvent(CONSUMER, ORIGINAL_EVENT))
        .thenReturn(Optional.of(processedInbox()));
    when(redriveAttempts.findByDlqId(DLQ_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().redrive(DLQ_ID, TENANT, ACTOR, request(REASON)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    // the business change already completed: the redrive never re-publishes (invariant 3 / S12-12).
    verify(outbox, never()).insert(any(OutboxEvent.class));
    verify(redriveAttempts, never()).insert(any());
  }

  @Test
  void an_in_flight_inbox_is_refused_409() {
    when(dlq.findById(DLQ_ID)).thenReturn(Optional.of(dlqMessage()));
    when(outbox.findByEventId(ORIGINAL_EVENT)).thenReturn(Optional.of(originalEnvelope()));
    when(inbox.findByConsumerAndEvent(CONSUMER, ORIGINAL_EVENT))
        .thenReturn(Optional.of(pendingInbox()));
    when(redriveAttempts.findByDlqId(DLQ_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().redrive(DLQ_ID, TENANT, ACTOR, request(REASON)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    verify(outbox, never()).insert(any(OutboxEvent.class));
  }

  @Test
  void a_replay_of_an_already_redriven_record_is_idempotent() {
    DlqRedriveAttempt recorded =
        DlqRedriveAttempt.created(
            DLQ_ID,
            ORIGINAL_EVENT,
            UUID.randomUUID(),
            REASON,
            ACTOR,
            TENANT,
            java.time.OffsetDateTime.parse("2026-11-14T13:00:00Z"));
    when(dlq.findById(DLQ_ID)).thenReturn(Optional.of(dlqMessage()));
    when(redriveAttempts.findByDlqId(DLQ_ID)).thenReturn(Optional.of(recorded));

    DlqRedriveResult result = service().redrive(DLQ_ID, TENANT, ACTOR, request(REASON));

    assertThat(result.requeuedEventId()).isEqualTo(recorded.requeuedEventId());
    assertThat(result.reason()).isEqualTo(REASON);
    // the guard never ran and nothing was re-published: a replay returns the existing redrive.
    verifyNoInteractions(outbox);
    verify(inbox, never()).findByConsumerAndEvent(any(), any());
    verify(audit, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void the_service_reports_a_503_when_no_database_is_configured() {
    DlqRedriveService noDb =
        new DlqRedriveService(dlq, outbox, inbox, redriveAttempts, audit, null);

    assertThatThrownBy(() -> noDb.redrive(DLQ_ID, TENANT, ACTOR, request(REASON)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  private InboxMessage processedInbox() {
    return InboxMessage.claimed(
        CONSUMER,
        ORIGINAL_EVENT,
        InboxMessage.Status.PROCESSED,
        3,
        null,
        "redacted",
        "applied",
        java.time.OffsetDateTime.parse("2026-11-14T11:00:00Z"),
        java.time.OffsetDateTime.parse("2026-11-14T10:00:00Z"));
  }

  private InboxMessage pendingInbox() {
    return InboxMessage.claimed(
        CONSUMER,
        ORIGINAL_EVENT,
        InboxMessage.Status.PENDING,
        1,
        null,
        null,
        null,
        java.time.OffsetDateTime.parse("2026-11-14T11:00:00Z"),
        java.time.OffsetDateTime.parse("2026-11-14T11:00:00Z"));
  }
}
