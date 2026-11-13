package com.example.agentflow.knowledgebase.persistence;

import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
import java.util.UUID;

/**
 * The minimal tenant-scoped projection of a {@code documents} row the duplicate-upload and manual
 * retry flows of {@code S10-19} need: the identity, the parent collection and the lifecycle state.
 *
 * <p>It deliberately omits the object-storage locator, the fingerprint and the embedding model the
 * indexing path ({@code DocumentView}) carries — the duplicate lookup is keyed on {@code
 * (tenant_id, sha256)} and the retry on {@code (tenant_id, id)}, so neither needs the parent
 * knowledge-base join. It lives in {@code persistence} (the mapping side); {@code knowledgebase}
 * application services build the public {@link
 * com.example.agentflow.knowledgebase.application.DocumentDto} from it, never the other way round.
 *
 * @param documentId the document id
 * @param knowledgeBaseId the parent collection
 * @param status the lifecycle state (the canonical {@link DocumentUploadStatus} name)
 */
public record DocumentLookupRow(
    UUID documentId, UUID knowledgeBaseId, DocumentUploadStatus status) {

  /**
   * @return the document id the caller uses to follow the lifecycle or to address the object key
   */
  public UUID documentId() {
    return documentId;
  }

  /**
   * @return the parent collection the document was uploaded into
   */
  public UUID knowledgeBaseId() {
    return knowledgeBaseId;
  }

  /**
   * @return the current lifecycle state (the lookup returns the persisted name)
   */
  public DocumentUploadStatus status() {
    return status;
  }
}
