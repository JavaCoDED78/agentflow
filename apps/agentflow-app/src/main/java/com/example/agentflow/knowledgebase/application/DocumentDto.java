package com.example.agentflow.knowledgebase.application;

import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
import java.util.UUID;

/**
 * The API view of an uploaded document returned by the {@link DocumentService} upload flow.
 *
 * <p>An API DTO, not a persistence object (the {@code documents} row is mapped by {@code
 * knowledgebase.persistence}). It carries only the identity the caller needs to follow the
 * lifecycle on: the server-issued {@code documentId}, the parent {@code knowledgeBaseId} and the
 * current {@link DocumentUploadStatus}. The object bytes live in object storage under the {@code
 * object_key}; they are never part of this view.
 */
public record DocumentDto(UUID documentId, UUID knowledgeBaseId, DocumentUploadStatus status) {

  /**
   * @return the server-issued id the caller uses to read status or to address the object key
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
   * @return the current lifecycle state (the upload returns {@link DocumentUploadStatus#UPLOADED};
   *     the enqueue flow's outbox event moves it to {@link DocumentUploadStatus#QUEUED})
   */
  public DocumentUploadStatus status() {
    return status;
  }
}
