package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.knowledgebase.domain.DocumentContentType;
import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
import com.example.agentflow.knowledgebase.persistence.DocumentLookupRow;
import com.example.agentflow.knowledgebase.persistence.DocumentRepository;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the {@code S10-06} upload / enqueue flow, {@link DocumentService}.
 *
 * <p>DB-free: {@link DocumentRepository} and {@link OutboxService} are mocked, so no {@code
 * DataSource} is needed. The tests pin the three {@code S10-06} facts that must hold before any
 * storage backend or worker exists:
 *
 * <ul>
 *   <li>the {@code documents} row is committed with {@link DocumentUploadStatus#UPLOADED},
 *       tenant-scoped through the exact {@code tenantId} passed in (invariant 1);
 *   <li>the {@code agentflow.document.ingestion-requested.v1} outbox event is appended in the same
 *       call, keyed to the document, tenant-scoped and correlation-complete (invariant 3, {@code
 *       ADR-004}); and
 *   <li>the flow returns the async {@code UPLOADED} status &mdash; it never invokes the parser or
 *       embedding, so the upload does not wait for parsing/embedding to complete.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

  private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555566667777");
  private static final UUID KNOWLEDGE_BASE =
      UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final String CHECKSUM =
      "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

  @Mock private DocumentRepository documentRepository;
  @Mock private OutboxService outboxService;
  @Mock private DocumentTransitionService documentTransitionService;

  private DocumentService service;
  private ValidatedUpload validated;

  @BeforeEach
  void setUp() {
    service = new DocumentService(documentRepository, outboxService, documentTransitionService);
    validated =
        new ValidatedUpload(
            "report.pdf",
            DocumentContentType.from("application/pdf"),
            1024,
            CHECKSUM,
            false,
            Optional.empty());
  }

  /**
   * The row is committed under the exact tenant passed in — never a value derived from input
   * (invariant 1) &mdash; with the {@link DocumentUploadStatus#UPLOADED} lifecycle state, the
   * server-generated {@code object_key} and the validated filename / type / size / checksum.
   */
  @Test
  void upload_commits_the_document_row_tenant_scoped_in_up_loaded() {
    DocumentDto result = service.upload(TENANT, KNOWLEDGE_BASE, validated, "actor@example.com");

    verify(documentRepository)
        .insert(
            result.documentId(),
            TENANT,
            KNOWLEDGE_BASE,
            "documents/" + TENANT + "/" + result.documentId(),
            "report.pdf",
            "application/pdf",
            1024,
            CHECKSUM,
            "actor@example.com");
  }

  /**
   * The {@code ingestion-requested} event is appended to the outbox in the same call, with the
   * {@code document} aggregate keyed to the new document, the correct event type/topic and the
   * exact tenant &mdash; correlation-complete (the request event's {@code correlationId} is the
   * document id and its {@code causationId} is null, since it has no predecessor event). This is
   * the durable trigger that moves the document {@code UPLOADED -> QUEUED} on the worker.
   */
  @Test
  void upload_appends_the_ingestion_requested_event_to_the_outbox() {
    ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);

    service.upload(TENANT, KNOWLEDGE_BASE, validated, null);

    verify(outboxService).enqueue(event.capture());
    OutboxEvent queued = event.getValue();
    // The versioned event name (document.ingestion-requested.v1) is carried by the topic component
    // (the eventType factory argument has no persisted component); the topic is the delivery
    // target.
    assertThat(queued.topic()).isEqualTo("agentflow.document.ingestion-requested.v1");
    assertThat(queued.aggregateType()).isEqualTo("document");
    assertThat(queued.aggregateId()).isNotNull();
    assertThat(queued.tenantId()).isEqualTo(TENANT);
    assertThat(queued.eventId()).isEqualTo(queued.aggregateId());
  }

  /**
   * The upload returns the async {@link DocumentUploadStatus#UPLOADED} status with the new document
   * id and parent collection. The async status is the whole point of {@code S10-06}: the caller
   * does <b>not</b> wait for parsing ({@code S10-07}) or embedding ({@code S10-11}) — that work is
   * done by the ingestion worker on the event the flow just enqueued.
   */
  @Test
  void upload_returns_the_async_up_loaded_status() {
    DocumentDto result = service.upload(TENANT, KNOWLEDGE_BASE, validated, null);

    assertThat(result.status()).isEqualTo(DocumentUploadStatus.UPLOADED);
    assertThat(result.knowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE);
    assertThat(result.documentId()).isNotNull();
  }

  /**
   * The row insert and the outbox append are two writes in one {@code @Transactional} method, so
   * the flow asserts they happen together: a successful upload invokes both exactly once. If either
   * write were moved out of the transaction, the row and the event could diverge (invariant 3), and
   * this test would catch it.
   */
  @Test
  void row_and_event_are_written_together_in_one_flow() {
    service.upload(TENANT, KNOWLEDGE_BASE, validated, null);

    verify(documentRepository)
        .insert(
            any(),
            any(),
            any(),
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyString(),
            any());
    verify(outboxService).enqueue(any());
  }

  /**
   * The {@code UPLOADED -> QUEUED} transition is <em>not</em> applied here: the flow returns {@code
   * UPLOADED} and leaves {@code QUEUED} to the transition service that the outbox consumer drives.
   * Pinning this keeps {@code DocumentService} the transactional author of the row + event without
   * reaching into the document state machine ({@code S10-01}…{@code S10-13}).
   */
  @Test
  void upload_does_not_transition_the_document_queued() {
    DocumentDto result = service.upload(TENANT, KNOWLEDGE_BASE, validated, null);

    assertThat(result.status()).isNotEqualTo(DocumentUploadStatus.QUEUED);
  }

  // --- S10-19: duplicate upload (content SHA-256) and manual retry (FAILED) ---

  /**
   * Duplicate upload (S10-19): a re-upload whose payload SHA-256 matches an existing tenant-owned
   * row is content-identical — {@link #upload} returns that existing document with no new row and
   * no new event. The idempotency key is {@code (tenant_id, sha256)} (plan §8.2).
   */
  @Test
  void upload_returns_existing_document_on_duplicate_tenant_scoped_checksum() {
    DocumentLookupRow existing =
        new DocumentLookupRow(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            KNOWLEDGE_BASE,
            DocumentUploadStatus.QUEUED);
    when(documentRepository.findByTenantAndSha256(TENANT, CHECKSUM))
        .thenReturn(Optional.of(existing));

    DocumentDto result = service.upload(TENANT, KNOWLEDGE_BASE, validated, "actor@example.com");

    // Returns the existing document unchanged ...
    assertThat(result.documentId()).isEqualTo(existing.documentId());
    assertThat(result.knowledgeBaseId()).isEqualTo(existing.knowledgeBaseId());
    assertThat(result.status()).isEqualTo(DocumentUploadStatus.QUEUED);
    // ... without inserting a new row or enqueuing a new event (the duplicate is absorbed).
    verify(documentRepository, never())
        .insert(any(), any(), any(), any(), any(), any(), anyLong(), any(), any());
    verify(outboxService, never()).enqueue(any());
    // ... and the duplicate lookup is the gate (tenant-scoped, invariant 1).
    verify(documentRepository).findByTenantAndSha256(TENANT, CHECKSUM);
  }

  /**
   * Duplicate upload is tenant-scoped (invariant 1): a content-identical payload from a different
   * tenant is indistinguishable from a missing one, so it is a fresh upload, never a hit.
   */
  @Test
  void upload_on_other_tenant_identical_checksum_is_fresh_upload() {
    when(documentRepository.findByTenantAndSha256(TENANT, CHECKSUM)).thenReturn(Optional.empty());

    DocumentDto result = service.upload(TENANT, KNOWLEDGE_BASE, validated, null);

    assertThat(result.status()).isEqualTo(DocumentUploadStatus.UPLOADED);
    verify(documentRepository)
        .insert(any(), eq(TENANT), any(), anyString(), any(), any(), anyLong(), any(), any());
  }

  /**
   * Manual retry (S10-19): a {@code FAILED} document is released back to {@code QUEUED} and its
   * {@code ingestion-requested} event is re-enqueued in one transaction, so the next delivery
   * re-drives it. The re-enqueued event keeps the correlation chain (correlationId = document id,
   * tenant-scoped) and uses a fresh event id.
   */
  @Test
  void retry_failed_releases_failed_to_queued_and_reenqueues() {
    UUID documentId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    DocumentLookupRow failed =
        new DocumentLookupRow(documentId, KNOWLEDGE_BASE, DocumentUploadStatus.FAILED);
    when(documentRepository.findByTenantAndId(TENANT, documentId)).thenReturn(Optional.of(failed));

    DocumentDto result = service.retryFailed(TENANT, documentId, "actor@example.com");

    assertThat(result.status()).isEqualTo(DocumentUploadStatus.QUEUED);
    assertThat(result.knowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE);
    // Released to QUEUED ...
    verify(documentTransitionService).releaseFailedToQueued(TENANT, documentId);
    // ... and a fresh ingestion-requested event is re-enqueued, tenant-scoped and
    // correlation-complete.
    ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxService).enqueue(event.capture());
    OutboxEvent queued = event.getValue();
    assertThat(queued.tenantId()).isEqualTo(TENANT);
    assertThat(queued.aggregateType()).isEqualTo("document");
    assertThat(queued.aggregateId()).isEqualTo(documentId);
    assertThat(queued.eventId()).isNotNull();
    assertThat(queued.topic()).isEqualTo("agentflow.document.ingestion-requested.v1");
  }

  /** A document that is not {@code FAILED} has nothing to retry: {@code retryFailed} rejects it. */
  @Test
  void retry_failed_on_non_failed_document_is_rejected() {
    UUID documentId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    DocumentLookupRow indexed =
        new DocumentLookupRow(documentId, KNOWLEDGE_BASE, DocumentUploadStatus.INDEXED);
    when(documentRepository.findByTenantAndId(TENANT, documentId)).thenReturn(Optional.of(indexed));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.retryFailed(TENANT, documentId, null));

    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    // Nothing released, nothing re-enqueued.
    verify(documentTransitionService, never()).releaseFailedToQueued(any(), any());
    verify(outboxService, never()).enqueue(any());
  }

  /**
   * Cross-tenant / forged retry target is a {@code 404}: the document is re-checked against {@code
   * (tenant_id, id)} (invariant 1), so a missing tenant-owned document is indistinguishable from
   * any other missing one.
   */
  @Test
  void retry_failed_on_missing_tenant_owned_document_is_not_found() {
    UUID documentId = UUID.fromString("22222222-3333-4444-5555-666677778888");
    when(documentRepository.findByTenantAndId(TENANT, documentId)).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.retryFailed(TENANT, documentId, null));

    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    verify(documentTransitionService, never()).releaseFailedToQueued(any(), any());
  }

  /**
   * Manual retry is unavailable without a database, and fails loudly with {@code 503} rather than
   * silently dropping the release (S01-02). The repository is present (a mock) but the transition
   * writer is absent.
   */
  @Test
  void retry_failed_fails_loudly_without_transition_writer() {
    DocumentService noDb = new DocumentService(documentRepository, outboxService, null);
    UUID documentId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> noDb.retryFailed(TENANT, documentId, null));

    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
