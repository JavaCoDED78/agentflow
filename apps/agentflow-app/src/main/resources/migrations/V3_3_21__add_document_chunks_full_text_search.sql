-- S10-15 · document_chunks full-text search · owner: knowledgebase
--
-- Full-text search (FTS) half of the hybrid retrieval the glossary "Retrieval" names (S10-15 /
-- S10-16). This builds a *lexical index* on the chunk text so a keyword query finds chunks by their
-- exact content instead of by embedding similarity -- the complement of the pgvector
-- nearest-neighbour primitive (S10-11 / S10-14). S10-16 fuses the two halves.
--
-- The index is a generated `tsvector` column (`search_vector`) over `content`, built with the
-- PostgreSQL `english` text search configuration. The corpus the tests and the local documents use
-- is English, and `english` is the standard choice for English notation and identifiers, so it is
-- pinned here (the language is a fixed decision, not a runtime switch): `to_tsvector('english',
-- content)` normalises and lexemises the stored text. The column is `GENERATED ... STORED`, so it
-- is recomputed at write time (a change to `content` recomputes the vector) and always matches the
-- stored text -- there is no separate column to reindex and no drift. A `GIN` index
-- (`document_chunks_search_vector_fts_idx`) backs the `@@` match, so `search_vector @@ plainto_tsquery('english',
-- ?)` uses the index rather than a table scan.
--
-- Tenant isolation is in the query, not the index (invariant 1): the lexical query
-- (`DocumentChunkRepository#findByKeyword`) puts `c.tenant_id = ?` and the `documents` join on
-- `d.knowledge_base_id = ?` before the `@@` and the limit, so the GIN index alone (on
-- `search_vector`) is never the tenant boundary. A cross-tenant or cross-collection keyword query
-- returns nothing, exactly as a cross-tenant lookup of any other resource does.
--
-- `plainto_tsquery` (not `to_tsquery`) tokenises the incoming keyword: an untrusted search string is
-- treated as natural language and can never break the query with a malformed operator, so a hostile
-- or odd keyword is a no-match, never an error (invariant 7 -- the model / caller is not an
-- authority over query construction).
--
-- This is a forward-only migration (ADR-008); IF NOT EXISTS keeps Testcontainers rebuilds and the
-- controlled apply step quiet and changes nothing already applied.
--
-- Created after V3_3_19 (document_chunks) and V3_3_20 (embedding) so both the table and the column
-- already exist.

ALTER TABLE document_chunks
    ADD COLUMN IF NOT EXISTS search_vector tsvector
        GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX IF NOT EXISTS document_chunks_search_vector_fts_idx
    ON document_chunks USING GIN (search_vector);

COMMENT ON COLUMN document_chunks.search_vector IS
    'S10-15 · document_chunks · English FTS tsvector over `content` (GENERATED ... STORED); backs the tenant-scoped lexical keyword retrieval (S10-15 / S10-16) via the GIN index document_chunks_search_vector_fts_idx';

COMMENT ON INDEX document_chunks_search_vector_fts_idx IS
    'S10-15 · document_chunks · GIN index over search_vector for FTS keyword matching; the tenant / knowledge-base scope is applied in the query (invariant 1), not the index';
