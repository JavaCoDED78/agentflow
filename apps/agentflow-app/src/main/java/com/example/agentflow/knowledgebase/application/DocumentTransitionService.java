package com.example.agentflow.knowledgebase.application;

import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
import com.example.agentflow.knowledgebase.persistence.DocumentRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The single writer of {@code documents.status} for the ingestion worker (S10-13) — the document
 * mirror of the run transition service (S05-11): a controller or Kafka listener never sets the
 * status directly, every working transition is a conditional update guarded by the current status
 * and the optimistic {@code version} counter (invariant 4, {@code document-lifecycle.md} §4).
 *
 * <p>The vocabulary and the guards live here, not in the caller: the worker asks to {@link
 * #claimForIndexing} (with or without reindex), {@link #markIndexed}, {@link #markFailed} or {@link
 * #releaseToQueued}, and this service maps the request onto the allowed from-states. A transition
 * that finds the document in a different state affects zero rows and reports {@code false}, so a
 * duplicate or concurrent delivery is a no-op rather than a second transition.
 *
 * <p>The repository is optional (constructor-parameter {@code @Autowired(required = false)}, the
 * {@code DocumentService} idiom), so the application still boots without a database (S01-02); a
 * transition then fails loudly at call time with {@link IllegalStateException} — a deployment
 * fault, classified permanent by a consumer's error policy.
 */
@Service
public class DocumentTransitionService {

  /**
   * The states a fresh indexing pass may claim a document from: the upload committed ({@code
   * UPLOADED} — the {@code ingestion-requested} event proves it) and the queued await ({@code
   * QUEUED}).
   */
  private static final Set<DocumentUploadStatus> FRESH_CLAIMABLE =
      Set.of(DocumentUploadStatus.UPLOADED, DocumentUploadStatus.QUEUED);

  /**
   * The states a reindex pass may claim from: the fresh set plus {@code INDEXED} (a redelivery
   * under the {@code UPDATE} policy re-embeds an already-indexed document in place).
   */
  private static final Set<DocumentUploadStatus> REINDEX_CLAIMABLE =
      Set.of(
          DocumentUploadStatus.UPLOADED, DocumentUploadStatus.QUEUED, DocumentUploadStatus.INDEXED);

  private final DocumentRepository documentRepository;

  /**
   * @param documentRepository the tenant-scoped {@code documents} write path, or {@code null} when
   *     no database is configured (S01-02)
   */
  @Autowired
  public DocumentTransitionService(
      @Autowired(required = false) DocumentRepository documentRepository) {
    this.documentRepository = documentRepository;
  }

  /**
   * Atomically claim a document for indexing ({@code worker.claim}): a conditional update out of
   * the claimable states, so exactly one worker wins a concurrent race.
   *
   * @param tenantId the owning tenant
   * @param documentId the document id
   * @param allowReindex whether an already-{@code INDEXED} document may be claimed again (the
   *     redelivery {@code UPDATE} policy); {@code false} claims only from {@code UPLOADED}/{@code
   *     QUEUED}
   * @return {@code true} when this call claimed the document; {@code false} when it is in another
   *     state (already indexed, processing elsewhere, or terminal) and the pass must skip it
   * @throws IllegalStateException when no database connection is configured (S01-02)
   */
  public boolean claimForIndexing(UUID tenantId, UUID documentId, boolean allowReindex) {
    requireRepository();
    return documentRepository.claimForIndexing(
        tenantId, documentId, allowReindex ? REINDEX_CLAIMABLE : FRESH_CLAIMABLE);
  }

  /**
   * Complete a claimed document: {@code PROCESSING -> INDEXED}, recording the extracted page count
   * (the S10-08 diagnostic) when known.
   *
   * @param tenantId the owning tenant
   * @param documentId the document id
   * @param pageCount the extracted page count, or {@code null} to leave the column untouched
   * @return {@code true} when the transition applied; {@code false} when the document was no longer
   *     claimed (a no-op, never a second transition)
   * @throws IllegalStateException when no database connection is configured (S01-02)
   */
  public boolean markIndexed(UUID tenantId, UUID documentId, Integer pageCount) {
    requireRepository();
    return documentRepository.markIndexed(tenantId, documentId, pageCount);
  }

  /**
   * Fail a claimed document: {@code PROCESSING -> FAILED} with the safe, machine-readable error
   * code and redacted summary (S10-08).
   *
   * @param tenantId the owning tenant
   * @param documentId the document id
   * @param errorCode the safe machine-readable failure token (never {@code null} or blank)
   * @param errorSummary the short redacted failure summary (never {@code null})
   * @return {@code true} when the transition applied; {@code false} when the document was no longer
   *     claimed
   * @throws IllegalStateException when no database connection is configured (S01-02)
   */
  public boolean markFailed(UUID tenantId, UUID documentId, String errorCode, String errorSummary) {
    requireRepository();
    return documentRepository.markFailed(tenantId, documentId, errorCode, errorSummary);
  }

  /**
   * Release a claimed document back to {@code QUEUED} after a transient failure (the retry edge of
   * {@code document-lifecycle.md}), so a later delivery re-drives it.
   *
   * @param tenantId the owning tenant
   * @param documentId the document id
   * @return {@code true} when the release applied; {@code false} when the document was no longer
   *     claimed
   * @throws IllegalStateException when no database connection is configured (S01-02)
   */
  public boolean releaseToQueued(UUID tenantId, UUID documentId) {
    requireRepository();
    return documentRepository.releaseToQueued(tenantId, documentId);
  }

  private void requireRepository() {
    if (documentRepository == null) {
      throw new IllegalStateException(
          "transitioning a document is unavailable: no database connection is configured (S01-02)");
    }
  }
}
