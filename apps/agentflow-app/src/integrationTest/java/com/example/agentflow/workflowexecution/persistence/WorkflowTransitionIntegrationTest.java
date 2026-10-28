package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.messaging.persistence.WorkflowEventRepository;
import com.example.agentflow.workflowexecution.application.RunStateMachineService;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL integration suite for the conditional-update / optimistic-lock path that the run /
 * step transition service (S05-11) depends on.
 *
 * <p>The DB-free repository tests ({@code WorkflowRunRepositoryTest}, {@code
 * WorkflowStepRunRepositoryTest}) assert the SQL as <em>text</em> against a mocked {@code
 * JdbcTemplate} &mdash; they prove the code <em>emits</em> a tenant / status / version (or {@code
 * lease_epoch}) predicate, but a mocked template never <em>runs</em> the SQL, so a wrong binding
 * order or a missing {@code WHERE} column would slip through. This suite runs the real SQL against
 * a real {@code pgvector/pgvector:0.8.6-pg18} (the same image as Compose, per {@code AGENTS.md}:
 * never {@code H2} as a PostgreSQL stand-in), proving that the database itself enforces the guards.
 * It covers the four things nothing else can:
 *
 * <ul>
 *   <li>the {@code tenant_id} filter actually refuses a foreign-tenant write (invariant 1);
 *   <li>the optimistic {@code version} / {@code lease_epoch} fence actually refuses a stale writer
 *       &mdash; two competing updates never silently overwrite each other (invariant 4);
 *   <li>the step's {@code JOIN workflow_runs} actually scopes the write to the parent's tenant;
 *   <li>the {@code workflowexecution} transition service appends exactly one {@code
 *       workflow_events} row on success and none on conflict.
 * </ul>
 *
 * <p>Every row is created with test-generated UUIDs (each test is self-contained, no shared mutable
 * state), and the {@link JdbcTemplate} binds parameters with real prepared statements rather than
 * literal interpolation.
 */
@Testcontainers
class WorkflowTransitionIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateAndWire() throws SQLException {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:migrations")
        .load()
        .migrate();
    DataSource dataSource =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(dataSource);
  }

  // --- run repository (WorkflowRunRepository) ----------------------------------------------------

  /**
   * A valid run transition commits: the row moves and the optimistic lock is bumped inside one
   * statement.
   */
  @Test
  void run_transition_succeeds_and_bumps_the_optimistic_version() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    seedRun(tenant, runId);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);

    repo.transition(runId, tenant, WorkflowRunState.QUEUED, 0, WorkflowRunState.RUNNING);

    RunStateSnapshot after = repo.findState(tenant, runId).orElseThrow();
    assertThat(after.status()).isEqualTo(WorkflowRunState.RUNNING);
    assertThat(after.version()).isEqualTo(1);
  }

  /**
   * A stale optimistic version &mdash; another writer committed between the read and the write
   * &mdash; affects no row and must not silently overwrite the current state (invariant 4).
   */
  @Test
  void run_transition_with_a_stale_version_is_a_conflict() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    seedRun(tenant, runId);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);

    // Simulate a competing writer having committed first: bump the version out from under the
    // reader.
    jdbc.update("UPDATE workflow_runs SET version = version + 1 WHERE id = ?", runId);

    assertThatThrownBy(
            () ->
                repo.transition(
                    runId, tenant, WorkflowRunState.QUEUED, 0, WorkflowRunState.RUNNING))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("out of date");
    // The row is left exactly as it was: the stale writer is refused, not silently overwritten.
    assertThat(repo.findState(tenant, runId).orElseThrow().status())
        .isEqualTo(WorkflowRunState.QUEUED);
    assertThat(repo.findState(tenant, runId).orElseThrow().version()).isEqualTo(1);
  }

  /**
   * A write scoped to the wrong tenant affects no row (the {@code tenant_id} predicate is in the
   * {@code WHERE}, so a foreign id is indistinguishable from a missing row, invariant 1).
   */
  @Test
  void run_transition_from_the_wrong_tenant_affects_zero_rows() {
    UUID tenant = UUID.randomUUID();
    UUID foreignTenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    seedRun(tenant, runId);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);

    assertThatThrownBy(
            () ->
                repo.transition(
                    runId, foreignTenant, WorkflowRunState.QUEUED, 0, WorkflowRunState.RUNNING))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("out of date");
    assertThat(repo.findState(tenant, runId).orElseThrow().status())
        .isEqualTo(WorkflowRunState.QUEUED);
  }

  /**
   * A conditional update whose {@code status} no longer matches the domain gate's {@code from}
   * state affects no row (invariant 4).
   */
  @Test
  void run_transition_with_a_mismatched_state_is_a_conflict() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    seedRun(tenant, runId);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);

    // The run is QUEUED, but the caller still believes it is RUNNING: the guard rejects the write.
    assertThatThrownBy(
            () ->
                repo.transition(
                    runId, tenant, WorkflowRunState.RUNNING, 0, WorkflowRunState.SUCCEEDED))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("no longer in state");
    assertThat(repo.findState(tenant, runId).orElseThrow().status())
        .isEqualTo(WorkflowRunState.QUEUED);
  }

  // --- step repository (WorkflowStepRunRepository)
  // ------------------------------------------------

  /** A valid step transition commits through the parent-run join. */
  @Test
  void step_transition_succeeds() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID stepId = UUID.randomUUID();
    seedRun(tenant, runId);
    seedStep(runId, stepId, "llm_plan");
    WorkflowStepRunRepository repo = new WorkflowStepRunRepository(jdbc);

    repo.transition(
        runId, tenant, "llm_plan", WorkflowStepState.PENDING, 0, WorkflowStepState.RUNNING);

    StepStateSnapshot after = repo.findState(tenant, runId, "llm_plan").orElseThrow();
    assertThat(after.status()).isEqualTo(WorkflowStepState.RUNNING);
  }

  /**
   * A step whose parent run belongs to another tenant is fenced out through the {@code JOIN} {@code
   * WHERE} (invariant 1).
   */
  @Test
  void step_transition_from_the_wrong_tenant_affects_zero_rows() {
    UUID tenant = UUID.randomUUID();
    UUID foreignTenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID stepId = UUID.randomUUID();
    seedRun(tenant, runId);
    seedStep(runId, stepId, "llm_plan");
    WorkflowStepRunRepository repo = new WorkflowStepRunRepository(jdbc);

    assertThatThrownBy(
            () ->
                repo.transition(
                    runId,
                    foreignTenant,
                    "llm_plan",
                    WorkflowStepState.PENDING,
                    0,
                    WorkflowStepState.RUNNING))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("lease epoch");
    assertThat(repo.findState(tenant, runId, "llm_plan").orElseThrow().status())
        .isEqualTo(WorkflowStepState.PENDING);
  }

  /**
   * A stale {@code lease_epoch} &mdash; another worker renewed the lease &mdash; affects no row
   * (the step fence, invariant 4).
   */
  @Test
  void step_transition_with_a_stale_lease_epoch_is_a_conflict() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID stepId = UUID.randomUUID();
    seedRun(tenant, runId);
    seedStep(runId, stepId, "llm_plan");
    WorkflowStepRunRepository repo = new WorkflowStepRunRepository(jdbc);

    // Simulate another worker renewing the lease between the read and the write.
    jdbc.update("UPDATE workflow_step_runs SET lease_epoch = lease_epoch + 1 WHERE id = ?", stepId);

    assertThatThrownBy(
            () ->
                repo.transition(
                    runId,
                    tenant,
                    "llm_plan",
                    WorkflowStepState.PENDING,
                    0,
                    WorkflowStepState.RUNNING))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("lease epoch");
    assertThat(repo.findState(tenant, runId, "llm_plan").orElseThrow().status())
        .isEqualTo(WorkflowStepState.PENDING);
  }

  // --- transition service (RunStateMachineService) against real repositories
  // ----------------------

  /**
   * The single transition service appends exactly one {@code workflow_events} row on success and
   * leaves the history empty when a transition is refused. A refused transition (the domain gate
   * rejects {@code from -> to}, so no write happens) must not append a divergent history row
   * (invariant 3). A service-level conflict (409) cannot be produced deterministically here: the
   * service reads the current optimistic version and writes it back, so a pre-read bump is absorbed
   * by the read. That path is covered by {@code RunStateMachineServiceTest} (mocked repository) and
   * by the repository integration suite (real conditional update).
   */
  @Test
  void service_appends_one_event_on_success_and_none_when_a_transition_is_forbidden() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    seedRun(tenant, runId);

    WorkflowRunRepository runRepo = new WorkflowRunRepository(jdbc);
    WorkflowEventService events = new WorkflowEventService(new WorkflowEventRepository(jdbc));
    RunStateMachineService service = new RunStateMachineService(runRepo, null, events);

    // Refused: the run is QUEUED and QUEUED -> SUCCEEDED has no edge, so the domain gate refuses
    // (400) before any write and no history row is appended.
    assertThatThrownBy(
            () ->
                service.transitionRun(
                    tenant, runId, WorkflowRunState.SUCCEEDED, "run.succeeded", "worker"))
        .extracting(WorkflowTransitionIntegrationTest::statusOf)
        .isEqualTo(400);
    // No event row was appended for the refused transition.
    assertThat(events.historyByRun(tenant, runId)).isEmpty();

    // Happy path: the run moves QUEUED -> RUNNING and exactly one fact is recorded.
    service.transitionRun(tenant, runId, WorkflowRunState.RUNNING, "run.started", "worker");
    assertThat(events.historyByRun(tenant, runId)).hasSize(1);
    assertThat(events.historyByRun(tenant, runId).get(0).eventType()).isEqualTo("run.started");
  }

  /**
   * A cross-tenant id through the service is indistinguishable from a missing run (invariant 1).
   */
  @Test
  void service_returns_404_for_a_cross_tenant_run() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    seedRun(tenant, runId);
    WorkflowRunRepository runRepo = new WorkflowRunRepository(jdbc);
    WorkflowEventService events = new WorkflowEventService(new WorkflowEventRepository(jdbc));
    RunStateMachineService service = new RunStateMachineService(runRepo, null, events);

    assertThatThrownBy(
            () ->
                service.transitionRun(
                    UUID.randomUUID(), runId, WorkflowRunState.RUNNING, "run.started", "worker"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .extracting(WorkflowTransitionIntegrationTest::statusOf)
        .isEqualTo(404);
  }

  /**
   * The HTTP status a {@code ResponseStatusException} carries, read without importing {@code
   * HttpStatus} (spring-web is on the classpath, spring-core's {@code HttpStatus} is not on the
   * integrationTest compile path).
   */
  private static int statusOf(Throwable t) {
    var e = (org.springframework.web.server.ResponseStatusException) t;
    return e.getStatusCode().value();
  }

  // --- seeding helpers
  // ----------------------------------------------------------------------------

  private static void seedRun(UUID tenant, UUID runId) {
    UUID definitionId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    insertTenant(tenant, "tenant-" + tenant);
    jdbc.update(
        "INSERT INTO workflow_definitions (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, definitionId);
          ps.setObject(2, tenant);
          ps.setString(3, "wf-" + runId);
        });
    jdbc.update(
        "INSERT INTO workflow_definition_versions (id, workflow_id, version, graph_json, checksum) VALUES (?, ?, 1, ?::jsonb, ?)",
        ps -> {
          ps.setObject(1, versionId);
          ps.setObject(2, definitionId);
          ps.setString(3, "{\"nodes\":[]}");
          ps.setString(4, "c");
        });
    jdbc.update(
        "INSERT INTO workflow_runs (id, tenant_id, workflow_version_id, status) VALUES (?, ?, ?, 'QUEUED')",
        ps -> {
          ps.setObject(1, runId);
          ps.setObject(2, tenant);
          ps.setObject(3, versionId);
        });
  }

  private static void seedStep(UUID runId, UUID stepId, String stepKey) {
    jdbc.update(
        "INSERT INTO workflow_step_runs (id, run_id, step_key, status) VALUES (?, ?, ?, 'PENDING')",
        ps -> {
          ps.setObject(1, stepId);
          ps.setObject(2, runId);
          ps.setString(3, stepKey);
        });
  }

  private static void insertTenant(UUID tenantId, String slug) {
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, slug);
        });
  }
}
