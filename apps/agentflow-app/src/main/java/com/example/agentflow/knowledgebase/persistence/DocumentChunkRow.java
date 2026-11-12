package com.example.agentflow.knowledgebase.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A persisted {@code document_chunks} row as read by the tenant-scoped nearest-neighbour retrieval
 * query (S10-11 / S10-14).
 *
 * <p>This is a <em>persistence</em> projection, not the tenant-free {@code
 * ingestion.domain.DocumentChunk} value (that is the chunker's pure output); retrieval needs the
 * full (tenant, document, chunk) binding the glossary §7 fixes for a citation, plus the fragment
 * text and page range. It carries no embedding: the query orders by distance to the (in-memory)
 * query vector, so the returned rows already arrive relevance-ordered and the caller never reads
 * the stored vector back (the plain PostgreSQL driver cannot map a {@code vector} column to {@code
 * float[]} without pgvector-jdbc, which this task does not add).
 *
 * <p>Immutable value: all fields are {@code final}, equality covers every field, and it is created
 * only by the repository {@code RowMapper}. Another module reaches it only through the {@code
 * knowledgebase} application boundary; the {@code persistence} sub-package is internal.
 */
public record DocumentChunkRow(
    UUID id,
    UUID tenantId,
    UUID documentId,
    String pipelineVersion,
    int chunkIndex,
    int pageFrom,
    int pageTo,
    String content,
    String contentHash,
    String createdBy,
    OffsetDateTime createdAt) {

  /**
   * @param id the chunk id (the persisted {@code document_chunks.id})
   * @param tenantId the owning tenant (FK {@code tenants.id}); the isolation key on retrieval
   * @param documentId the parent document (FK {@code documents.id})
   * @param pipelineVersion the chunking pipeline version that produced the chunk
   * @param chunkIndex the zero-based position within the document's chunk set
   * @param pageFrom the first page the fragment spans (>= 1)
   * @param pageTo the last page the fragment spans (>= {@code pageFrom})
   * @param content the fragment text
   * @param contentHash the SHA-256 hex fingerprint of {@code content}
   * @param createdBy the actor recorded for the audit trail, or {@code null} for a system actor
   * @param createdAt the immutable creation instant
   */
}
