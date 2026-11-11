package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

import com.example.agentflow.knowledgebase.domain.DocumentContentType;
import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
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

  private DocumentService service;
  private ValidatedUpload validated;

  @BeforeEach
  void setUp() {
    service = new DocumentService(documentRepository, outboxService);
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
  void uploadCommitsTheDocumentRowTenantScopedInUpLoaded() {
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
  void uploadAppendsTheIngestionRequestedEventToTheOutbox() {
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
  void uploadReturnsTheAsyncUpLoadedStatus() {
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
  void rowAndEventAreWrittenTogetherInOneFlow() {
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
  void uploadDoesNotTransitionTheDocumentQueued() {
    DocumentDto result = service.upload(TENANT, KNOWLEDGE_BASE, validated, null);

    assertThat(result.status()).isNotEqualTo(DocumentUploadStatus.QUEUED);
  }
}
