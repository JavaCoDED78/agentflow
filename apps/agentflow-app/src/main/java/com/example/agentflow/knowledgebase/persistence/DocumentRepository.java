package com.example.agentflow.knowledgebase.persistence;

import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The single write path for a {@code documents} metadata row, owned by the {@code knowledgebase}
 * module.
 *
 * <p>Backed by a plain {@link JdbcTemplate} (Spring Data JDBC is the single persistence approach,
 * <code>ADR-009</code>; the row is a small, tenant-scoped insert, not an aggregate with a rich read
 * model). The bean is registered only when a {@link DataSource} exists (<code>
 * @ConditionalOnBean(DataSource.class)</code>), so the application boots without a database (<code>
 * S01-02</code>) and the transition service fails loudly (reports <code>503</code>) when the
 * repository is absent — exactly as {@code workflowexecution} does for its repositories.
 *
 * <p>Every write is tenant-scoped through the <code>tenant_id</code> column (invariant 1): the
 * <code>documents</code> row is created under the tenant taken from the verified security context,
 * and every later read of it is by the pair <code>(tenant_id, id)</code>, so a cross-tenant id is
 * indistinguishable from a missing one. The insert does not attempt to read the parent <code>
 * knowledge_bases</code> row (that belongs to the follow-up upload controller / a FK that the
 * <code>S10-06</code> enqueue flow leaves to the database); the <code>knowledge_base_id</code> FK
 * is enforced by PostgreSQL, not by a pre-check that would need a second read.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class DocumentRepository {

  private static final String TABLE = "documents";

  private final JdbcTemplate jdbc;

  /**
   * @param jdbc the JDBC template for the application data source
   */
  public DocumentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Insert one {@code documents} metadata row in status <code>UPLOADED</code>, the lifecycle state
   * the {@code S10-03} upload returns and the state the {@code S10-06} enqueue flow starts from.
   * The <code>status</code> column carries the <code>UPLOADED</code> default, but it is bound
   * explicitly so the intent of this creation path is visible in one place. The <code>page_count
   * </code> column is left to its database default (NULL): the upload has not parsed the object,
   * and a <code>S10-08</code> diagnostic fills it later.
   *
   * <p>The <code>tenant_id</code> predicate is never part of this insert (an insert creates a row;
   * it does not read one), but the row is written <em>under</em> the tenant passed here — the same
   * tenant the verified security context supplied, never a body/header value (invariant 1). The
   * <code>version</code> optimistic-lock counter starts at <code>0</code> and is bumped by the
   * transition service on every later status change (the <code>documents_status_check</code> in the
   * migration pins the vocabulary).
   *
   * @param documentId the server-issued id of the row (also the identity returned to the caller)
   * @param tenantId the owning tenant (from the verified security context)
   * @param knowledgeBaseId the parent collection this document is uploaded into
   * @param objectKey the server-generated object-storage key the object is stored under
   * @param fileName the server-sanitised, path-free filename
   * @param mimeType the canonical, allowlisted content type
   * @param sizeBytes the payload size in bytes
   * @param checksumSha256 the SHA-256 of the payload, hex-encoded (the ingestion idempotency key
   *     seed, plan §8.2)
   * @param createdBy the actor recorded for the audit trail, or {@code null} for a system actor
   */
  public void insert(
      UUID documentId,
      UUID tenantId,
      UUID knowledgeBaseId,
      String objectKey,
      String fileName,
      String mimeType,
      long sizeBytes,
      String checksumSha256,
      String createdBy) {
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, tenant_id, knowledge_base_id, object_key, filename, mime_type, "
            + "size_bytes, sha256, version, status, pipeline_version, created_by) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'UPLOADED', 'v1', ?)",
        ps -> {
          bindNullableUuid(ps, 1, documentId);
          bindNullableUuid(ps, 2, tenantId);
          bindNullableUuid(ps, 3, knowledgeBaseId);
          bindString(ps, 4, objectKey);
          bindString(ps, 5, fileName);
          bindString(ps, 6, mimeType);
          ps.setLong(7, sizeBytes);
          bindString(ps, 8, checksumSha256);
          bindString(ps, 9, createdBy);
        });
  }

  /**
   * Read one document by the {@code (tenant_id, id)} pair, joined with the embedding model pinned
   * on its parent knowledge base (S10-13). The tenant predicate is part of the query itself
   * (invariant 1): another tenant's document is indistinguishable from a missing one, so a forged
   * or cross-tenant id yields {@link Optional#empty()}.
   *
   * @param tenantId the owning tenant (from the stored event, re-checked here against PostgreSQL)
   * @param documentId the document id
   * @return the indexing read model, or empty when no such tenant-owned document exists
   */
  public Optional<DocumentIndexRow> findIndexRowByIdAndTenant(UUID tenantId, UUID documentId) {
    List<DocumentIndexRow> rows =
        jdbc.query(
            "SELECT d.id, d.tenant_id, d.knowledge_base_id, d.object_key, d.filename, "
                + "d.mime_type, d.size_bytes, d.sha256, d.status, d.pipeline_version, "
                + "k.embedding_model "
                + "FROM "
                + TABLE
                + " d JOIN knowledge_bases k ON k.id = d.knowledge_base_id "
                + "WHERE d.tenant_id = ? AND d.id = ?",
            indexRowMapper,
            tenantId,
            documentId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Atomically claim a document for indexing (S10-13): a conditional {@code QUEUED/UPLOADED ->
   * PROCESSING} update guarded by the {@code status} column and the optimistic {@code version}
   * counter. The guard is the single-writer fence: exactly one worker can move the row out of a
   * claimable state, so a concurrent duplicate delivery (at-least-once, ADR-003) or a second worker
   * finds zero rows and skips. The status vocabulary is pinned by the {@code
   * documents_status_check} constraint; the allowed from-states are bound as enum names (never
   * caller-typed SQL).
   *
   * <p>This is the {@code worker.claim} transition of {@code document-lifecycle.md}. The lease
   * columns the lifecycle document describes ({@code lease_owner}/{@code lease_until}/{@code
   * lease_epoch}) are a recorded follow-up: the claim today is the conditional status update, which
   * is sufficient for the inbox-transactional indexing (a crashed worker's transaction rolls back,
   * releasing the claim).
   *
   * @param tenantId the owning tenant
   * @param documentId the document id
   * @param fromStates the states the claim may move out of (never empty)
   * @return {@code true} when this call claimed the document (exactly one row transitioned)
   */
  public boolean claimForIndexing(
      UUID tenantId, UUID documentId, Set<DocumentUploadStatus> fromStates) {
    if (fromStates.isEmpty()) {
      throw new IllegalArgumentException("fromStates must not be empty");
    }
    String placeholders = fromStates.stream().map(s -> "?").collect(Collectors.joining(", "));
    List<String> stateNames = fromStates.stream().map(Enum::name).collect(Collectors.toList());
    Object[] args = new Object[2 + stateNames.size()];
    args[0] = tenantId;
    args[1] = documentId;
    for (int i = 0; i < stateNames.size(); i++) {
      args[2 + i] = stateNames.get(i);
    }
    int updated =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " SET status = 'PROCESSING', version = version + 1, updated_at = now() "
                + "WHERE tenant_id = ? AND id = ? AND status IN ("
                + placeholders
                + ")",
            args);
    return updated > 0;
  }

  /**
   * Move a claimed document {@code PROCESSING -> INDEXED} (the {@code document.indexed} transition
   * applied by the worker's transaction), recording the extraction diagnostic (S10-08). Guarded by
   * {@code status = 'PROCESSING'} so only the claiming worker completes it; zero rows means the
   * document is no longer in the claimed state and the caller treats the completion as a no-op.
   *
   * @param tenantId the owning tenant
   * @param documentId the document id
   * @param pageCount the extracted page count, or {@code null} to leave the column untouched
   * @return {@code true} when the transition applied
   */
  public boolean markIndexed(UUID tenantId, UUID documentId, Integer pageCount) {
    int updated =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " SET status = 'INDEXED', version = version + 1, updated_at = now(), "
                + "page_count = COALESCE(?, page_count) "
                + "WHERE tenant_id = ? AND id = ? AND status = 'PROCESSING'",
            pageCount,
            tenantId,
            documentId);
    return updated > 0;
  }

  /**
   * Move a claimed document {@code PROCESSING -> FAILED} with the safe, machine-readable error code
   * and redacted summary (S10-08: never the raw exception text or a document fragment). Guarded by
   * {@code status = 'PROCESSING'} like every working transition.
   *
   * @param tenantId the owning tenant
   * @param documentId the document id
   * @param errorCode the safe machine-readable failure token (never {@code null})
   * @param errorSummary the short redacted failure summary (never {@code null})
   * @return {@code true} when the transition applied
   */
  public boolean markFailed(UUID tenantId, UUID documentId, String errorCode, String errorSummary) {
    int updated =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " SET status = 'FAILED', version = version + 1, updated_at = now(), "
                + "error_code = ?, error_summary = ? "
                + "WHERE tenant_id = ? AND id = ? AND status = 'PROCESSING'",
            errorCode,
            errorSummary,
            tenantId,
            documentId);
    return updated > 0;
  }

  /**
   * Release a claimed document back {@code PROCESSING -> QUEUED} after a transient failure (the
   * retry edge of {@code document-lifecycle.md}), so a later delivery re-drives it. Guarded by
   * {@code status = 'PROCESSING'}.
   *
   * @param tenantId the owning tenant
   * @param documentId the document id
   * @return {@code true} when the release applied
   */
  public boolean releaseToQueued(UUID tenantId, UUID documentId) {
    int updated =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " SET status = 'QUEUED', version = version + 1, updated_at = now() "
                + "WHERE tenant_id = ? AND id = ? AND status = 'PROCESSING'",
            tenantId,
            documentId);
    return updated > 0;
  }

  /**
   * Maps the joined {@code documents} + {@code knowledge_bases} read to {@link DocumentIndexRow}.
   */
  private final RowMapper<DocumentIndexRow> indexRowMapper =
      (rs, rowNum) ->
          new DocumentIndexRow(
              rs.getObject("id", UUID.class),
              rs.getObject("tenant_id", UUID.class),
              rs.getObject("knowledge_base_id", UUID.class),
              rs.getString("object_key"),
              rs.getString("filename"),
              rs.getString("mime_type"),
              rs.getLong("size_bytes"),
              rs.getString("sha256"),
              DocumentUploadStatus.valueOf(rs.getString("status")),
              rs.getString("pipeline_version"),
              rs.getString("embedding_model"));

  /** Binds a nullable {@code UUID} so an absent value is stored as SQL {@code NULL}. */
  private static void bindNullableUuid(PreparedStatement ps, int index, UUID value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.OTHER);
    } else {
      ps.setObject(index, value);
    }
  }

  /** Binds a {@code String} column so an absent value is stored as SQL {@code NULL}. */
  private static void bindString(PreparedStatement ps, int index, String value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }
}
