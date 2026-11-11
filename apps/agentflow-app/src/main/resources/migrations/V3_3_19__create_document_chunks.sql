-- S10-10 · document_chunks · owner: knowledgebase
--
-- One row per deterministic fragment (glossary "Chunk") of an extracted document, ready for
-- embedding and indexing. `knowledgebase` owns the table: a chunk is the document's persisted
-- fragment, so it references its parent `documents` row and the `(tenant_id, document_id)` read
-- keeps retrieval tenant-scoped (invariant 1). This is a forward-only migration (ADR-008);
-- IF NOT EXISTS keeps Testcontainers rebuilds and the controlled apply step quiet.
--
-- `tenant_id` is a foreign key to `tenants` and is the isolation key: another tenant's chunk is
-- indistinguishable from a missing one through the `(tenant_id, document_id)` read, never a
-- nullable owner column (invariant 1). `document_id` references `documents` and cascades: a
-- document's fragments go away when the document is deleted (the two-phase document lifecycle,
-- document-lifecycle.md). `pipeline_version` is the chunking configuration version that produced
-- the chunk (ingestion.domain.DocumentChunk#pipelineVersion); it is part of the composite unique,
-- so a retuned pipeline invalidates the old chunks on reindex (the seed S10-13 / S10-19
-- idempotency key).
--
-- Duplicate chunk within a version is forbidden by the UNIQUE(document_id, chunk_index,
-- pipeline_version) constraint: one document has exactly one chunk per (position, pipeline), so a
-- repeated chunk set (a redelivery, S10-13) cannot create a second row — the DB guard, not a
-- post-check. The embedding vector itself is added by S10-11 (pgvector); chunk metadata such as
-- embedding dimensionality / model lives in metadata_json.
--
-- Created after documents (V3_3_18) so the document_id foreign key resolves.
CREATE TABLE IF NOT EXISTS document_chunks (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL REFERENCES tenants (id),
    document_id      UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    pipeline_version TEXT        NOT NULL DEFAULT 'v1',
    chunk_index      INTEGER     NOT NULL,
    page_from        INTEGER     NOT NULL,
    page_to          INTEGER     NOT NULL,
    content          TEXT        NOT NULL,
    content_hash     TEXT        NOT NULL,
    metadata_json    JSONB,
    created_by       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_chunks_document_index_version UNIQUE (document_id, chunk_index, pipeline_version)
);

COMMENT ON TABLE document_chunks IS
    'S10-10 · document_chunks · owner: knowledgebase · one fragment per row; unique (document_id, chunk_index, pipeline_version) forbids a duplicate chunk within a version; chunks cascade with their document';

COMMENT ON COLUMN document_chunks.tenant_id IS
    'S10-10 · document_chunks · owner tenant (FK tenants.id); tenant scope enforced by the (tenant_id, document_id) read (invariant 1)';

COMMENT ON COLUMN document_chunks.document_id IS
    'S10-10 · document_chunks · parent document (FK documents.id); ON DELETE CASCADE removes the fragments when the document is deleted';

COMMENT ON COLUMN document_chunks.pipeline_version IS
    'S10-10 · document_chunks · chunking pipeline version that produced the chunk; part of the (document_id, chunk_index, pipeline_version) unique and the S10-13 / S10-19 idempotency key';

COMMENT ON COLUMN document_chunks.page_from IS
    'S10-10 · document_chunks · first page the fragment spans (>= 1)';

COMMENT ON COLUMN document_chunks.page_to IS
    'S10-10 · document_chunks · last page the fragment spans (>= page_from)';

COMMENT ON COLUMN document_chunks.content_hash IS
    'S10-10 · document_chunks · SHA-256 hex fingerprint of the content; the idempotency seed S10-13 keys on';

COMMENT ON COLUMN document_chunks.metadata_json IS
    'S10-10 · document_chunks · optional chunk metadata (e.g. embedding dimensionality / model); the embedding vector itself is added by S10-11 (pgvector)';

-- One index per real lookup path:
--   (tenant_id, document_id, chunk_index) — a document's chunks, tenant-scoped, in creation order;
--     the tenant filter is mandatory on chunk search (invariant 1). The unique constraint already
--     backs its own (document_id, chunk_index, pipeline_version) index, so it is not duplicated.
CREATE INDEX IF NOT EXISTS document_chunks_tenant_document_idx
    ON document_chunks (tenant_id, document_id, chunk_index);
