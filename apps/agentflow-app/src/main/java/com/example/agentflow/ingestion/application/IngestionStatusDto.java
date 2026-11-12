package com.example.agentflow.ingestion.application;

import java.util.UUID;

/**
 * The outcome of one indexing pass (S10-13): what happened to the document the pass drove.
 *
 * <p>{@code status} is the document's lifecycle state after the pass (see {@code
 * docs/architecture/document-lifecycle.md}): {@code INDEXED} when the pass completed the index,
 * {@code FAILED} when it recorded a permanent failure (the safe error code lives on the {@code
 * documents.error_code} column), or the unchanged current state when the pass was a no-op (an
 * already-indexed document under the {@code SKIP} policy, a document claimed by another worker, a
 * terminal state).
 *
 * @param documentId the document the pass drove
 * @param status the document lifecycle state after the pass (see above)
 * @param pipelineVersion the document's pipeline version
 */
public record IngestionStatusDto(UUID documentId, String status, String pipelineVersion) {

  /**
   * Canonical constructor.
   *
   * @throws IllegalArgumentException if any field is {@code null} or a text field is blank
   */
  public IngestionStatusDto {
    if (documentId == null) {
      throw new IllegalArgumentException("documentId must not be null");
    }
    if (status == null || status.isBlank()) {
      throw new IllegalArgumentException("status must not be null or blank");
    }
    if (pipelineVersion == null || pipelineVersion.isBlank()) {
      throw new IllegalArgumentException("pipelineVersion must not be null or blank");
    }
  }
}
