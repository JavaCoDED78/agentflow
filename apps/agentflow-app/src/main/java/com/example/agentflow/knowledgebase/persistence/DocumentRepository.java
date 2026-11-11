package com.example.agentflow.knowledgebase.persistence;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
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
