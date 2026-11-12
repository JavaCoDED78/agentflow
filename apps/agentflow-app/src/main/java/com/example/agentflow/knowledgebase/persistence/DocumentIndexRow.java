package com.example.agentflow.knowledgebase.persistence;

import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
import java.util.UUID;

/**
 * The persistence projection of a {@code documents} row joined with its parent {@code
 * knowledge_bases.embedding_model} (S10-13), as read by the indexing lookup.
 *
 * <p>Internal to the {@code knowledgebase} module: the repository maps it, the application query
 * service translates it into the public {@code
 * com.example.agentflow.knowledgebase.application.DocumentView}. It is a separate type from that
 * public DTO (API DTO &ne; persistence object), and the {@code status} column is mapped to the
 * domain enum here, inside the owning module.
 *
 * @param documentId the document id
 * @param tenantId the owning tenant
 * @param knowledgeBaseId the parent collection
 * @param objectKey the object-storage key
 * @param fileName the sanitised display name
 * @param mimeType the canonical content type
 * @param sizeBytes the payload size in bytes
 * @param sha256 the payload SHA-256 hex fingerprint
 * @param status the lifecycle state
 * @param pipelineVersion the pipeline version indexing the document
 * @param embeddingModel the embedding model pinned on the parent knowledge base
 */
public record DocumentIndexRow(
    UUID documentId,
    UUID tenantId,
    UUID knowledgeBaseId,
    String objectKey,
    String fileName,
    String mimeType,
    long sizeBytes,
    String sha256,
    DocumentUploadStatus status,
    String pipelineVersion,
    String embeddingModel) {}
