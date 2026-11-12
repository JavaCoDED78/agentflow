package com.example.agentflow.knowledgebase.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A persisted {@code document_chunks} row as read by the tenant/ACL-scoped retrieval queries
 * (S10-11 / S10-14 / S10-15).
 *
 * <p>This is a <em>persistence</em> projection, not the tenant-free {@code
 * ingestion.domain.DocumentChunk} value (that is the chunker's pure output); retrieval needs the
 * full (tenant, document, chunk) binding the glossary §7 fixes for a citation, plus the fragment
 * text, the page range and the relevance the row was found at. It carries no embedding: the queries
 * order by their own score (the cosine distance to the in-memory query vector, or the FTS
 * relevance) and select that score as a plain column, so the caller never reads the stored {@code
 * vector} back (the plain PostgreSQL driver cannot map a {@code vector} column to {@code float[]}
 * without pgvector-jdbc, which this task does not add).
 *
 * <p>Two relevance concepts share the record so the two retrieval halves do not need two records:
 * the nearest-neighbour query (S10-14) fills {@link #distance()} with the cosine distance and
 * leaves {@link #rank()} at {@code 0}; the lexical full-text search (S10-15) fills {@link #rank()}
 * with the FTS relevance and leaves {@link #distance()} at {@code 0}. A {@code DocumentChunkRow}
 * from one half never mixes the two scores.
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
    OffsetDateTime createdAt,
    double distance,
    double rank) {

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
   * @param distance the cosine distance ({@code embedding <=> query}) the row was returned at;
   *     {@code 0} = identical to the query vector, lower is closer; the retrieval service compares
   *     it against the configured relevance threshold — {@code 0} for a lexical (FTS) row
   * @param rank the FTS relevance ({@code ts_rank}) the row was returned at by the lexical
   *     full-text search (S10-15); {@code 0} for a nearest-neighbour (vector) row
   */
}
