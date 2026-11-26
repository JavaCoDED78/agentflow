package com.example.agentflow.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
 * PostgreSQL integration suite for the {@code tenants} read path (S04-19, tenant-boundary
 * regression), exercising the real {@link TenantRepository} against a real {@code
 * pgvector/pgvector:0.8.6-pg18} (the same image as Compose, per {@code AGENTS.md}: never {@code H2}
 * as a PostgreSQL stand-in).
 *
 * <p>The resolver's contract (S04-19) lives at the application layer; this suite proves the storage
 * half of it at the SQL layer: the repository reads the tenant's {@code status} back for a live
 * row, reports a non-ACTIVE status back as-is (the resolver decides what is refused), and answers
 * {@code Optional.empty()} for a tenant id with no row — the three outcomes the resolver must tell
 * apart without ever leaking the distinction in the response.
 *
 * <p>Each test seeds its own tenant rows, so the suite is independent of any prior state and leaves
 * no inter-test coupling.
 */
@Testcontainers
class TenantRepositoryIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;
  private static TenantRepository repository;

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
    repository = new TenantRepository(jdbc);
  }

  @Test
  void the_active_tenant_reads_back_its_status() {
    UUID tenant = seedTenant("ACTIVE");

    assertThat(repository.findStatus(tenant)).contains("ACTIVE");
  }

  @Test
  void the_disabled_tenant_reads_back_its_status_untouched() {
    UUID tenant = seedTenant("DISABLED");

    assertThat(repository.findStatus(tenant)).contains("DISABLED");
  }

  @Test
  void a_tenant_id_with_no_row_answers_empty() {
    assertThat(repository.findStatus(UUID.randomUUID())).isEmpty();
  }

  /** Seeds one tenant with the given status and returns its id. */
  private static UUID seedTenant(String status) {
    UUID tenant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, ?)",
        ps -> {
          ps.setObject(1, tenant);
          ps.setString(2, "tenant-" + tenant);
          ps.setString(3, status);
        });
    return tenant;
  }
}
