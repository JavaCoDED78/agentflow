package com.example.agentflow.knowledgebase.application;

import com.example.agentflow.knowledgebase.persistence.DocumentIndexRow;
import com.example.agentflow.knowledgebase.persistence.DocumentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The tenant-scoped document read path (S10-13), exposed through the {@code knowledgebase} public
 * application boundary for the {@code ingestion} worker.
 *
 * <p>Every read is by the {@code (tenant_id, id)} pair, with the tenant predicate inside the query
 * itself (invariant 1): another tenant's document is indistinguishable from a missing one, so a
 * cross-tenant or forged id yields {@link Optional#empty()} — a UUID is not authorization. The
 * worker passes the tenant of the stored event, which this lookup re-checks against PostgreSQL (the
 * source of truth, invariant 2).
 *
 * <p>The repository is optional (constructor-parameter {@code @Autowired(required = false)}, the
 * {@code DocumentService} idiom), so the application still boots without a database (S01-02); the
 * read then fails loudly at call time with {@link IllegalStateException} — a deployment fault that
 * no retry fixes, which a consumer's error policy classifies as permanent.
 */
@Service
public class DocumentQueryService {

  private final DocumentRepository documentRepository;

  /**
   * @param documentRepository the tenant-scoped {@code documents} read path, or {@code null} when
   *     no database is configured (S01-02)
   */
  @Autowired
  public DocumentQueryService(@Autowired(required = false) DocumentRepository documentRepository) {
    this.documentRepository = documentRepository;
  }

  /**
   * Read one document for indexing, with the embedding model of its parent knowledge base.
   *
   * @param tenantId the owning tenant (from the stored event, re-checked against PostgreSQL)
   * @param documentId the document id
   * @return the document read model, or empty when no such tenant-owned document exists
   * @throws IllegalStateException when no database connection is configured (S01-02)
   */
  public Optional<DocumentView> findForIndexing(UUID tenantId, UUID documentId) {
    if (documentRepository == null) {
      throw new IllegalStateException(
          "reading a document is unavailable: no database connection is configured (S01-02)");
    }
    return documentRepository.findIndexRowByIdAndTenant(tenantId, documentId).map(this::toView);
  }

  /**
   * @param row the persistence projection (internal)
   * @return the public read model; the status travels as its canonical name so a consumer module
   *     never imports this module's internal {@code domain} package
   */
  private DocumentView toView(DocumentIndexRow row) {
    return new DocumentView(
        row.documentId(),
        row.tenantId(),
        row.knowledgeBaseId(),
        row.objectKey(),
        row.fileName(),
        row.mimeType(),
        row.sizeBytes(),
        row.sha256(),
        row.status().name(),
        row.pipelineVersion(),
        row.embeddingModel());
  }
}
