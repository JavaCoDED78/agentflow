package com.example.agentflow.knowledgebase.application;

import com.example.agentflow.knowledgebase.persistence.DocumentChunkRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The idempotent chunk write path of the {@code document_chunks} table (S10-13), exposed through
 * the {@code knowledgebase} public application boundary for the {@code ingestion} indexing worker.
 *
 * <p>Every write is an upsert against the {@code (document_id, chunk_index, pipeline_version)}
 * unique key {@code S10-10} declared, driven by the caller's redelivery policy:
 *
 * <ul>
 *   <li><b>skip</b> ({@code updateOnConflict = false}) — {@code ON CONFLICT DO NOTHING}: a
 *       redelivered pass leaves an already-indexed chunk untouched and reports it as skipped, so
 *       repeating one ingestion event never creates a duplicate logical chunk (the S10-13
 *       acceptance criterion) — the database guard, not a post-check.
 *   <li><b>update</b> ({@code updateOnConflict = true}) — {@code ON CONFLICT DO UPDATE}: a reindex
 *       refreshes the fragment and its vector in place, keyed identically, so the row set stays
 *       exactly one row per (position, pipeline version).
 * </ul>
 *
 * <p>The embedding width is pinned here: {@link #EMBEDDING_DIMENSIONS} is the single application
 * constant for the {@code vector(768)} column {@code S10-11} added (the {@code nomic-embed-text}
 * space), and every vector is validated against it before a row is written — a mismatched width
 * fails the pass loudly, never landing in a silently wrong index. The column itself rejects any
 * other width at the database layer as the second guard.
 *
 * <p>The repository is optional (constructor-parameter {@code @Autowired(required = false)}, the
 * {@code DocumentService} idiom), so the application still boots without a database (S01-02); an
 * indexing call then fails loudly at call time with {@link IllegalStateException}.
 */
@Service
public class ChunkIndexingService {

  /**
   * The pinned embedding width of {@code document_chunks.embedding} ({@code vector(768)}, {@code
   * nomic-embed-text}, S10-11): the single application-side constant for the column's
   * dimensionality.
   */
  public static final int EMBEDDING_DIMENSIONS = 768;

  private final DocumentChunkRepository chunkRepository;

  /**
   * @param chunkRepository the tenant-scoped {@code document_chunks} write path, or {@code null}
   *     when no database is configured (S01-02)
   */
  @Autowired
  public ChunkIndexingService(
      @Autowired(required = false) DocumentChunkRepository chunkRepository) {
    this.chunkRepository = chunkRepository;
  }

  /**
   * Persist one pass of a document's chunk set idempotently.
   *
   * <p>The whole pass is one caller-managed transaction (the worker runs it inside the inbox
   * transaction, invariant 3): either every chunk of the pass lands or none does.
   *
   * @param tenantId the owning tenant
   * @param documentId the parent document
   * @param pipelineVersion the pipeline version the chunks were produced with (part of the unique
   *     key)
   * @param chunks the chunk set in index order (never {@code null} or empty)
   * @param updateOnConflict {@code false} for the redelivery {@code SKIP} policy (existing rows are
   *     left untouched and counted as skipped); {@code true} for the {@code UPDATE} policy
   *     (existing rows are overwritten in place)
   * @param createdBy the actor recorded for the audit trail, or {@code null} for a system actor
   * @return the write counts (rows written / rows skipped)
   * @throws IllegalStateException when a vector's width differs from {@link #EMBEDDING_DIMENSIONS},
   *     when no database connection is configured (S01-02), or when the chunk set is empty
   */
  public ChunkIndexingResult index(
      UUID tenantId,
      UUID documentId,
      String pipelineVersion,
      List<ChunkToIndex> chunks,
      boolean updateOnConflict,
      String createdBy) {
    if (chunkRepository == null) {
      throw new IllegalStateException(
          "indexing chunks is unavailable: no database connection is configured (S01-02)");
    }
    if (chunks == null || chunks.isEmpty()) {
      throw new IllegalStateException("chunk set must not be null or empty");
    }
    for (ChunkToIndex chunk : chunks) {
      if (chunk.embedding().length != EMBEDDING_DIMENSIONS) {
        throw new IllegalStateException(
            "embedding must be "
                + EMBEDDING_DIMENSIONS
                + " dimensions (nomic-embed-text), was "
                + chunk.embedding().length);
      }
    }
    int written = 0;
    int skipped = 0;
    for (ChunkToIndex chunk : chunks) {
      if (updateOnConflict) {
        chunkRepository.upsertUpdating(
            UUID.randomUUID(),
            tenantId,
            documentId,
            pipelineVersion,
            chunk.chunkIndex(),
            chunk.pageFrom(),
            chunk.pageTo(),
            chunk.content(),
            chunk.contentHash(),
            chunk.embedding(),
            createdBy);
        written += 1;
      } else {
        boolean inserted =
            chunkRepository.insertSkippingConflicts(
                UUID.randomUUID(),
                tenantId,
                documentId,
                pipelineVersion,
                chunk.chunkIndex(),
                chunk.pageFrom(),
                chunk.pageTo(),
                chunk.content(),
                chunk.contentHash(),
                chunk.embedding(),
                createdBy);
        if (inserted) {
          written += 1;
        } else {
          skipped += 1;
        }
      }
    }
    return new ChunkIndexingResult(written, skipped);
  }
}
