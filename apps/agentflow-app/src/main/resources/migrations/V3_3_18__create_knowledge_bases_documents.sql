-- S10-05 · knowledge_bases, documents · owner: knowledgebase
--
-- The knowledge-base collection and the documents it holds (glossary §7, plan §8.1).
-- One owning module (`knowledgebase`) owns both tables; a `documents` row references its
-- parent collection through `knowledge_base_id`. This is a forward-only migration (ADR-008);
-- IF NOT EXISTS keeps Testcontainers rebuilds and the controlled apply step quiet.
--
-- knowledge_bases: one tenant-scoped collection of indexed documents (glossary "Knowledge
-- base"). `tenant_id` scopes every row to one isolated organization (invariant 1) and is a
-- foreign key to `tenants`; another tenant's base is indistinguishable from a missing one
-- through the parent. `name` is fixed after creation, so the collection is immutable and is
-- guarded by `unique (tenant_id, name)` rather than an optimistic-lock counter. `embedding_model`
-- pins the embedding model the collection's chunks are vectorised with (the dimensionality that
-- S10-11 fixes); `chunking_config` holds the (optional) JSON chunker configuration.
--
-- documents: one row per uploaded object (glossary "Document"). The original bytes live in
-- object storage under a server-generated, tenant-scoped `object_key`; this row in PostgreSQL
-- is the source of truth for its status (ADR-002). Columns:
--   id                the document id (UUID, assigned by the domain factory); the canonical id
--                     surfaced on GET /api/v1/documents/{documentId}.
--   tenant_id         the owning tenant (FK tenants.id); tenant isolation is enforced by the
--                     (tenant_id, id) read, never by a nullable owner column.
--   knowledge_base_id the collection this document is uploaded into (FK knowledge_bases.id);
--                     another tenant's base is indistinguishable from a missing one through
--                     the parent's tenant_id.
--   object_key        the server-generated object-storage path (documents/<tenantId>/<id>[.<ext>],
--                     domain.DocumentObjectKey); generated once at upload, immutable.
--   filename          the safe, sanitized display name (never a path — FileNameSanitizer).
--   mime_type         the canonical content type resolved by the upload validator.
--   size_bytes        the payload length in bytes (BIGINT; the S10-03 size bound is applied
--                     before the row is committed).
--   sha256            the SHA-256 of the payload (domain.DocumentUploadValidator); the content
--                     fingerprint that drives duplicate detection and ingestion idempotency
--                     (plan §8.2: idempotency by tenant_id + sha256 + pipeline_version).
--   version           application-managed optimistic-lock counter (conditional update -> 409);
--                     bumped by the transition service on every status change.
--   status            the document lifecycle state (document-lifecycle.md): UPLOADED / QUEUED /
--                     PROCESSING / INDEXED / FAILED / DELETE_REQUESTED / DELETED. The CHECK
--                     pins the canonical vocabulary; transitions go through the single
--                     knowledgebase transition service, never set from a controller/listener.
--   pipeline_version  the chunking/embedding pipeline version that indexes this document; part
--                     of the ingestion idempotency key and the reindex trigger (S10-19).
--   page_count        extractor output (parser metadata); NULL until a worker extracts the
--                     object (unknown at UPLOADED time).
--   error_code        safe machine-readable parse/ingestion failure token (S10-08); NULL on
--                     success or while processing. Never the raw exception text.
--   error_summary     a short redacted parse/ingestion failure summary (S10-08); never a full
--                     prompt or a document (invariant 8).
--   retained_until    earliest instant the object + its chunks become eligible for retention-
--                     based deletion (a data-retention deadline); NULL when retention is managed
--                     purely by the lifecycle/deletion flow.
--   created_by / created_at / updated_at  the unified audit set (database-conventions.md).
--
-- Duplicate upload policy (determined here, implemented by S10-19): a re-upload whose
-- (tenant_id, sha256) matches an existing document is content-identical, so it is treated as a
-- duplicate — the upload API returns the existing document and creates no new row. This keeps
-- ingestion idempotent by content ("a repeated upload does not create duplicate logical chunks",
-- S10-13) and never treats the delivery as exactly-once (invariant 3). The policy is enforced at
-- the application layer (a lookup keyed on the index below), so the column is indexed, not made
-- unique: a change in pipeline_version reindexes as a new document (S10-19), which a unique
-- (tenant_id, sha256) would wrongly forbid.
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL REFERENCES tenants (id),
    name            TEXT        NOT NULL,
    embedding_model TEXT        NOT NULL,
    chunking_config JSONB,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_by      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT knowledge_bases_tenant_name UNIQUE (tenant_id, name)
);

COMMENT ON TABLE knowledge_bases IS
    'S10-05 · knowledgebases · owner: knowledgebase · one tenant-scoped collection of indexed documents; unique (tenant_id, name); immutable after creation.';

COMMENT ON COLUMN knowledge_bases.tenant_id IS
    'S10-05 · knowledgebases · owner tenant (FK tenants.id); tenant scope enforced by the (tenant_id, name) unique and the (tenant_id, id) read (invariant 1)';

COMMENT ON COLUMN knowledge_bases.status IS
    'S10-05 · knowledgebases · collection status (ACTIVE); application-managed.';

COMMENT ON COLUMN knowledge_bases.embedding_model IS
    'S10-05 · knowledgebases · embedding model pinned for this collection (dimensionality fixed by S10-11); mismatched dimensionality is rejected at indexing';

-- documents: one row per uploaded object. Created after knowledge_bases so the FK resolves.
CREATE TABLE IF NOT EXISTS documents (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL REFERENCES tenants (id),
    knowledge_base_id UUID       NOT NULL REFERENCES knowledge_bases (id),
    object_key       TEXT        NOT NULL,
    filename         TEXT        NOT NULL,
    mime_type        TEXT        NOT NULL,
    size_bytes       BIGINT      NOT NULL,
    sha256           TEXT        NOT NULL,
    version          INTEGER     NOT NULL DEFAULT 0,
    status           TEXT        NOT NULL DEFAULT 'UPLOADED',
    pipeline_version TEXT        NOT NULL DEFAULT 'v1',
    page_count       INTEGER,
    error_code       TEXT,
    error_summary    TEXT,
    retained_until   TIMESTAMPTZ,
    created_by       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    CONSTRAINT documents_status_check CHECK (
        status IN ('UPLOADED', 'QUEUED', 'PROCESSING', 'INDEXED', 'FAILED', 'DELETE_REQUESTED', 'DELETED')
    )
);

COMMENT ON TABLE documents IS
    'S10-05 · documents · owner: knowledgebase · one uploaded object per row; status lifecycle pinned by a CHECK (document-lifecycle.md); payloads externalised to object storage under object_key';

COMMENT ON COLUMN documents.tenant_id IS
    'S10-05 · documents · owner tenant (FK tenants.id); tenant scope enforced by the (tenant_id, id) read (invariant 1)';

COMMENT ON COLUMN documents.knowledge_base_id IS
    'S10-05 · documents · parent knowledge base (FK knowledge_bases.id); tenant isolation flows through the parent';

COMMENT ON COLUMN documents.object_key IS
    'S10-05 · documents · server-generated, tenant-scoped object-storage path (documents/<tenantId>/<id>[.<ext>]); generated once at upload, immutable, never derived from the client filename';

COMMENT ON COLUMN documents.sha256 IS
    'S10-05 · documents · payload SHA-256; content fingerprint for duplicate detection and ingestion idempotency (tenant_id + sha256 + pipeline_version)';

COMMENT ON COLUMN documents.status IS
    'S10-05 · documents · document lifecycle state (UPLOADED, QUEUED, PROCESSING, INDEXED, FAILED, DELETE_REQUESTED, DELETED); managed by the state machine, not set directly (CHECK)';

COMMENT ON COLUMN documents.pipeline_version IS
    'S10-05 · documents · chunking/embedding pipeline version that indexes this document; part of the ingestion idempotency key and the reindex trigger';

COMMENT ON COLUMN documents.page_count IS
    'S10-05 · documents · parser metadata: extracted page count; NULL until extraction (unknown at UPLOADED)';

COMMENT ON COLUMN documents.error_code IS
    'S10-05 · documents · parser metadata: safe machine-readable failure token (S10-08); never raw error text';

COMMENT ON COLUMN documents.error_summary IS
    'S10-05 · documents · parser metadata: short redacted failure summary (S10-08); never a full prompt or document (invariant 8)';

COMMENT ON COLUMN documents.retained_until IS
    'S10-05 · documents · data-retention deadline: earliest instant the object + chunks are eligible for retention-based deletion; NULL when managed by the lifecycle flow';

COMMENT ON COLUMN documents.version IS
    'S10-05 · documents · optimistic-lock counter; bumped on each successful status update, mapped to 409 on conflict';

-- One index per real lookup path:
--   (tenant_id, knowledge_base_id, status) — list a knowledge base's documents, tenant-scoped,
--     optionally filtered by status (plan §8.1).
--   (tenant_id, sha256) — duplicate detection / idempotent lookup for the upload flow.
CREATE INDEX IF NOT EXISTS documents_tenant_kb_status_idx
    ON documents (tenant_id, knowledge_base_id, status);

CREATE INDEX IF NOT EXISTS documents_tenant_sha256_idx
    ON documents (tenant_id, sha256);
