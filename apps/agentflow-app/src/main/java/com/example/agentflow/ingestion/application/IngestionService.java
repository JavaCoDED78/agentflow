package com.example.agentflow.ingestion.application;

import com.example.agentflow.ingestion.application.chunk.Chunker;
import com.example.agentflow.ingestion.application.embed.EmbeddingBatchResult;
import com.example.agentflow.ingestion.application.embed.EmbeddingGenerationService;
import com.example.agentflow.ingestion.domain.DocumentChunk;
import com.example.agentflow.ingestion.domain.ExtractionResult;
import com.example.agentflow.ingestion.domain.RedeliveryPolicy;
import com.example.agentflow.knowledgebase.application.ChunkIndexingResult;
import com.example.agentflow.knowledgebase.application.ChunkIndexingService;
import com.example.agentflow.knowledgebase.application.ChunkToIndex;
import com.example.agentflow.knowledgebase.application.DocumentPayloadStore;
import com.example.agentflow.knowledgebase.application.DocumentQueryService;
import com.example.agentflow.knowledgebase.application.DocumentTransitionService;
import com.example.agentflow.knowledgebase.application.DocumentView;
import com.example.agentflow.messaging.application.CorrelationHeaders;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The indexing use case of the ingestion pipeline (S10-13): one {@code ingestion-requested} event
 * driven through extract &rarr; chunk &rarr; embed &rarr; index, idempotently.
 *
 * <p><b>Where the index lives.</b> Everything persisted is owned by {@code knowledgebase} and
 * reached only through its public application boundary ({@link DocumentQueryService}, {@link
 * DocumentTransitionService}, {@link ChunkIndexingService}); this module owns the pipeline
 * semantics (order, batching, validation, policy), never the tables. The index is bound to the
 * document's content fingerprint and pipeline version: the chunk rows are keyed by {@code
 * (document_id, chunk_index, pipeline_version)} and the document carries {@code sha256} and {@code
 * pipeline_version} — the idempotency key the plan fixes ({@code tenant_id + sha256 +
 * pipeline_version}, plan §8.2).
 *
 * <p><b>Idempotent redelivery.</b> Delivery is at-least-once ({@code ADR-003}), so the same event
 * can arrive twice. Three guards make a repeat a no-op rather than a duplicate (the S10-13
 * acceptance criterion, "повтор одного ingestion event не создаёт дубли logical chunks"):
 *
 * <ol>
 *   <li>the <b>inbox claim</b> (the caller's {@code InboxDedupService}) dedups by event id in the
 *       same transaction as this pass;
 *   <li>the <b>conditional claim</b> ({@link DocumentTransitionService#claimForIndexing}) lets
 *       exactly one worker move the document out of a claimable state — an already-indexed document
 *       under the {@link RedeliveryPolicy#SKIP} policy is never re-claimed, and a concurrent
 *       duplicate finds zero rows and skips;
 *   <li>the <b>upsert</b> ({@link ChunkIndexingService#index}) writes each chunk against the {@code
 *       (document_id, chunk_index, pipeline_version)} unique key — {@code DO NOTHING} under {@code
 *       SKIP}, {@code DO UPDATE} under {@code UPDATE} — so even a re-driven pass can never create a
 *       second logical chunk.
 * </ol>
 *
 * <p><b>Transaction shape (the recorded trade-off).</b> The whole pass runs inside the caller's
 * inbox transaction: the event claim, the document claim, the chunk writes, the {@code PROCESSING
 * -> INDEXED} transition and the {@code document.indexed} outbox record commit or roll back
 * <em>together</em> (invariant 3). That means the embedding call happens inside a database
 * transaction — a deliberate choice for the low-frequency ingestion path: it buys atomicity with
 * the already-tested {@code InboxDedupService} (a crash anywhere rolls back to a clean, re-drivable
 * state; the savepoint retry re-runs the pass safely because every step is idempotent), at the cost
 * of holding the transaction across a bounded provider call (bounded by the {@code ai} resilience
 * layer's timeouts). Splitting extract/embed out of the transaction is the documented follow-up (it
 * needs the document lease columns {@code document-lifecycle.md} describes).
 *
 * <p><b>Failure policy.</b> A permanent failure (a corrupt/encrypted payload, an empty extraction
 * is <em>not</em> one — it completes with zero chunks, an embedding-dimensionality mismatch) is
 * recorded on the document ({@code FAILED} + the safe S10-08 error code) and returned as a normal
 * outcome — the event is durably handled, never retried. A transient fault (a provider or storage
 * fault) propagates to the caller's error policy, which retries it bounded and then holds the
 * event; the worker then releases the document back to {@code QUEUED} (the lifecycle's retry edge).
 * A mismatched embedding width is forbidden explicitly: the pass fails the document with {@code
 * EMBEDDING_DIMENSION_MISMATCH} rather than letting a wrong-width vector near the {@code
 * vector(768)} column.
 *
 * <p>The payload bytes and the extracted text are untrusted content (invariant 7): they are never
 * logged, never put into an event payload and never persisted beyond the chunk rows. The service
 * holds no Spring annotations, no clock and no randomness of its own (the chunk row ids and the
 * event id are the only fresh UUIDs), so every outcome is reproducible in a DB-free unit test.
 */
public final class IngestionService {

  /** The indexed-event payload status, matching the {@code document.indexed.v1} contract. */
  private static final String STATUS_INDEXED = "INDEXED";

  /** The document status recorded when the pass ends in the terminal indexed state. */
  private static final String STATUS_INDEXED_RESULT = "INDEXED";

  /** The document status recorded when the pass ends in the terminal failed state. */
  private static final String STATUS_FAILED_RESULT = "FAILED";

  /** The safe, machine-readable failure code for an embedding-dimensionality mismatch. */
  private static final String ERROR_DIMENSION_MISMATCH = "EMBEDDING_DIMENSION_MISMATCH";

  private final DocumentQueryService documentQueryService;
  private final DocumentTransitionService documentTransitionService;
  private final ChunkIndexingService chunkIndexingService;
  private final DocumentPayloadStore payloadStore;
  private final DocumentParser documentParser;
  private final Chunker chunker;
  private final EmbeddingGenerationService embeddingGenerationService;
  private final OutboxService outboxService;
  private final RedeliveryPolicy redeliveryPolicy;

  /**
   * @param documentQueryService the tenant-scoped document read path ({@code knowledgebase})
   * @param documentTransitionService the single writer of {@code documents.status} ({@code
   *     knowledgebase})
   * @param chunkIndexingService the idempotent chunk write path ({@code knowledgebase})
   * @param payloadStore the object-storage read port; {@code null} when no Garage client is wired
   *     (opt-in, {@code ADR-014}) — {@link #index} then fails loudly at call time instead of the
   *     application failing to boot (S01-02, the {@code PlannerService} idiom)
   * @param documentParser the extraction port (Tika/PDFBox)
   * @param chunker the deterministic chunker
   * @param embeddingGenerationService the batching embed step (S10-12)
   * @param outboxService the transactional-outbox appender for the {@code document.indexed} event
   * @param redeliveryPolicy what a redelivered event does with an already-indexed document
   */
  public IngestionService(
      DocumentQueryService documentQueryService,
      DocumentTransitionService documentTransitionService,
      ChunkIndexingService chunkIndexingService,
      DocumentPayloadStore payloadStore,
      DocumentParser documentParser,
      Chunker chunker,
      EmbeddingGenerationService embeddingGenerationService,
      OutboxService outboxService,
      RedeliveryPolicy redeliveryPolicy) {
    this.documentQueryService = documentQueryService;
    this.documentTransitionService = documentTransitionService;
    this.chunkIndexingService = chunkIndexingService;
    this.payloadStore = payloadStore;
    this.documentParser = documentParser;
    this.chunker = chunker;
    this.embeddingGenerationService = embeddingGenerationService;
    this.outboxService = outboxService;
    this.redeliveryPolicy = redeliveryPolicy;
  }

  /**
   * Drive one ingestion event through the whole pipeline. Runs inside the caller's transaction (the
   * worker's inbox transaction — see the class javadoc for the trade-off).
   *
   * @param key the tenant/document pair the event carries
   * @param ingestionEventId the id of the consumed {@code ingestion-requested} event, recorded as
   *     the {@code indexed} event's {@code causationId}
   * @return the pass outcome: the document's lifecycle state after the pass
   * @throws IllegalArgumentException when the event references no tenant-owned document (a
   *     permanent, malformed-event fault — the inbox policy dead-letters it)
   * @throws IllegalStateException when no {@link DocumentPayloadStore} is configured (a permanent
   *     deployment fault)
   * @throws RuntimeException a transient provider/storage fault, propagated to the caller's error
   *     policy (bounded retry, then hold); nothing is marked processed on this path
   */
  public IngestionStatusDto index(IngestionEventKey key, UUID ingestionEventId) {
    DocumentView document =
        documentQueryService
            .findForIndexing(key.tenantId(), key.documentId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "no tenant-owned document "
                            + key.documentId()
                            + " for the tenant of the ingestion event"));

    if (!documentTransitionService.claimForIndexing(
        key.tenantId(), key.documentId(), redeliveryPolicy.updatesOnConflict())) {
      // Not claimable: already indexed (SKIP policy), claimed by another worker, or a terminal
      // state. A no-op, never a second transition — the redelivery is absorbed here.
      return new IngestionStatusDto(
          document.documentId(), document.status(), document.pipelineVersion());
    }

    if (payloadStore == null) {
      throw new IllegalStateException(
          "no DocumentPayloadStore is configured: the object-storage client is opt-in"
              + " (ADR-014), so indexing requires a storage profile");
    }
    byte[] payload = payloadStore.fetch(key.tenantId(), key.documentId());

    ExtractionResult extraction =
        documentParser.parse(document.fileName(), document.mimeType(), payload);
    if (extraction.isFailure()) {
      return fail(
          key,
          document,
          "EXTRACTION_" + extraction.error().code().name(),
          extraction.error().summary());
    }
    if (extraction.isEmpty()) {
      // An empty payload is not an error (S10-07): the document completes with zero chunks — it
      // is indexed, and retrieval simply finds nothing in it.
      return complete(key, document, ingestionEventId, extraction.pageCount(), 0);
    }

    List<DocumentChunk> chunks = chunker.chunk(extraction);
    if (chunks.isEmpty()) {
      return complete(key, document, ingestionEventId, extraction.pageCount(), 0);
    }

    EmbeddingBatchResult embeddings =
        embeddingGenerationService.embed(
            document.embeddingModel(), chunks.stream().map(DocumentChunk::content).toList());
    if (embeddings.dimensions() != ChunkIndexingService.EMBEDDING_DIMENSIONS) {
      return fail(
          key,
          document,
          ERROR_DIMENSION_MISMATCH,
          "embedding width "
              + embeddings.dimensions()
              + " does not match the pinned "
              + ChunkIndexingService.EMBEDDING_DIMENSIONS
              + "-dimension index (nomic-embed-text)");
    }

    List<ChunkToIndex> toIndex = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      DocumentChunk chunk = chunks.get(i);
      toIndex.add(
          new ChunkToIndex(
              chunk.index(),
              chunk.pageFrom(),
              chunk.pageTo(),
              chunk.content(),
              chunk.contentHash(),
              embeddings.vectors().get(i)));
    }
    ChunkIndexingResult indexed =
        chunkIndexingService.index(
            key.tenantId(),
            key.documentId(),
            chunker.pipelineVersion(),
            toIndex,
            redeliveryPolicy.updatesOnConflict(),
            null);
    return complete(key, document, ingestionEventId, extraction.pageCount(), indexed.written());
  }

  /**
   * Record a permanent failure on the document (S10-08 safe code + redacted summary) and return the
   * failed outcome. The failure is a normal, durable outcome: the event is handled, not retried.
   */
  private IngestionStatusDto fail(
      IngestionEventKey key, DocumentView document, String errorCode, String errorSummary) {
    documentTransitionService.markFailed(key.tenantId(), key.documentId(), errorCode, errorSummary);
    return new IngestionStatusDto(
        document.documentId(), STATUS_FAILED_RESULT, document.pipelineVersion());
  }

  /**
   * Complete the pass: mark the document {@code INDEXED} (recording the extracted page count, the
   * S10-08 diagnostic) and enqueue the {@code document.indexed.v1} event in the same transaction
   * (invariant 3 — the outbox record commits with the state change).
   */
  private IngestionStatusDto complete(
      IngestionEventKey key,
      DocumentView document,
      UUID ingestionEventId,
      int pageCount,
      int chunkCount) {
    documentTransitionService.markIndexed(key.tenantId(), key.documentId(), pageCount);
    UUID eventId = UUID.randomUUID();
    outboxService.enqueue(
        OutboxEvent.pending(
            eventId,
            eventId,
            "document.indexed.v1",
            "agentflow.document.indexed.v1",
            "document",
            document.documentId(),
            key.tenantId(),
            indexedPayloadJson(document, chunkCount),
            CorrelationHeaders.of(
                    document.documentId().toString(), ingestionEventId.toString(), null)
                .toJson()));
    return new IngestionStatusDto(
        document.documentId(), STATUS_INDEXED_RESULT, document.pipelineVersion());
  }

  /**
   * The envelope payload for the {@code indexed} event: correlation metadata only (opaque ids, the
   * status, the pipeline version and the chunk count — never a secret, a prompt or a document
   * fragment, invariant 8). The values are UUIDs, numbers and fixed tokens, so a plain string build
   * is valid JSON and needs no serializer.
   */
  private static String indexedPayloadJson(DocumentView document, int chunkCount) {
    return "{\"documentId\":\""
        + document.documentId()
        + "\",\"knowledgeBaseId\":\""
        + document.knowledgeBaseId()
        + "\",\"status\":\""
        + STATUS_INDEXED
        + "\",\"pipelineVersion\":\""
        + document.pipelineVersion()
        + "\",\"chunkCount\":"
        + chunkCount
        + "}";
  }
}
