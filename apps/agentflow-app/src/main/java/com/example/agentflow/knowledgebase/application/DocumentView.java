package com.example.agentflow.knowledgebase.application;

import java.util.UUID;

/**
 * The read model of a {@code documents} row the indexing path needs (S10-13), exposed through the
 * {@code knowledgebase} public application boundary.
 *
 * <p>Carries exactly what the ingestion worker must know to index the document: the identity and
 * tenant binding, the object-storage locator ({code objectKey}) the payload is fetched with, the
 * content fingerprint ({@code sha256}) and the pipeline version the ingestion idempotency key is
 * built from (plan §8.2: {@code tenant_id + sha256 + pipeline_version}), plus the embedding model
 * pinned on the parent knowledge base ({@code knowledge_bases.embedding_model}) the vectors are
 * produced with.
 *
 * <p>{@code status} is the canonical lifecycle name (see {@code docs/architecture/
 * document-lifecycle.md}: {@code UPLOADED / QUEUED / PROCESSING / INDEXED / FAILED /
 * DELETE_REQUESTED / DELETED}). It is carried as a plain string so a consumer module (the {@code
 * ingestion} worker) never imports this module's internal {@code domain} package; all state
 * decisions stay encapsulated in {@link DocumentTransitionService}, which is the single writer of
 * {@code documents.status}.
 *
 * @param documentId the document id
 * @param tenantId the owning tenant (the isolation key)
 * @param knowledgeBaseId the parent collection
 * @param objectKey the server-generated object-storage key the payload is stored under
 * @param fileName the server-sanitised display name
 * @param mimeType the canonical content type
 * @param sizeBytes the payload size in bytes
 * @param sha256 the payload SHA-256 hex fingerprint (the idempotency key seed)
 * @param status the lifecycle state name (see above)
 * @param pipelineVersion the chunking/embedding pipeline version indexing this document
 * @param embeddingModel the embedding model pinned on the parent knowledge base
 */
public record DocumentView(
    UUID documentId,
    UUID tenantId,
    UUID knowledgeBaseId,
    String objectKey,
    String fileName,
    String mimeType,
    long sizeBytes,
    String sha256,
    String status,
    String pipelineVersion,
    String embeddingModel) {}
