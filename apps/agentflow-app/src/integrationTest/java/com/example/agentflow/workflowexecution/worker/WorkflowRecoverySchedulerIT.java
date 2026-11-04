package com.example.agentflow.workflowexecution.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.application.RunLeaseService;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end recovery tests for {@link WorkflowRecoveryScheduler} (S07-09) over a real {@code
 * pgvector/pgvector:0.8.6-pg18} database (the same image as Compose, per {@code AGENTS.md}: never
 * {@code H2} as a PostgreSQL stand-in). The scan, the atomic re-claim, the terminal-skip, the
 * concurrency bound and the {@code recoveries} counter are all exercised against a real {@link
 * WorkflowRunRepository} over real rows.
 *
 * <p>The scan ({@link WorkflowRunRepository#findExpiredLeaseRuns}) is a platform-wide lease scan,
 * so the tests isolate each run by seeding it in its own tenant and deleting that tenant's rows in
 * {@link #cleanUpCurrentTenant()}. Without the cleanup a later test's scan would pick up the
 * expired runs a prior test left behind and the per-run assertions would be off.
 *
 * <p>The scan re-derives the current owner from PostgreSQL on every pass (ADR-011), so the test
 * seeds the stale lease directly (a {@code lease_owner} set with a {@code lease_until} in the past,
 * per a worker that has since crashed) and asserts the recovery re-claims it — bumps the epoch so
 * the dead owner is fenced (invariant 5) and resets the lease (invariant 2: a stale run is paused,
 * never lost).
 */
@Testcontainers
class WorkflowRecoverySchedulerIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;
  private static AgentFlowProperties properties;

  // The tenant created by the test currently running; the platform-wide scan sees every tenant, so
  // this is what {@link #cleanUpCurrentTenant()} deletes to keep the scan isolated between tests.
  private UUID currentTenant;

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
    properties = new AgentFlowProperties();
  }

  @BeforeEach
  void resetTenant() {
    currentTenant = null;
  }

  @AfterEach
  void cleanUpCurrentTenant() {
    if (currentTenant == null) {
      return;
    }
    // FK-reverse order: runs -> versions -> definitions -> tenant.
    jdbc.update("DELETE FROM workflow_runs WHERE tenant_id = ?", currentTenant);
    jdbc.update(
        "DELETE FROM workflow_definition_versions "
            + "WHERE workflow_id IN (SELECT id FROM workflow_definitions WHERE tenant_id = ?)",
        currentTenant);
    jdbc.update("DELETE FROM workflow_definitions WHERE tenant_id = ?", currentTenant);
    jdbc.update("DELETE FROM tenants WHERE id = ?", currentTenant);
  }

  @Test
  void expired_lease_run_is_reclaimed_by_the_recovery_and_the_counter_increments() {
    properties.getWorker().setMaxConcurrentRuns(8);
    UUID version = seedTenant();
    UUID run = UUID.randomUUID();
    UUID deadOwner = UUID.randomUUID();
    seedExpiredRun(currentTenant, run, deadOwner, 5, version);

    MeterRegistry registry = new SimpleMeterRegistry();
    WorkflowRecoveryScheduler scheduler = schedulerWith(registry);

    int reclaimed = scheduler.recoverPass();

    assertThat(reclaimed).isOne();
    // The re-claim is tenant scoped and bumps the epoch (invariant 5 — the dead owner is fenced).
    LeaseState lease = readLease(currentTenant, run);
    assertThat(lease.owner()).isNotEqualTo(deadOwner);
    assertThat(lease.owner()).isNotNull();
    assertThat(lease.epoch()).isEqualTo(6);
    assertThat(lease.until()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    // One increment per reclaimed run (invariant 9 — no tenant / run label).
    assertThat(registry.find(WorkflowRecoveryScheduler.RECOVERIES_METRIC).counter().count())
        .isOne();
  }

  @Test
  void terminal_run_is_not_reclaimed_even_with_an_expired_lease() {
    properties.getWorker().setMaxConcurrentRuns(8);
    UUID version = seedTenant();
    UUID run = UUID.randomUUID();
    seedExpiredRun(currentTenant, run, UUID.randomUUID(), 5, version);
    // mark the run terminal — the scan must skip it (invariant 2)
    jdbc.update(
        "UPDATE workflow_runs SET status = 'SUCCEEDED' WHERE id = ? AND tenant_id = ?",
        ps -> {
          ps.setObject(1, run);
          ps.setObject(2, currentTenant);
        });

    MeterRegistry registry = new SimpleMeterRegistry();
    WorkflowRecoveryScheduler scheduler = schedulerWith(registry);

    int reclaimed = scheduler.recoverPass();

    assertThat(reclaimed).isZero();
    assertThat(registry.find(WorkflowRecoveryScheduler.RECOVERIES_METRIC).counter().count())
        .isZero();
  }

  @Test
  void the_concurrency_limit_bounds_the_scan() {
    int limit = 3;
    properties.getWorker().setMaxConcurrentRuns(limit);
    // seed ten expired runs in one tenant — so cleanup removes all of them
    UUID version = seedTenant();
    for (int i = 0; i < 10; i++) {
      seedExpiredRun(currentTenant, UUID.randomUUID(), UUID.randomUUID(), 5, version);
    }

    MeterRegistry registry = new SimpleMeterRegistry();
    WorkflowRecoveryScheduler scheduler = schedulerWith(registry);

    int reclaimed = scheduler.recoverPass();

    // The pass re-claims at most `limit` runs, so it never takes ownership of the whole table in
    // one scan (ADR-011 operational consequence).
    assertThat(reclaimed).isEqualTo(limit);
    assertThat(registry.find(WorkflowRecoveryScheduler.RECOVERIES_METRIC).counter().count())
        .isEqualTo((double) limit);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private WorkflowRecoveryScheduler schedulerWith(MeterRegistry registry) {
    WorkflowRunRepository runRepository = new WorkflowRunRepository(jdbc);
    RunLeaseService leaseService =
        new RunLeaseService(runRepository, Clock.systemUTC(), properties);
    return new WorkflowRecoveryScheduler(runRepository, leaseService, properties, registry);
  }

  /**
   * Seed a fresh tenant with one definition and one version, recording the tenant for {@link
   * #cleanUpCurrentTenant()}.
   *
   * @return the version id the caller passes to {@link #seedExpiredRun}
   */
  private UUID seedTenant() {
    UUID tenant = UUID.randomUUID();
    UUID definitionId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    this.currentTenant = tenant;
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, tenant);
          ps.setString(2, "tenant-" + tenant);
        });
    jdbc.update(
        "INSERT INTO workflow_definitions (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, definitionId);
          ps.setObject(2, tenant);
          ps.setString(3, "wf");
        });
    jdbc.update(
        "INSERT INTO workflow_definition_versions (id, workflow_id, version, graph_json, checksum) VALUES (?, ?, 1, ?::jsonb, ?)",
        ps -> {
          ps.setObject(1, versionId);
          ps.setObject(2, definitionId);
          ps.setString(3, "{\"nodes\":[]}");
          ps.setString(4, "c");
        });
    return versionId;
  }

  /**
   * Seed one running run in {@code tenant} (referencing an existing {@code versionId}) with an
   * expired run-level lease owned by {@code deadOwner} at {@code epoch} — the state of a run whose
   * worker has crashed (S07-09, ADR-011).
   */
  private void seedExpiredRun(UUID tenant, UUID run, UUID deadOwner, int epoch, UUID versionId) {
    this.currentTenant = tenant;
    jdbc.update(
        "INSERT INTO workflow_runs (id, tenant_id, workflow_version_id, status) VALUES (?, ?, ?, 'RUNNING')",
        ps -> {
          ps.setObject(1, run);
          ps.setObject(2, tenant);
          ps.setObject(3, versionId);
        });
    jdbc.update(
        "UPDATE workflow_runs SET lease_owner = ?, lease_until = ?, lease_epoch = ? WHERE id = ? AND tenant_id = ?",
        ps -> {
          ps.setObject(1, deadOwner);
          ps.setObject(2, until(-30));
          ps.setObject(3, epoch);
          ps.setObject(4, run);
          ps.setObject(5, tenant);
        });
  }

  private static OffsetDateTime until(int deltaSeconds) {
    return OffsetDateTime.now(ZoneOffset.UTC).withNano(0).plusSeconds(deltaSeconds);
  }

  private static LeaseState readLease(UUID tenant, UUID run) {
    return jdbc.query(
            "SELECT lease_owner, lease_until, lease_epoch FROM workflow_runs "
                + "WHERE id = ? AND tenant_id = ?",
            (rs, rowNum) ->
                new LeaseState(
                    (UUID) rs.getObject("lease_owner"),
                    rs.getObject("lease_until", OffsetDateTime.class),
                    rs.getInt("lease_epoch")),
            run,
            tenant)
        .get(0);
  }

  private record LeaseState(UUID owner, OffsetDateTime until, int epoch) {}
}
