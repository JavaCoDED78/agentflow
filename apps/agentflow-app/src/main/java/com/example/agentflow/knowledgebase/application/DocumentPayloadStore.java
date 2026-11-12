package com.example.agentflow.knowledgebase.application;

import java.util.UUID;

/**
 * The outbound port for reading an uploaded document's payload bytes from object storage (S10-02's
 * Garage, S3-compatible), exposed through the {@code knowledgebase} public application boundary for
 * the {@code ingestion} indexing worker (S10-13).
 *
 * <p>The worker depends on this port, never on a concrete S3 client: the Garage client (the
 * module's internal object-storage adapter, wired from {@code agentflow.object-storage.*}) is the
 * only code that knows the SDK. The port is <b>opt-in</b> ({@code ADR-014} idiom): until the Garage
 * client lands there is no bean, and the indexing pass fails loudly at call time instead of the
 * application failing to boot (S01-02) — exactly like the {@code EmbeddingPort} seam.
 *
 * <p>Security (invariant 1): an implementation must resolve the payload through the {@code
 * documents} row read by the {@code (tenant_id, document_id)} pair — the tenant is re-checked
 * against PostgreSQL (the source of truth), never taken from the caller as authorization — and then
 * read the object under the server-generated {@code object_key}. Another tenant's document is
 * indistinguishable from a missing one. The bytes returned are untrusted content (invariant 7):
 * they have passed upload validation (S10-03) but are never logged, never put into a prompt
 * verbatim and never persisted outside the chunk pipeline.
 */
public interface DocumentPayloadStore {

  /**
   * Fetch the payload bytes of a tenant-owned document.
   *
   * @param tenantId the owning tenant (re-checked against the stored document row)
   * @param documentId the document id
   * @return the raw payload bytes (never {@code null}; an empty array yields an empty extraction)
   * @throws IllegalArgumentException when no such tenant-owned document exists (a missing and a
   *     cross-tenant id are indistinguishable)
   * @throws RuntimeException a storage fault (a connection/timeout fault is transient and
   *     retryable; a missing object is permanent) — propagated to the caller's error policy
   */
  byte[] fetch(UUID tenantId, UUID documentId);
}
