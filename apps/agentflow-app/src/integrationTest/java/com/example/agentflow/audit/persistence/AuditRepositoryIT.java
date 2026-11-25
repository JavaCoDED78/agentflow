package com.example.agentflow.audit.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditEvent;
import com.example.agentflow.audit.domain.AuditResourceType;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL integration suite for the {@code audit_events} table (S14-03, tenant-boundary
 * regression), exercising the real {@link AuditRepository} against a real {@code
 * pgvector/pgvector:0.8.6-pg18} (the same image as Compose, per {@code AGENTS.md}: never {@code H2}
 * as a PostgreSQL stand-in).
 *
 * <p>The repository has no read-side service: the {@code findByTenantAndId} lookup (the row-by-id
 * read) is exercised here at the storage layer, where the acceptance criterion of S14-03 lives — a
 * cross-tenant id is indistinguishable from a missing one. Two assertions pin the masking at the
 * SQL layer: the owner reads their row back with its fields, and a foreign-tenant read throws the
 * same {@link EmptyResultDataAccessException} a nonexistent-id read throws, so the caller can
 * neither read nor distinguish a foreign audit record.
 *
 * <p>Each test seeds its own tenant and rows, so the tests are independent and the suite leaves no
 * inter-test coupling.
 */
@Testcontainers
class AuditRepositoryIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;
  private static AuditRepository repository;

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
    repository = new AuditRepository(jdbc);
  }

  // --- the owning tenant reads its own row back with its fields ------------------------------

  @Test
  void the_owner_reads_its_inserted_row_back_with_the_same_fields() {
    UUID tenant = seedTenant();
    AuditEvent event =
        AuditEvent.created(
            tenant,
            "alice",
            AuditAction.CREATE,
            AuditResourceType.WORKFLOW_DEFINITION,
            UUID.randomUUID());

    UUID id = repository.insert(event);

    AuditEvent found = repository.findByTenantAndId(tenant, id);
    assertThat(found.id()).isEqualTo(id);
    assertThat(found.tenantId()).isEqualTo(tenant);
    assertThat(found.actorSubject()).isEqualTo("alice");
    assertThat(found.action()).isEqualTo(AuditAction.CREATE);
    assertThat(found.resourceType()).isEqualTo(AuditResourceType.WORKFLOW_DEFINITION);
    assertThat(found.resourceId()).isEqualTo(event.resourceId());
  }

  // --- the read side of tenant isolation (invariant 1) ----------------------------------------

  @Test
  void a_foreign_tenant_cannot_read_the_row_indistinguishable_from_missing() {
    UUID tenant = seedTenant();
    AuditEvent event =
        AuditEvent.created(
            tenant,
            "alice",
            AuditAction.CREATE,
            AuditResourceType.WORKFLOW_DEFINITION,
            UUID.randomUUID());
    UUID id = repository.insert(event);

    // A foreign tenant's read misses exactly like a nonexistent id: the same exception, the same
    // indistinguishability (the 403/404 masking policy, invariant 1). The row stays with its owner.
    assertThatThrownBy(() -> repository.findByTenantAndId(UUID.randomUUID(), id))
        .isInstanceOf(EmptyResultDataAccessException.class);
    assertThatThrownBy(() -> repository.findByTenantAndId(tenant, UUID.randomUUID()))
        .isInstanceOf(EmptyResultDataAccessException.class);

    assertThat(repository.findByTenantAndId(tenant, id).id()).isEqualTo(id);
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** Seeds one tenant (the FK target of {@code audit_events.tenant_id}) and returns its id. */
  private static UUID seedTenant() {
    UUID tenant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, tenant);
          ps.setString(2, "tenant-" + tenant);
        });
    return tenant;
  }
}
