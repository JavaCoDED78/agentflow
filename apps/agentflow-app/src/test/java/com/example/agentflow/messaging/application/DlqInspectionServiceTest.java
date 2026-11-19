package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.messaging.domain.DlqMessage;
import com.example.agentflow.messaging.persistence.DlqMessageRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * DB-free tests for {@link DlqInspectionService} (S12-11): a paginated, read-only list and an
 * audited detail view, with the {@link DlqMessageRepository} and {@link AuditService} mocked so the
 * data-shape logic runs without a database (the real repository path is covered by the integration
 * tests).
 */
class DlqInspectionServiceTest {

  private final DlqMessageRepository repository = mock(DlqMessageRepository.class);
  private final AuditService auditService = mock(AuditService.class);
  private final DlqInspectionService service = new DlqInspectionService(repository, auditService);

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID DLQ_ID = UUID.randomUUID();
  private static final OffsetDateTime NOW = OffsetDateTime.now();

  private static DlqMessage message(UUID id) {
    return DlqMessage.read(
        id,
        UUID.randomUUID(),
        "agent-run.v1",
        "agent:run:step",
        DlqMessage.Status.DEAD_LETTER,
        DlqMessage.ErrorClass.PERMANENT,
        3,
        "Envelope body was redacted by the DLQ store",
        NOW);
  }

  // --- list ---

  @Test
  void list_pages_the_store_and_returns_the_overview_metadata() {
    DlqMessage row = message(DLQ_ID);
    when(repository.findAllPageable(anyInt(), anyInt())).thenReturn(List.of(row));
    when(repository.countAll()).thenReturn(1L);

    DlqMessagePageDto page = service.listDlqMessages(0, 20);

    assertThat(page.items()).hasSize(1);
    DlqMessageDto item = page.items().get(0);
    // The overview carries the structural identity, not the failure text (lastError has no
    // field on DlqMessageDto — the shape itself omits it; the detail view is the only view that
    // does).
    assertThat(item.errorClass()).isEqualTo(DlqMessage.ErrorClass.PERMANENT);
    assertThat(item.consumerName()).isEqualTo("agent:run:step");
    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(20);
    assertThat(page.totalElements()).isEqualTo(1L);
    assertThat(page.hasNext()).isFalse();
    assertThat(page.hasPrevious()).isFalse();
    verify(repository).findAllPageable(eq(0), eq(20));
    verify(repository).countAll();
  }

  @Test
  void list_clamps_the_requested_size_to_the_max_page_size() {
    when(repository.findAllPageable(anyInt(), anyInt())).thenReturn(List.of(message(DLQ_ID)));
    when(repository.countAll()).thenReturn(50L);

    DlqMessagePageDto page = service.listDlqMessages(0, 500);

    assertThat(page.size()).isEqualTo(100);
    // The repository was asked for the clamped size and the matching offset, never the raw 500.
    verify(repository).findAllPageable(eq(0), eq(100));
  }

  @Test
  void list_reports_400_for_invalid_pagination() {
    assertThatThrownBy(() -> service.listDlqMessages(-1, 20))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThatThrownBy(() -> service.listDlqMessages(0, 0))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    verify(repository, never()).findAllPageable(anyInt(), anyInt());
  }

  @Test
  void list_reports_503_when_no_database_is_configured() {
    DlqInspectionService noDb = new DlqInspectionService(null, auditService);

    assertThatThrownBy(() -> noDb.listDlqMessages(0, 20))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  // --- inspect (audited detail view) ---

  @Test
  void inspect_returns_the_full_view_and_records_the_audit() {
    when(repository.findById(DLQ_ID)).thenReturn(Optional.of(message(DLQ_ID)));

    DlqMessageDetailDto detail = service.inspectDlqMessage(DLQ_ID, TENANT, "local");

    assertThat(detail.errorClass()).isEqualTo(DlqMessage.ErrorClass.PERMANENT);
    assertThat(detail.lastError()).isEqualTo("Envelope body was redacted by the DLQ store");
    // The sensitive detail view is audited (S12-07 §4.6: a READ on the DLQ resource).
    verify(auditService, times(1))
        .record(
            eq(TENANT),
            eq("local"),
            eq(AuditAction.READ),
            eq(AuditResourceType.DLQ),
            eq(DLQ_ID),
            any());
  }

  @Test
  void inspect_reports_404_and_records_no_audit_for_unknown_id() {
    when(repository.findById(DLQ_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.inspectDlqMessage(DLQ_ID, TENANT, "local"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void inspect_reports_503_when_no_database_is_configured() {
    DlqInspectionService noDb = new DlqInspectionService(null, auditService);

    assertThatThrownBy(() -> noDb.inspectDlqMessage(DLQ_ID, TENANT, "local"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
  }
}
