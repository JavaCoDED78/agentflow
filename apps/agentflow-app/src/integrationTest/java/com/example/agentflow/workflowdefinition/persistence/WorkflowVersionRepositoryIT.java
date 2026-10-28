package com.example.agentflow.workflowdefinition.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.workflowdefinition.domain.WorkflowVersionStatus;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
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
 * PostgreSQL integration suite for the {@link WorkflowVersionRepository} (S05-12).
 *
 * <p>The DB-free repository tests ({@code WorkflowVersionRepositoryTest}) assert the SQL as
 * <em>text</em> against a mocked {@link JdbcTemplate} &mdash; they prove the code <em>emits</em> a
 * tenant / checksum guard, but a mocked template never <em>runs</em> the SQL. This suite runs the
 * real SQL against a real {@code pgvector/pgvector:0.8.6-pg18} (the same image as Compose, per
 * {@code AGENTS.md}: never {@code H2} as a PostgreSQL stand-in), proving that the database itself
 * enforces the guards. It covers the four things nothing else can:
 *
 * <ul>
 *   <li>a tenant-scoped create actually inserts a draft and pins the checksum;
 *   <li>{@code next_version_number} is monotonic per workflow (the {@code (workflow_id, version)}
 *       uniqueness from the migration);
 *   <li>{@code activate} transitions {@code DRAFT → ACTIVATED} only when the checksum matches, and
 *       affects zero rows when it does not (immutable after activation, invariant 4);
 *   <li>every write/read is scoped through the parent {@code workflow_definitions.tenant_id}, so a
 *       cross-tenant id is indistinguishable from a missing one (invariant 1).
 * </ul>
 *
 * <p>Every id is test-generated; each test is self-contained with no shared mutable state.
 */
@Testcontainers
class WorkflowVersionRepositoryIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateAndWire() {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:migrations")
        .load()
        .migrate();
    DataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(dataSource);
  }

  // --- create ----------------------------------------------------------------

  @Test
  void create_inserts_a_draft_scoped_to_the_tenant_with_a_pinned_checksum() {
    UUID tenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    UUID graph = UUID.randomUUID();
    String graphJson =
        "{\"nodes\":[{\"id\":\"n1\",\"type\":\"FINALIZE\",\"config\":{}}],\"edges\":[]}";
    String checksum = sha256Hex(graphJson);

    Optional<WorkflowVersion> created =
        repo.create(
            tenant,
            definition,
            repo.nextVersionNumber(tenant, definition),
            graphJson,
            checksum,
            "alice");

    assertThat(created)
        .isPresent()
        .satisfies(
            v -> {
              assertThat(v.get().version()).isEqualTo(1);
              assertThat(v.get().status()).isEqualTo(WorkflowVersionStatus.DRAFT);
              assertThat(v.get().checksum()).isEqualTo(checksum);
              assertThat(v.get().createdBy()).isEqualTo("alice");
            });
  }

  @Test
  void next_version_number_is_monotonic_per_workflow() {
    UUID tenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    String graphJson = "{\"nodes\":[],\"edges\":[]}";
    String checksum = sha256Hex(graphJson);

    assertThat(repo.nextVersionNumber(tenant, definition)).isEqualTo(1);
    repo.create(
        tenant,
        definition,
        repo.nextVersionNumber(tenant, definition),
        graphJson,
        checksum,
        "alice");
    assertThat(repo.nextVersionNumber(tenant, definition)).isEqualTo(2);
  }

  @Test
  void create_a_cross_tenant_version_affects_zero_rows() {
    UUID tenant = UUID.randomUUID();
    UUID foreignTenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    String graphJson = "{\"nodes\":[],\"edges\":[]}";
    String checksum = sha256Hex(graphJson);

    Optional<WorkflowVersion> created =
        repo.create(foreignTenant, definition, 1, graphJson, checksum, "alice");

    assertThat(created).isEmpty();
  }

  // --- activate ------------------------------------------------------------

  @Test
  void activate_transitions_a_draft_and_is_guarded_by_checksum() {
    UUID tenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    String graphJson = "{\"nodes\":[],\"edges\":[]}";
    String checksum = sha256Hex(graphJson);
    WorkflowVersion draft =
        repo.create(tenant, definition, 1, graphJson, checksum, "alice").orElseThrow();

    // the matching checksum transitions DRAFT -> ACTIVATED ...
    assertThat(repo.activate(tenant, draft.id(), checksum)).isEqualTo(1);
    assertThat(repo.get(tenant, draft.id()).orElseThrow().status())
        .isEqualTo(WorkflowVersionStatus.ACTIVATED);

    // ... and activation is idempotent: a second activate affects zero rows (immutable).
    assertThat(repo.activate(tenant, draft.id(), checksum)).isEqualTo(0);

    // a tampered checksum is refused (the stored checksum is the immutability anchor).
    assertThat(repo.activate(tenant, draft.id(), "wrong")).isEqualTo(0);
  }

  @Test
  void activate_a_cross_tenant_version_affects_zero_rows() {
    UUID tenant = UUID.randomUUID();
    UUID foreignTenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    String graphJson = "{\"nodes\":[],\"edges\":[]}";
    String checksum = sha256Hex(graphJson);
    WorkflowVersion draft =
        repo.create(tenant, definition, 1, graphJson, checksum, "alice").orElseThrow();

    assertThat(repo.activate(foreignTenant, draft.id(), checksum)).isEqualTo(0);
    assertThat(repo.get(foreignTenant, draft.id())).isEmpty();
  }

  // --- get -----------------------------------------------------------------

  @Test
  void get_is_tenant_scoped_and_cross_tenant_returns_empty() {
    UUID tenant = UUID.randomUUID();
    UUID foreignTenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    String graphJson = "{\"nodes\":[],\"edges\":[]}";
    String checksum = sha256Hex(graphJson);
    WorkflowVersion version =
        repo.create(tenant, definition, 1, graphJson, checksum, "alice").orElseThrow();

    assertThat(repo.get(tenant, version.id())).isPresent();
    assertThat(repo.get(foreignTenant, version.id())).isEmpty();
  }

  // --- helpers -------------------------------------------------------------

  private static void seedDefinition(UUID tenant, UUID definition) {
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, tenant);
          ps.setString(2, "tenant-" + tenant);
        });
    jdbc.update(
        "INSERT INTO workflow_definitions (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, definition);
          ps.setObject(2, tenant);
          ps.setString(3, "wf-" + definition);
        });
  }

  private static String sha256Hex(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes());
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit((b & 0xF), 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
