-- S10-11 · enable pgvector + document_chunks.embedding · owner: knowledgebase
--
-- pgvector turns PostgreSQL into the vector store the glossary "Retrieval" names (tenant/ACL
-- filtered nearest-neighbour search). This is the only migration of Sprint 10 that adds a
-- PostgreSQL EXTENSION rather than a table: the `vector` extension must exist before any column
-- of type `vector` can be declared, so it runs first and is idempotent (`IF NOT EXISTS`) to keep
-- Testcontainers rebuilds and the controlled apply step quiet. This is a forward-only migration
-- (ADR-008); it changes nothing already applied.
--
-- The embedding vector is added to `document_chunks` (created by S10-10) as a fixed-dimension
-- `vector(768)`. The dimension is pinned here (`S10-11`) so the column rejects any embedding of a
-- different width at the database layer -- a mismatched-dimension insert fails the insert, never a
-- post-check (`S10-13` / `S10-14` rely on this guard). The column is nullable: a chunk is created
-- during ingestion (`S10-09`) and may be indexed only later (`S10-13`), so a row can predate its
-- vector; a failed or pending embedding leaves it without one. `vector(768)` still fixes the width
-- of anything stored, and no DEFAULT is needed.
-- The embedding model whose 768-dimension vectors this stores is `nomic-embed-text` (the local
-- Ollama embedding model, OpenAI-compatible); its id is pinned on `knowledge_bases.embedding_model`
-- (declared by S10-05).
--
-- Created after V3_3_19 (document_chunks) so the table to alter already exists.

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS embedding vector(768);

COMMENT ON TABLE document_chunks IS
    'S10-10 / S10-11 · document_chunks · owner: knowledgebase · one fragment per row; unique (document_id, chunk_index, pipeline_version) forbids a duplicate chunk within a version; chunks cascade with their document; S10-11 adds the `embedding` vector (768-d, `nomic-embed-text`)';

COMMENT ON COLUMN document_chunks.embedding IS
    'S10-11 · document_chunks · nomic-embed-text embedding (768 dims) of `content`; fixed-dimension vector(768) rejects a mismatched width at the DB (S10-13 / S10-14); used by the tenant-scoped nearest-neighbour retrieval (S10-11 / S10-14)';
