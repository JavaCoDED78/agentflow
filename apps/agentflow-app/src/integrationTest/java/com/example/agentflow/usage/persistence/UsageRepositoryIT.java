package com.example.agentflow.usage.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.usage.domain.Measurement;
import com.example.agentflow.usage.domain.UsageRecordDto;
import com.example.agentflow.usage.domain.UsageType;
import java.math.BigDecimal;
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
 * PostgreSQL integration suite for the {@code usage_records} table (S14-03, tenant-boundary
 * regression), exercising the real {@link UsageRepository} against a real {@code
 * pgvector/pgvector:0.8.6-pg18} (the same image as Compose, per {@code AGENTS.md}: never {@code H2}
 * as a PostgreSQL stand-in).
 *
 * <p>The {@code findByTenantAndRun} read (the usage-by-run investigation path) is exercised at the
 * storage layer: the owning tenant reads its run's measurements back with the same values, and a
 * foreign tenant's read returns the same empty list as a missing run — indistinguishable from a
 * missing one (the 403/404 masking policy, invariant 1). Each test seeds its own tenant and rows,
 * so the tests are independent.
 */
@Testcontainers
class UsageRepositoryIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;
  private static UsageRepository repository;

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
    repository = new UsageRepository(jdbc);
  }

  // --- the owning tenant reads its run's measurements back with the same values --------------

  @Test
  void the_owner_reads_its_runs_measurements_back_with_the_same_values() {
    UUID tenant = seedTenant();
    UUID run = UUID.randomUUID();
    repository.insert(measurement(tenant, run, "step-1", 1));

    var found = repository.findByTenantAndRun(tenant, run);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).tenantId()).isEqualTo(tenant);
    assertThat(found.get(0).runId()).isEqualTo(run);
    assertThat(found.get(0).stepRunId()).isEqualTo("step-1");
    assertThat(found.get(0).attempt()).isEqualTo(1);
    assertThat(found.get(0).usageType()).isEqualTo(UsageType.MODEL);
    assertThat(found.get(0).measurement()).isEqualTo(Measurement.ESTIMATED);
    assertThat(found.get(0).model()).isEqualTo("gpt-4o-mini");
    assertThat(found.get(0).inputTokens()).isEqualTo(100L);
    assertThat(found.get(0).outputTokens()).isEqualTo(50L);
    assertThat(found.get(0).estimatedCost()).isEqualByComparingTo(new BigDecimal("0.0010"));
  }

  // --- the read side of tenant isolation (invariant 1) ----------------------------------------

  @Test
  void a_foreign_tenant_cannot_read_the_runs_measurements_indistinguishable_from_missing() {
    UUID tenant = seedTenant();
    UUID run = UUID.randomUUID();
    repository.insert(measurement(tenant, run, "step-1", 1));

    // A foreign tenant's read returns no rows — indistinguishable from a missing run (the 403/404
    // masking policy, invariant 1): the tenant predicate is part of the WHERE clause, never a
    // post-filter.
    assertThat(repository.findByTenantAndRun(UUID.randomUUID(), run)).isEmpty();
    // a missing run for the owner's tenant is the same empty result
    assertThat(repository.findByTenantAndRun(tenant, UUID.randomUUID())).isEmpty();

    // the row stays with its owner
    assertThat(repository.findByTenantAndRun(tenant, run)).hasSize(1);
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** Seeds one tenant (the FK target of {@code usage_records.tenant_id}) and returns its id. */
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

  /** A one-attempt {@link UsageType#MODEL} measurement for the given (tenant, run). */
  private static UsageRecordDto measurement(UUID tenant, UUID run, String stepRunId, int attempt) {
    return new UsageRecordDto(
        UUID.randomUUID(),
        tenant,
        run,
        stepRunId,
        attempt,
        UsageType.MODEL,
        Measurement.ESTIMATED,
        "fake",
        "gpt-4o-mini",
        null,
        null,
        100L,
        50L,
        new BigDecimal("0.0010"),
        null,
        "token",
        "USD",
        null,
        null);
  }
}
