package com.example.agentflow.knowledgebase.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The pgvector persistence adapter for {@code document_chunks} (S10-11), owned by the {@code
 * knowledgebase} module.
 *
 * <p>It does two things the plan fixes here: it <em>persists</em> a chunk's {@code
 * nomic-embed-text} embedding (a fixed 768-dimension {@code vector}, the column {@code S10-11}
 * added to {@code document_chunks}) and it runs the <em>nearest-neighbour</em> search the glossary
 * "Retrieval" names. Both are plain {@link JdbcTemplate} calls on Spring Data JDBC (the single
 * persistence approach, {@code ADR-009}); the row is a small, tenant-scoped write/read, not an
 * aggregate with a rich read model. The bean is registered only when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application boots without a database
 * ({@code S01-02}) and the caller fails loud (reports {@code 503}) when the repository is absent —
 * exactly as the sibling {@code workflowexecution} / {@code knowledgebase} repositories do.
 *
 * <h2>Binding a {@code vector} without pgvector-jdbc</h2>
 *
 * <p>This task does not add the {@code pgvector-jdbc} dependency, so the vector is bound as a
 * pgvector text literal (for example {@code '[0.1,-0.5,1.0]'} for {@code float[]}) cast to {@code
 * vector} in SQL. The cast makes the bind driver-independent: the literal parses to a {@code
 * vector(768)} and PostgreSQL rejects any other width at the insert (the dimension guard {@code
 * S10-13} / {@code S10-14} rely on). The query vector is bound the same way. The stored vector is
 * never read back: the query orders by distance to the in-memory query vector, so the {@code
 * RowMapper} maps only plain columns (UUID / text / int / timestamptz), which the plain driver
 * reads without a pgvector type adapter.
 *
 * <h2>Tenant scope (invariant 1)</h2>
 *
 * <p>Every read and every write is scoped by the {@code tenant_id} column. {@link #findNearest}
 * puts the tenant predicate <em>inside the query</em> ({@code WHERE tenant_id = ?}), before the
 * nearest-neighbour ordering and limit — a post-filter is not permitted, so another tenant's chunk
 * can never surface as a "close" match. A cross-tenant nearest-neighbour result set is empty, just
 * as a cross-tenant lookup of any other resource is.
 *
 * <h2>Reliability</h2>
 *
 * <p>Neither method is a Kafka/transactional boundary. The insert is a single tenant-scoped row;
 * the nearest-neighbour query is a read. The indexing worker (S10-13) owns idempotency (the {@code
 * (document_id, chunk_index, pipeline_version)} unique forbids a duplicate chunk); this adapter
 * only persists and reads what it is handed.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class DocumentChunkRepository {

  private static final String TABLE = "document_chunks";

  private static final String INSERT_SQL =
      "INSERT INTO "
          + TABLE
          + " (id, tenant_id, document_id, pipeline_version, chunk_index, page_from, page_to, "
          + "content, content_hash, embedding, created_by) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?)";

  private static final String FIND_NEAREST_SQL =
      "SELECT id, tenant_id, document_id, pipeline_version, chunk_index, page_from, page_to, "
          + "content, content_hash, created_by, created_at "
          + "FROM "
          + TABLE
          + " WHERE tenant_id = ? "
          + "ORDER BY embedding <=> ?::vector "
          + "LIMIT ?";

  private final JdbcTemplate jdbc;

  /**
   * @param jdbc the JDBC template for the application data source
   */
  public DocumentChunkRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Insert one {@code document_chunks} row together with its {@code nomic-embed-text} embedding.
   *
   * <p>Embeddings are produced by the {@code S10-13} indexing path, which writes the chunk and its
   * vector in one step, so this {@code insert} always binds one. The {@code embedding} column is
   * {@code nullable} (a chunk is created during ingestion {@code S10-09} and indexed only later,
   * {@code S10-13}, so a row can predate its vector): {@code vector(768)} still fixes the width of
   * anything stored, and no {@code DEFAULT} is needed. The row is written under {@code tenantId}
   * (the tenant from the verified security context, never a caller-supplied value, invariant 1);
   * the {@code (document_id, chunk_index, pipeline_version)} unique — a guard declared by {@code
   * S10-10} — rejects a duplicate chunk, so a redelivered indexing event (S10-13) cannot create a
   * second row.
   *
   * @param chunkId the server-issued id of the row
   * @param tenantId the owning tenant (from the verified security context)
   * @param documentId the parent document
   * @param pipelineVersion the chunking pipeline version that produced the chunk
   * @param chunkIndex the zero-based position within the document's chunk set
   * @param pageFrom the first page the fragment spans (>= 1)
   * @param pageTo the last page the fragment spans (>= {@code pageFrom})
   * @param content the fragment text
   * @param contentHash the SHA-256 hex fingerprint of {@code content}
   * @param embedding the 768-dimension {@code nomic-embed-text} vector of {@code content}; the
   *     fixed {@code vector(768)} column rejects any other width
   * @param createdBy the actor recorded for the audit trail, or {@code null} for a system actor
   */
  public void insert(
      UUID chunkId,
      UUID tenantId,
      UUID documentId,
      String pipelineVersion,
      int chunkIndex,
      int pageFrom,
      int pageTo,
      String content,
      String contentHash,
      float[] embedding,
      String createdBy) {
    if (embedding.length != 768) {
      // The column is vector(768); refuse the wrong width in Java too, so the failure is explicit
      // and dependency-free rather than a driver-side "invalid vector length".
      throw new IllegalArgumentException(
          "embedding must be 768 dimensions (nomic-embed-text), was " + embedding.length);
    }
    jdbc.update(
        INSERT_SQL,
        ps -> {
          bindNullableUuid(ps, 1, chunkId);
          bindNullableUuid(ps, 2, tenantId);
          bindNullableUuid(ps, 3, documentId);
          bindString(ps, 4, pipelineVersion);
          ps.setInt(5, chunkIndex);
          ps.setInt(6, pageFrom);
          ps.setInt(7, pageTo);
          bindString(ps, 8, content);
          bindString(ps, 9, contentHash);
          bindVector(ps, 10, embedding);
          bindString(ps, 11, createdBy);
        });
  }

  /**
   * Return the closest chunks to {@code query} for one tenant, in ascending distance order.
   *
   * <p>The tenant predicate is the first clause ({@code WHERE tenant_id = ?}), so the nearest
   * -neighbour search never sees another tenant's rows (invariant 1); a cross-tenant query returns
   * an empty list. The cosine distance to {@code query} orders the rows; {@code limit} caps the
   * result set (the {@code S10-14} retrieval layer applies its own relevance threshold on top). The
   * stored vector is not selected, so no {@code vector} column is mapped.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param query the 768-dimension query vector; the same {@code nomic-embed-text} space as the
   *     stored vectors
   * @param limit the maximum number of chunks to return (>= 1)
   * @return the nearest chunks, closest first; empty when the tenant has no chunks
   */
  public List<DocumentChunkRow> findNearest(UUID tenantId, float[] query, int limit) {
    if (query.length != 768) {
      throw new IllegalArgumentException(
          "query vector must be 768 dimensions (nomic-embed-text), was " + query.length);
    }
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be >= 1, was " + limit);
    }
    return jdbc.query(
        FIND_NEAREST_SQL, new ChunkRowMapper(), tenantId, bindVectorLiteral(query), limit);
  }

  /**
   * Insert one {@code document_chunks} row unless the {@code (document_id, chunk_index,
   * pipeline_version)} unique key already holds it (S10-13, the redelivery {@code SKIP} policy).
   * The conflict target is the unique constraint {@code S10-10} declared, so a redelivered indexing
   * event can never create a duplicate logical chunk — the database guard, not a post-check.
   *
   * @param chunkId the server-issued id of the row
   * @param tenantId the owning tenant
   * @param documentId the parent document
   * @param pipelineVersion the chunking pipeline version that produced the chunk
   * @param chunkIndex the zero-based position within the document's chunk set
   * @param pageFrom the first page the fragment spans
   * @param pageTo the last page the fragment spans
   * @param content the fragment text
   * @param contentHash the SHA-256 hex fingerprint of {@code content}
   * @param embedding the 768-dimension vector of {@code content}
   * @param createdBy the actor recorded for the audit trail, or {@code null} for a system actor
   * @return {@code true} when the row was inserted; {@code false} when the key already existed (the
   *     redelivery was skipped)
   */
  public boolean insertSkippingConflicts(
      UUID chunkId,
      UUID tenantId,
      UUID documentId,
      String pipelineVersion,
      int chunkIndex,
      int pageFrom,
      int pageTo,
      String content,
      String contentHash,
      float[] embedding,
      String createdBy) {
    requireEmbeddingWidth(embedding);
    int updated =
        jdbc.update(
            INSERT_SQL + " ON CONFLICT (document_id, chunk_index, pipeline_version) DO NOTHING",
            ps -> {
              bindChunk(
                  ps,
                  chunkId,
                  tenantId,
                  documentId,
                  pipelineVersion,
                  chunkIndex,
                  pageFrom,
                  pageTo,
                  content,
                  contentHash,
                  embedding,
                  createdBy);
            });
    return updated > 0;
  }

  /**
   * Insert one {@code document_chunks} row or overwrite the existing one in place (S10-13, the
   * redelivery {@code UPDATE} policy): on a {@code (document_id, chunk_index, pipeline_version)}
   * conflict the fragment and its vector are replaced by the new values, so a reindex refreshes the
   * index without duplicating or deleting rows. The key (and the row id) is stable across the
   * update; only the payload columns move.
   *
   * @param chunkId the server-issued id of the row
   * @param tenantId the owning tenant
   * @param documentId the parent document
   * @param pipelineVersion the chunking pipeline version that produced the chunk
   * @param chunkIndex the zero-based position within the document's chunk set
   * @param pageFrom the first page the fragment spans
   * @param pageTo the last page the fragment spans
   * @param content the fragment text
   * @param contentHash the SHA-256 hex fingerprint of {@code content}
   * @param embedding the 768-dimension vector of {@code content}
   * @param createdBy the actor recorded for the audit trail, or {@code null} for a system actor
   */
  public void upsertUpdating(
      UUID chunkId,
      UUID tenantId,
      UUID documentId,
      String pipelineVersion,
      int chunkIndex,
      int pageFrom,
      int pageTo,
      String content,
      String contentHash,
      float[] embedding,
      String createdBy) {
    requireEmbeddingWidth(embedding);
    jdbc.update(
        INSERT_SQL
            + " ON CONFLICT (document_id, chunk_index, pipeline_version) DO UPDATE SET "
            + "page_from = EXCLUDED.page_from, page_to = EXCLUDED.page_to, "
            + "content = EXCLUDED.content, content_hash = EXCLUDED.content_hash, "
            + "embedding = EXCLUDED.embedding",
        ps -> {
          bindChunk(
              ps,
              chunkId,
              tenantId,
              documentId,
              pipelineVersion,
              chunkIndex,
              pageFrom,
              pageTo,
              content,
              contentHash,
              embedding,
              createdBy);
        });
  }

  /**
   * Binds one chunk row for {@link #insertSkippingConflicts} / {@link #upsertUpdating} (the same
   * column order as {@link #INSERT_SQL}).
   */
  private static void bindChunk(
      PreparedStatement ps,
      UUID chunkId,
      UUID tenantId,
      UUID documentId,
      String pipelineVersion,
      int chunkIndex,
      int pageFrom,
      int pageTo,
      String content,
      String contentHash,
      float[] embedding,
      String createdBy)
      throws SQLException {
    bindNullableUuid(ps, 1, chunkId);
    bindNullableUuid(ps, 2, tenantId);
    bindNullableUuid(ps, 3, documentId);
    bindString(ps, 4, pipelineVersion);
    ps.setInt(5, chunkIndex);
    ps.setInt(6, pageFrom);
    ps.setInt(7, pageTo);
    bindString(ps, 8, content);
    bindString(ps, 9, contentHash);
    bindVector(ps, 10, embedding);
    bindString(ps, 11, createdBy);
  }

  /**
   * Refuse a wrong embedding width in Java, so the failure is explicit and dependency-free rather
   * than a driver-side {@code invalid vector length} (the {@code vector(768)} column is the
   * database guard, this is the application one).
   */
  private static void requireEmbeddingWidth(float[] embedding) {
    if (embedding == null || embedding.length != 768) {
      throw new IllegalArgumentException(
          "embedding must be 768 dimensions (nomic-embed-text), was "
              + (embedding == null ? "null" : embedding.length));
    }
  }

  /**
   * Maps one {@code document_chunks} row to a {@link DocumentChunkRow} (no vector column selected).
   */
  private static final class ChunkRowMapper implements RowMapper<DocumentChunkRow> {

    @Override
    public DocumentChunkRow mapRow(ResultSet rs, int rowNum) throws SQLException {
      return new DocumentChunkRow(
          rs.getObject("id", UUID.class),
          rs.getObject("tenant_id", UUID.class),
          rs.getObject("document_id", UUID.class),
          rs.getString("pipeline_version"),
          rs.getInt("chunk_index"),
          rs.getInt("page_from"),
          rs.getInt("page_to"),
          rs.getString("content"),
          rs.getString("content_hash"),
          rs.getString("created_by"),
          rs.getObject("created_at", OffsetDateTime.class));
    }
  }

  /** Binds a {@code float[]} as a pgvector text literal cast to {@code vector} in SQL. */
  private static void bindVector(PreparedStatement ps, int index, float[] values)
      throws SQLException {
    ps.setString(index, toVectorLiteral(values));
  }

  /**
   * Bind the query vector for the nearest-neighbour ordering clause the same way the insert binds
   * the stored one, so both round-trip through the identical {@code ::vector} cast.
   */
  private static String bindVectorLiteral(float[] values) {
    return toVectorLiteral(values);
  }

  /**
   * Formats a {@code float[]} as a pgvector literal, {@code [v1,v2,...,vN]}. pgvector 0.8.x uses
   * the PostgreSQL-array syntax (square brackets), so the same format the database parses here.
   */
  private static String toVectorLiteral(float[] values) {
    StringBuilder sb = new StringBuilder(values.length * 8 + 2);
    sb.append('[');
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(Float.toString(values[i]));
    }
    sb.append(']');
    return sb.toString();
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
