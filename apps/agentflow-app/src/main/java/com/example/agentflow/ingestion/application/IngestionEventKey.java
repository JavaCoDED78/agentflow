package com.example.agentflow.ingestion.application;

import java.util.UUID;

/**
 * The identity of an ingestion event the indexing worker drives (S10-13): the tenant/document pair
 * the {@code document.ingestion-requested.v1} payload carries.
 *
 * <p>The tenant is <b>correlation metadata re-checked against PostgreSQL</b>, never an
 * authorization source (invariant 1): the document is read by the {@code (tenant_id, document_id)}
 * pair, so a forged or cross-tenant key is indistinguishable from a missing document.
 *
 * @param tenantId the owning tenant (from the stored event, re-checked against the document row)
 * @param documentId the document to index
 */
public record IngestionEventKey(UUID tenantId, UUID documentId) {

  /**
   * Canonical constructor.
   *
   * @throws IllegalArgumentException if either id is {@code null}
   */
  public IngestionEventKey {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must not be null");
    }
    if (documentId == null) {
      throw new IllegalArgumentException("documentId must not be null");
    }
  }
}
