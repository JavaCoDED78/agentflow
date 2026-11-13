package com.example.agentflow.knowledgebase.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
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
 * The {@code documents} persistence adapter's {@code S10-19} read/transition paths against a
 * <em>real</em> PostgreSQL &mdash; {@code pgvector/pgvector:0.8.6-pg18}, the exact image Compose
 * pins (never {@code H2}, per {@code AGENTS.md}). The DB-free unit suites mock {@link
 * JdbcTemplate}, so they cannot prove the SQL itself; these probes drive the real adapter against
 * the real schema ({@code V3_3_18}) and assert:
 *
 * <ul>
 *   <li>the duplicate-upload lookup {@link DocumentRepository#findByTenantAndSha256} executes on
 *       the {@code documents_tenant_sha256_idx} path and is tenant-scoped &mdash; a content-
 *       identical payload owned by another tenant is indistinguishable from a missing one
 *       (invariant 1 at the SQL layer);
 *   <li>the retry existence check {@link DocumentRepository#findByTenantAndId} is tenant-scoped the
 *       same way (a cross-tenant id yields {@code Optional.empty()}, so the manual retry reports
 *       {@code 404});
 *   <li>{@link DocumentRepository#requeueFailedToQueued} is guarded by {@code status = 'FAILED'}:
 *       it releases a failed document to {@code QUEUED}, clears the stale {@code error_code} /
 *       {@code error_summary} diagnostic and bumps the optimistic {@code version}, and it touches
 *       nothing when the document is not {@code FAILED};
 *   <li>{@link DocumentRepository#markIndexed} is guarded by {@code status = 'PROCESSING'} and
 *       records the {@code pipeline_version} / {@code page_count} the just-completed pass produced
 *       (the reindex trigger: the document always reflects the pipeline version backing it).
 * </ul>
 *
 * <p>Each probe uses its own tenant slug, so nothing leaks between methods (the throwaway container
 * is the only thing that holds the rows).
 */
@Testcontainers
class DocumentRepositoryIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;
  private static DocumentRepository repo;

  @BeforeAll
  static void migrateAndWire() {
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
    repo = new DocumentRepository(jdbc);
  }

  /** The tenant/knowledge-base/document chain a {@code documents} probe needs. */
  private record Fixture(UUID tenantId, UUID knowledgeBaseId, UUID documentId) {}

  private static Fixture tenantWithDocument(String slug, String sha256) {
    jdbc.update("INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')", uuid(), slug);
    UUID tenant = jdbc.queryForObject("SELECT id FROM tenants WHERE slug = ?", UUID.class, slug);
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
        sha256);
    return new Fixture(tenant, kb, document);
  }

  /** Force a lifecycle state (and optionally a stale diagnostic) straight on the row. */
  private static void forceStatus(UUID documentId, String status, Integer version) {
    jdbc.update(
        "UPDATE documents SET status = ?, version = COALESCE(?, version) WHERE id = ?",
        status,
        version,
        documentId);
  }

  private static void failWithDiagnostic(UUID documentId) {
    jdbc.update(
        "UPDATE documents SET status = 'FAILED', error_code = 'EXTRACTION_CORRUPT', "
            + "error_summary = 'unrecoverable cross-reference', version = 2 WHERE id = ?",
        documentId);
  }

  private static String statusOf(UUID documentId) {
    return jdbc.queryForObject(
        "SELECT status FROM documents WHERE id = ?", String.class, documentId);
  }

  private static Integer versionOf(UUID documentId) {
    return jdbc.queryForObject(
        "SELECT version FROM documents WHERE id = ?", Integer.class, documentId);
  }

  private static UUID uuid() {
    return UUID.randomUUID();
  }

  /**
   * The duplicate-upload lookup (plan §8.2, {@code S10-19}): a re-upload whose {@code sha256}
   * matches an existing tenant-owned row resolves to that document, so the upload is absorbed with
   * no new row.
   */
  @Test
  void find_by_tenant_and_sha256_returns_the_existing_document() {
    Fixture fixture = tenantWithDocument("dup-hit", "sha256-dup-hit");

    var found = repo.findByTenantAndSha256(fixture.tenantId(), "sha256-dup-hit");

    assertThat(found).isPresent();
    assertThat(found.get().documentId()).isEqualTo(fixture.documentId());
    assertThat(found.get().knowledgeBaseId()).isEqualTo(fixture.knowledgeBaseId());
    assertThat(found.get().status()).isEqualTo(DocumentUploadStatus.UPLOADED);
  }

  /**
   * Invariant 1 at the SQL layer: the {@code tenant_id} predicate is part of the query itself, so
   * another tenant's content-identical payload (the same {@code sha256}) is indistinguishable from
   * a missing one &mdash; the duplicate lookup misses and the upload is a fresh document, never a
   * false hit.
   */
  @Test
  void find_by_tenant_and_sha256_misses_another_tenants_content_identical_payload() {
    Fixture owner = tenantWithDocument("dup-owner", "sha256-shared");
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')", uuid(), "dup-other");
    UUID otherTenant =
        jdbc.queryForObject("SELECT id FROM tenants WHERE slug = ?", UUID.class, "dup-other");

    var found = repo.findByTenantAndSha256(otherTenant, "sha256-shared");

    assertThat(found).isEmpty();
    // The owner's row is untouched by the other tenant's lookup.
    assertThat(repo.findByTenantAndSha256(owner.tenantId(), "sha256-shared")).isPresent();
  }

  /**
   * The retry existence check: the owning tenant resolves the document; a foreign tenant (or a
   * forged id) yields empty, which the manual retry maps to {@code 404}.
   */
  @Test
  void find_by_tenant_and_id_scopes_the_lookup_to_the_owning_tenant() {
    Fixture fixture = tenantWithDocument("retry-scope", "sha256-retry-scope");
    UUID foreignTenant = uuid();

    assertThat(repo.findByTenantAndId(fixture.tenantId(), fixture.documentId())).isPresent();
    assertThat(repo.findByTenantAndId(foreignTenant, fixture.documentId())).isEmpty();
  }

  /**
   * The {@code S10-19} retry edge on a real row: a {@code FAILED} document with a stale diagnostic
   * is released to {@code QUEUED}, the diagnostic is cleared (the next failed pass records a fresh
   * one), and the optimistic {@code version} is bumped like every working transition.
   */
  @Test
  void requeue_failed_to_queued_releases_a_failed_document_and_clears_the_diagnostic() {
    Fixture fixture = tenantWithDocument("retry-release", "sha256-retry-release");
    failWithDiagnostic(fixture.documentId());

    assertThat(repo.requeueFailedToQueued(fixture.tenantId(), fixture.documentId())).isTrue();

    assertThat(statusOf(fixture.documentId())).isEqualTo("QUEUED");
    assertThat(
            jdbc.queryForObject(
                "SELECT error_code FROM documents WHERE id = ?",
                String.class,
                fixture.documentId()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT error_summary FROM documents WHERE id = ?",
                String.class,
                fixture.documentId()))
        .isNull();
    assertThat(versionOf(fixture.documentId())).isEqualTo(3);
  }

  /**
   * The {@code status = 'FAILED'} guard: a document in any other state (here {@code INDEXED}) is
   * not released &mdash; zero rows change, the call reports {@code false} (nothing to retry).
   */
  @Test
  void requeue_failed_to_queued_touches_nothing_when_the_document_is_not_failed() {
    Fixture fixture = tenantWithDocument("retry-guard", "sha256-retry-guard");
    forceStatus(fixture.documentId(), "INDEXED", 1);

    assertThat(repo.requeueFailedToQueued(fixture.tenantId(), fixture.documentId())).isFalse();

    assertThat(statusOf(fixture.documentId())).isEqualTo("INDEXED");
    assertThat(versionOf(fixture.documentId())).isEqualTo(1);
  }

  /**
   * The {@code S10-19} reindex trigger on a real row: only the claiming worker (status {@code
   * PROCESSING}) completes the document, and the completion records the {@code pipeline_version}
   * and {@code page_count} the just-finished pass produced &mdash; so a retuned pipeline (a new
   * {@code pipeline_version} label) is detectable as the reason to reindex.
   */
  @Test
  void mark_indexed_records_the_pipeline_version_and_page_count_guarded_by_processing() {
    Fixture fixture = tenantWithDocument("reindex", "sha256-reindex");
    forceStatus(fixture.documentId(), "PROCESSING", 1);

    assertThat(repo.markIndexed(fixture.tenantId(), fixture.documentId(), 4, "chunking:v2"))
        .isTrue();

    assertThat(statusOf(fixture.documentId())).isEqualTo("INDEXED");
    assertThat(
            jdbc.queryForObject(
                "SELECT pipeline_version FROM documents WHERE id = ?",
                String.class,
                fixture.documentId()))
        .isEqualTo("chunking:v2");
    assertThat(
            jdbc.queryForObject(
                "SELECT page_count FROM documents WHERE id = ?",
                Integer.class,
                fixture.documentId()))
        .isEqualTo(4);
    assertThat(versionOf(fixture.documentId())).isEqualTo(2);
  }

  /**
   * The {@code status = 'PROCESSING'} guard: a document that is not claimed (here still {@code
   * UPLOADED}) is not completed by the call &mdash; zero rows change and the pipeline version is
   * not recorded.
   */
  @Test
  void mark_indexed_touches_nothing_when_the_document_is_not_processing() {
    Fixture fixture = tenantWithDocument("reindex-guard", "sha256-reindex-guard");

    assertThat(repo.markIndexed(fixture.tenantId(), fixture.documentId(), 4, "chunking:v2"))
        .isFalse();

    assertThat(statusOf(fixture.documentId())).isEqualTo("UPLOADED");
    assertThat(
            jdbc.queryForObject(
                "SELECT pipeline_version FROM documents WHERE id = ?",
                String.class,
                fixture.documentId()))
        .isEqualTo("v1");
    assertThat(versionOf(fixture.documentId())).isEqualTo(0);
  }
}
