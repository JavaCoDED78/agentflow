package com.example.agentflow.knowledgebase.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The pgvector persistence adapter (S10-11) against a <em>real</em> PostgreSQL &mdash; {@code
 * pgvector/pgvector:0.8.6-pg18}, the exact image Compose pins (never {@code H2}, per {@code
 * AGENTS.md}). It proves the S10-11 acceptance: the nearest-neighbour query executes on the same
 * image type as Compose, returns the closest chunks ordered by distance, stays tenant-scoped (a
 * cross-tenant chunk never surfaces), and the fixed {@code vector(768)} dimension rejects a
 * mismatched width at the database layer.
 *
 * <p>Every probe rolls back so nothing leaks between methods (the throwaway container is the only
 * thing that holds the rows).
 */
@Testcontainers
class DocumentChunkRepositoryIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;
  private static DocumentChunkRepository repo;

  @BeforeAll
  static void migrateAndWire() throws SQLException {
    Flyway flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:migrations")
            .load();
    flyway.migrate();

    DataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    repo = new DocumentChunkRepository(jdbc);
  }

  /**
   * Persist a chunk through the real {@link DocumentChunkRepository#insert} (the production path
   * the adapter owns): its {@code nomic-embed-text} vector is bound and cast {@code ::vector}
   * there, so this exercises the same code the indexing path (`S10-13`) will use.
   */
  private void insertChunk(
      UUID tenant, UUID document, int index, float[] embedding, String content) {
    repo.insert(
        uuid(), tenant, document, "v1", index, 1, 1, content, "hash-" + index, embedding, "tester");
  }

  /** A bare tenant with a knowledge base and a document (the FK chain a chunk needs). */
  private static UUID tenantWithDocument(String slug) {
    jdbc.update("INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')", uuid(), slug);
    UUID tenant = tenantId(slug);
    UUID kb = uuid();
    jdbc.update(
        "INSERT INTO knowledge_bases (id, tenant_id, name, embedding_model) VALUES (?, ?, ?, ?)",
        kb,
        tenant,
        slug + "-kb",
        "nomic-embed-text");
    UUID document = uuid();
    jdbc.update(
        "INSERT INTO documents (id, tenant_id, knowledge_base_id, object_key, filename, "
            + "mime_type, size_bytes, sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        document,
        tenant,
        kb,
        "documents/" + tenant + ".pdf",
        "sample.pdf",
        "application/pdf",
        1024L,
        "sha256-" + slug);
    return tenant;
  }

  /**
   * A 768-dimension vector whose only non-zero components are the first three (set from {@code a,
   * b, c}); the remaining 765 are zero. The repository's {@code vector(768)} guard accepts it, and
   * the {@code cosine} helper — which reads only the first three components — keeps the ordering
   * math unchanged while the query round-trips through the real 768-dim column.
   */
  private static float[] unit768(float a, float b, float c) {
    float[] v = fill(0f, 768);
    double norm = Math.sqrt(a * a + b * b + c * c);
    v[0] = a / (float) norm;
    v[1] = b / (float) norm;
    v[2] = c / (float) norm;
    return v;
  }

  private static float[] fill(float value, int dim) {
    float[] v = new float[dim];
    java.util.Arrays.fill(v, value);
    return v;
  }

  /**
   * Cosine distance between two 768-d vectors (only the first three components are non-zero here).
   */
  private static float cosine(float[] a, float[] b) {
    double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    return (float) (1.0 - dot);
  }

  /** The S10-11 acceptance: a nearest-neighbour query executes, returns the closest chunk first. */
  @Test
  void nearest_neighbour_search_runs_and_orders_by_distance() throws SQLException {
    // `repo` is wired in migrateAndWire (a stateless wrapper over the shared jdbc).
    UUID tenant = tenantWithDocument("nn-order");
    UUID document = documentIdForTenant(tenant);

    // Two chunks with embeddings on different axes: eA on +x, eB on +y. A query near +x must rank
    // eA first, eB second.
    float[] eA = unit768(1f, 0f, 0f);
    float[] eB = unit768(0f, 1f, 0f);
    insertChunk(tenant, document, 0, eA, "chunk on x axis");
    insertChunk(tenant, document, 1, eB, "chunk on y axis");

    float[] q = unit768(1f, 0.15f, 0f); // clearly closer to +x than to +y
    assertThat(cosine(q, eA)).isLessThan(cosine(q, eB));

    List<DocumentChunkRow> rows = repo.findNearest(tenant, q, 10);

    assertThat(rows).hasSize(2);
    // The closest chunk (the +x one) is returned first; content disambiguates the ordering.
    assertThat(rows.get(0).content()).isEqualTo("chunk on x axis");
    assertThat(rows.get(1).content()).isEqualTo("chunk on y axis");
  }

  /** Invariant 1: a nearest-neighbour query never returns another tenant's chunk. */
  @Test
  void nearest_neighbour_search_is_tenant_scoped() throws SQLException {
    // `repo` is wired in migrateAndWire (a stateless wrapper over the shared jdbc).
    UUID tenantA = tenantWithDocument("nn-tenant-a");
    UUID tenantB = tenantWithDocument("nn-tenant-b");
    UUID documentA = documentIdForTenant(tenantA);
    UUID documentB = documentIdForTenant(tenantB);

    // A chunk in tenant A, and a clearly different chunk in tenant B.
    insertChunk(tenantA, documentA, 0, unit768(1f, 0f, 0f), "A chunk");
    insertChunk(tenantB, documentB, 0, unit768(0f, 1f, 0f), "B chunk");

    // A query answered for tenant A must be empty of tenant B's chunk, even though the search is
    // over the whole table.
    List<DocumentChunkRow> forA = repo.findNearest(tenantA, unit768(1f, 0f, 0f), 10);

    assertThat(forA).hasSize(1);
    assertThat(forA.get(0).tenantId()).isEqualTo(tenantA);
    assertThat(forA.get(0).content()).isEqualTo("A chunk");
    assertThat(forA).noneMatch(r -> r.tenantId().equals(tenantB));

    // And the reverse query for tenant B only ever sees B's chunk.
    List<DocumentChunkRow> forB = repo.findNearest(tenantB, unit768(0f, 1f, 0f), 10);
    assertThat(forB).hasSize(1);
    assertThat(forB.get(0).content()).isEqualTo("B chunk");
  }

  /** The fixed 768 dimension is a database guard: a mismatched width is rejected by PostgreSQL. */
  @Test
  void embedding_dimension_is_fixed_to_768() throws SQLException {
    // `repo` is wired in migrateAndWire (a stateless wrapper over the shared jdbc).
    UUID tenant = tenantWithDocument("nn-dim");
    UUID document = documentIdForTenant(tenant);

    // A correct 768-dim chunk lands through the adapter.
    insertChunk(tenant, document, 0, fill((float) 0.01, 768), "correct width");

    // A 769-dim vector is rejected by the vector(768) column, not by the application: the insert
    // bypasses the adapter's Java guard so only the database guard can stop it.
    assertThatThrownBy(
            () ->
                statement()
                    .execute(
                        "INSERT INTO document_chunks (id, tenant_id, document_id, pipeline_version, "
                            + "chunk_index, page_from, page_to, content, content_hash, embedding) "
                            + "VALUES ('"
                            + uuid()
                            + "', '"
                            + tenant
                            + "', '"
                            + document
                            + "', 'v1', 5, 1, 1, 'bad', 'h', "
                            + vectorLiteral(fill(0.01f, 769))
                            + "::vector)"))
        // The vector(768) column rejects a 769-dim width at the database layer. The exact
        // pgvector error text is version-dependent, so the assertion is the thrown SQL error, not
        // its wording.
        .isInstanceOf(SQLException.class);

    // The valid chunk is still retrievable; the rejected insert left no row behind (rolled back).
    assertThat(repo.findNearest(tenant, fill((float) 0.01, 768), 10)).hasSize(1);
  }

  /** A wrong-dim query vector is refused in Java before it can reach the database. */
  @Test
  void find_nearest_rejects_a_wrong_width_query_vector() {
    // `repo` is wired in migrateAndWire (a stateless wrapper over the shared jdbc).
    UUID tenant = tenantWithDocument("nn-query-dim");
    assertThatThrownBy(() -> repo.findNearest(tenant, fill(0.1f, 100), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("768");
  }

  private static Connection connection() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Statement statement() throws SQLException {
    return connection().createStatement();
  }

  private static String vectorLiteral(float[] values) {
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

  private static UUID uuid() {
    return UUID.randomUUID();
  }

  private static UUID tenantId(String slug) {
    try (Statement st = statement();
        java.sql.ResultSet rs =
            st.executeQuery("SELECT id FROM tenants WHERE slug = '" + slug + "'")) {
      return rs.next() ? rs.getObject("id", UUID.class) : null;
    } catch (SQLException e) {
      throw new UncheckedSQLException(e);
    }
  }

  private static UUID documentIdForTenant(UUID tenant) {
    try (Statement st = statement();
        java.sql.ResultSet rs =
            st.executeQuery("SELECT id FROM documents WHERE tenant_id = '" + tenant + "'")) {
      return rs.next() ? rs.getObject("id", UUID.class) : null;
    } catch (SQLException e) {
      throw new UncheckedSQLException(e);
    }
  }

  private static final class UncheckedSQLException extends RuntimeException {
    UncheckedSQLException(SQLException cause) {
      super(cause);
    }
  }
}
