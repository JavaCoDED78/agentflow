package com.example.agentflow.knowledgebase.application;

import com.example.agentflow.knowledgebase.domain.DocumentObjectKey;
import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
import com.example.agentflow.knowledgebase.persistence.DocumentRepository;
import com.example.agentflow.messaging.application.CorrelationHeaders;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The upload / ingestion-enqueue application use case, {@code S10-06}.
 *
 * <p>{@link #upload} is the single entry point that turns a {@link ValidatedUpload} (the {@code
 * S10-03} accept/bound-checked payload) into a durable, queued document. Each call:
 *
 * <ol>
 *   <li>generates a server-issued {@code documentId} up front (stable across a retry) and its
 *       {@code object_key} ({@link DocumentObjectKey#of(UUID, UUID)} — server-owned, tenant-scoped,
 *       never the client filename, {@code S10-02});
 *   <li>inserts the {@code documents} metadata row in status {@link DocumentUploadStatus#UPLOADED}
 *       (the state the {@code S10-03} upload returns) through {@link DocumentRepository#insert};
 *       and
 *   <li>appends the {@code agentflow.document.ingestion-requested.v1} event to the transactional
 *       outbox through {@link OutboxService#enqueue(OutboxEvent)}.
 * </ol>
 *
 * <p>Both writes run inside one {@link Transactional} method with the default {@code REQUIRED}
 * propagation, so the row and its event commit atomically &mdash; a document is never committed
 * without its outbox event and the event is never committed without durable state (invariant 3,
 * {@code ADR-004}). This is precisely what makes the upload <b>non-blocking on
 * parsing/embedding</b>: the HTTP response returns {@code UPLOADED} as soon as the row + event are
 * durably written, and the {@code QUEUED -> PROCESSING -> INDEXED} work is done later by the
 * ingestion worker on the event. The parser ({@code S10-07}) and the embedding pipeline ({@code
 * S10-11}) are never invoked here, directly or indirectly, so an upload never waits for them (the
 * {@code S10-06} acceptance criterion).
 *
 * <p>The {@code tenantId} comes from the verified security context; it is never taken from the
 * request body, query or a Kafka header (invariant 1). The {@code object_key} is a namespacing
 * defence-in-depth only (invariant 1); the {@code documents} row is always read by {@code
 * (tenant_id, id)} before the key is ever used to read an object, so another tenant's document is
 * indistinguishable from a missing one.
 *
 * <p>The event carries no secret, full prompt or full document payload: it is correlation metadata
 * (the {@code documentId} and the parent {@code knowledgeBaseId}, both opaque identifiers) plus the
 * {@code CorrelationHeaders} (a {@code correlationId} = the document id and a {@code null} {@code
 * causationId}, the request event having no predecessor). Idempotency by content ({@code
 * (tenant_id, sha256)}) is enforced at the consumer, not here (plan §8.2).
 */
@Service
public class DocumentService {

  private final DocumentRepository documentRepository;
  private final OutboxService outboxService;

  /**
   * @param documentRepository the tenant-scoped {@code documents} writer, or {@code null} when no
   *     database is configured (S01-02); injected as a constructor <em>parameter</em> with {@code
   *     required = false} so Spring null-injects it when no {@code DataSource} exists (the
   *     application still boots without a database; {@link #upload} fails loudly with {@code 503})
   * @param outboxService the transactional-outbox appender ({@code messaging}); used exactly as
   *     {@code workflowexecution} uses it to publish through the outbox port
   */
  @Autowired
  public DocumentService(
      @Autowired(required = false) DocumentRepository documentRepository,
      OutboxService outboxService) {
    this.documentRepository = documentRepository;
    this.outboxService = outboxService;
  }

  /**
   * Commit a document's metadata row and enqueue its {@code ingestion-requested} event in one
   * transaction ({@code S10-06}).
   *
   * @param tenantId the owning tenant (from the verified security context; never from input)
   * @param knowledgeBaseId the parent collection the document is uploaded into
   * @param validated the {@code S10-03} accept/bound-checked upload (filename, type, size,
   *     checksum)
   * @param createdBy the actor recorded on the row, or {@code null} for a system actor
   * @return the persisted document in the {@link DocumentUploadStatus#UPLOADED} state: the id, the
   *     parent collection and {@code UPLOADED}. The async status signals that parsing/embedding has
   *     <em>not</em> run and will not be awaited here.
   * @throws org.springframework.web.server.ResponseStatusException {@code 503} when no database is
   *     configured (S01-02); the upload fails loudly rather than dropping the row or the event
   */
  @Transactional
  public DocumentDto upload(
      UUID tenantId, UUID knowledgeBaseId, ValidatedUpload validated, String createdBy) {
    requireRepository(documentRepository);
    // Stable id across a retry; the row and the event carry it. The object key is server-owned and
    // tenant-scoped (S10-02); its authoritative type is documents.mime_type, so the key carries no
    // content-type extension.
    UUID documentId = UUID.randomUUID();
    String objectKey = DocumentObjectKey.of(tenantId, documentId).value();
    documentRepository.insert(
        documentId,
        tenantId,
        knowledgeBaseId,
        objectKey,
        validated.fileName(),
        validated.contentType().contentType(),
        validated.sizeBytes(),
        validated.checksumSha256(),
        createdBy);
    // Append the request event in the SAME transaction as the row: neither commits without the
    // other (invariant 3, ADR-004). The payload is correlation metadata (opaque ids), never a
    // secret/prompt/document. The document is the whole correlation chain for a request event, so
    // correlationId = documentId and causationId is null (there is no predecessor event). The
    // tenant
    // is scoped through outbox_events.tenant_id (a real FK), never through these headers (invariant
    // 1).
    OutboxEvent event =
        OutboxEvent.pending(
            documentId,
            documentId,
            "document.ingestion-requested.v1",
            "agentflow.document.ingestion-requested.v1",
            "document",
            documentId,
            tenantId,
            payloadJson(documentId, knowledgeBaseId),
            CorrelationHeaders.of(documentId.toString(), null, null).toJson());
    outboxService.enqueue(event);
    return new DocumentDto(documentId, knowledgeBaseId, DocumentUploadStatus.UPLOADED);
  }

  /**
   * @param repository the {@code documents} writer, or {@code null} when no database is configured
   * @return the repository, or a {@code 503} when no database connection is configured (S01-02)
   */
  private <T> T requireRepository(T repository) {
    if (repository == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "uploading a document is unavailable: no database connection is configured");
    }
    return repository;
  }

  /**
   * The envelope payload for the {@code ingestion-requested} event: correlation metadata only
   * (opaque identifiers, never a secret, a full prompt or a full document, invariant 8). The values
   * are {@code UUID} ids, so a plain string build is valid JSON and needs no serializer.
   *
   * @param documentId the document id the event describes
   * @param knowledgeBaseId the parent collection the document was uploaded into
   * @return the JSON envelope payload
   */
  private static String payloadJson(UUID documentId, UUID knowledgeBaseId) {
    return "{\"documentId\":\""
        + documentId
        + "\",\"knowledgeBaseId\":\""
        + knowledgeBaseId
        + "\"}";
  }
}
