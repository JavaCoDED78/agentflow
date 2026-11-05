package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.persistence.AuditRepository;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.messaging.persistence.WorkflowEventRepository;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import com.example.agentflow.workflowdefinition.persistence.WorkflowVersionRepository;
import com.example.agentflow.workflowexecution.domain.StepHandler;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.CheckpointSnapshot;
import com.example.agentflow.workflowexecution.persistence.RunView;
import com.example.agentflow.workflowexecution.persistence.StepStatusRow;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end (real PostgreSQL) verification of the S07-08 worker event loop.
 *
 * <p>The acceptance criterion for {@code S07-08} is that <em>a deterministic 2-3 step workflow
 * completes and its status is available</em>. This test boots a real PostgreSQL instance with the
 * project's own Flyway migrations applied, wires the real repository / state-machine / lease /
 * checkpoint beans, builds the {@link WorkflowWorker} with the MVP {@link DeterministicStepHandler}
 * and drives a deterministic two-step {@code a -> b} workflow through a single {@link
 * WorkflowWorker#pollOnce} call.
 *
 * <p>This is a <strong>controlled standalone</strong> test: instead of booting the full Spring
 * context (which pulls in the {@code messaging} outbox / inbox beans that the skeleton wires only
 * when external {@code spring.datasource.*} is supplied), it starts one Testcontainers PostgreSQL
 * container, applies the migrations and constructs the handful of beans the worker path actually
 * touches. That keeps the test deterministic and focused on the claim &rarr; load-graph &rarr;
 * schedule &rarr; execute &rarr; checkpoint &rarr; transition &rarr; reconcile loop over real SQL
 * with real lease/fencing, without depending on Kafka, Keycloak or the application's config
 * binding.
 *
 * <p>The graph is stored as a {@code DRAFT} version directly (the {@code NOOP} demo node type is
 * not in the closed validator set, which is {@code S05-07}'s concern, not S07-08's), then activated
 * through {@code versionRepository.activate}, which re-checks the pinned checksum and the tenant
 * through the parent definition.
 */
class WorkflowWorkerEndToEndIT {

  /** The Testcontainers image matches the integration-suite convention (pgvector on PG 18). */
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  // --- beans wired once against the real container, in {@link #startDatabaseAndWireBeans()} -----
  private static AgentFlowProperties properties;
  private static JdbcTemplate jdbc;
  private static WorkflowDefinitionRepository definitionRepository;
  private static WorkflowVersionRepository versionRepository;
  private static WorkflowRunRepository runRepository;
  private static WorkflowStepRunRepository stepRepository;
  private static RunStateMachineService transitionService;
  private static RunLeaseService leaseService;
  private static CheckpointAdapter checkpointAdapter;
  private static WorkflowVersionService versionService;
  private static WorkflowWorker worker;

  @BeforeAll
  static void startDatabaseAndWireBeans() {
    POSTGRES.start();

    // The skeleton disables startup migration (spring.flyway.enabled=false); apply the project's
    // migrations against the container explicitly, before any bean touches the database.
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:migrations")
        .load()
        .migrate();

    // Fresh defaults: nested Worker/Lease/Graph settings carry their built-in defaults; appName is
    // intentionally left null (only Spring's config binding is required to supply it in
    // production).
    properties = new AgentFlowProperties();

    jdbc = new JdbcTemplate(dataSource());

    WorkflowDefinitionRepository definitionRepo = new WorkflowDefinitionRepository(jdbc);
    WorkflowVersionRepository versionRepo = new WorkflowVersionRepository(jdbc);
    WorkflowRunRepository runRepo = new WorkflowRunRepository(jdbc);
    WorkflowStepRunRepository stepRepo = new WorkflowStepRunRepository(jdbc);
    WorkflowEventRepository eventRepo = new WorkflowEventRepository(jdbc);
    AuditRepository auditRepo = new AuditRepository(jdbc);

    // Expose the repositories to the test (the services below capture their own copies).
    definitionRepository = definitionRepo;
    versionRepository = versionRepo;
    runRepository = runRepo;
    stepRepository = stepRepo;

    AuditService auditService = new AuditService(auditRepo);
    WorkflowEventService eventService = new WorkflowEventService(eventRepo);
    versionService =
        new WorkflowVersionService(definitionRepo, versionRepo, auditService, properties);

    transitionService = new RunStateMachineService(runRepo, stepRepo, null, eventService);
    leaseService = new RunLeaseService(runRepo, Clock.systemUTC(), properties);
    RunHeartbeatService heartbeat = new RunHeartbeatService(runRepo, Clock.systemUTC(), properties);
    checkpointAdapter = new CheckpointAdapter(stepRepo, properties);

    // Build the worker with the MVP handler so the loop executes the deterministic NOOP graph.
    worker =
        new WorkflowWorker(
            runRepo,
            stepRepo,
            versionService,
            leaseService,
            heartbeat,
            transitionService,
            checkpointAdapter,
            List.<StepHandler>of(new DeterministicStepHandler()),
            properties);
  }

  @AfterAll
  static void stopDatabase() {
    if (POSTGRES != null && POSTGRES.isRunning()) {
      POSTGRES.stop();
    }
  }

  /**
   * Builds a {@link DataSource} bound to the Testcontainers Postgres. The container is started in
   * {@link #startDatabaseAndWireBeans()} before this is called, so the mapped port is available.
   */
  private static DataSource dataSource() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }

  @Test
  void a_deterministic_two_step_workflow_completes_end_to_end() {
    UUID tenant = UUID.randomUUID();
    UUID workflowId;
    UUID runId = UUID.randomUUID();

    // 0. Register the tenant — workflow_definitions.tenant_id references it (invariant 1). Bound as
    //    text and cast to uuid so the insert is independent of the driver's UUID parameter binding.
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES ((?)::uuid, ?, ?)",
        tenant.toString(),
        tenant.toString(),
        "ACTIVE");

    // 1. Create the parent definition (the version insert is guarded on its existence). The version
    //    references this definition by its own id, so that id is the workflow id used below.
    WorkflowDefinition definition =
        definitionRepository.insert(
            WorkflowDefinition.draft(
                tenant, "e2e-deterministic", "E2E deterministic workflow", "e2e@agentflow"));
    workflowId = definition.id();

    // 2. Store a deterministic (NOOP) graph version directly and activate it.
    String graphJson =
        "{\"schemaVersion\":\"1.0.0\",\"nodes\":["
            + "{\"id\":\"a\",\"type\":\"NOOP\",\"config\":{\"result\":\"a-done\"}},"
            + "{\"id\":\"b\",\"type\":\"NOOP\",\"config\":{\"result\":\"b-done\"}}],"
            + "\"edges\":[{\"from\":\"a\",\"to\":\"b\"}]}";
    String checksum = sha256Hex(graphJson);
    UUID versionId =
        versionRepository
            .create(tenant, workflowId, 1, graphJson, checksum, "e2e@agentflow")
            .orElseThrow()
            .id();
    versionRepository.activate(tenant, versionId, checksum);

    // 3. Create a QUEUED run pinned to that version.
    runRepository.create(runId, tenant, versionId, null, null, "e2e@agentflow");

    // 4. Drive the run through exactly one worker poll.
    int processed = worker.pollOnce(tenant);

    // 5. Assert the run completed and both steps reached SUCCEEDED.
    assertThat(processed).isEqualTo(1);
    RunView view = runRepository.findRun(tenant, runId).orElseThrow();
    assertThat(view.status()).isEqualTo(WorkflowRunState.SUCCEEDED);

    List<StepStatusRow> steps = stepRepository.findStepStatuses(tenant, runId);
    assertThat(steps).extracting("stepKey").containsExactlyInAnyOrder("a", "b");
    assertThat(steps)
        .extracting("status")
        .containsExactlyInAnyOrder(WorkflowStepState.SUCCEEDED, WorkflowStepState.SUCCEEDED);

    // 6. Assert a redacted checkpoint was persisted per executed step.
    assertThat(stepRepository.findCheckpoint(tenant, runId, "a"))
        .map(CheckpointSnapshot::outputInline)
        .hasValue("a-done");
    assertThat(stepRepository.findCheckpoint(tenant, runId, "b"))
        .map(CheckpointSnapshot::outputInline)
        .hasValue("b-done");
  }

  /** SHA-256 of the stored graph JSON text, hex-encoded &mdash; mirrors the version checksum. */
  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the version checksum", e);
    }
  }
}
