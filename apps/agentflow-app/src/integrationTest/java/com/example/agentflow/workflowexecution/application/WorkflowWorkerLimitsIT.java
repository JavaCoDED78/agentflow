package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.persistence.AuditRepository;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.messaging.domain.WorkflowEvent;
import com.example.agentflow.messaging.persistence.WorkflowEventRepository;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import com.example.agentflow.workflowdefinition.persistence.WorkflowVersionRepository;
import com.example.agentflow.workflowexecution.domain.StepHandler;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
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
 * Integration test for the per-run hard execution limits (S11-13) over <strong>real
 * PostgreSQL</strong>.
 *
 * <p>The S11-13 acceptance criterion is that <em>every per-run hard execution limit breach ends in
 * a documented, controlled {@code FAILED} run whose {@code run.limit-exceeded} event is
 * persisted</em>. The DB-free {@link WorkflowWorkerLimitsTest} proves the worker <em>says</em>
 * that; this test reads the outcome <em>back from the database</em>: the {@code
 * workflow_runs.status} row and the {@code workflow_events} history the state machine appended in
 * the same transaction as the run transition (invariant 3). That is the strictest closure of the
 * criterion — it verifies the documented state and event are durable, not merely emitted into the
 * air.
 *
 * <p>Like {@link WorkflowWorkerEndToEndIT}, this is a controlled standalone: one Testcontainers
 * PostgreSQL container, the project's own Flyway migrations applied, and the handful of beans the
 * worker path touches constructed directly (no Spring context, so no Kafka / Keycloak / outbox).
 * The {@link WorkflowWorker} is rebuilt per test with its own {@link AgentFlowProperties} because
 * it reads the execution limits <em>once, at construction</em> (see {@link
 * WorkflowWorker#WorkflowWorker}), so the bounds are fixed for the lifetime of each worker
 * instance.
 */
class WorkflowWorkerLimitsIT {

  /** The Testcontainers image matches the integration-suite convention (pgvector on PG 18). */
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  // --- beans wired once against the real container, in {@link #startDatabaseAndWireBeans()} ----
  private static AgentFlowProperties properties;
  private static JdbcTemplate jdbc;
  private static WorkflowDefinitionRepository definitionRepository;
  private static WorkflowVersionRepository versionRepository;
  private static WorkflowRunRepository runRepository;
  private static WorkflowStepRunRepository stepRepository;
  private static WorkflowEventRepository eventRepository;
  private static RunStateMachineService transitionService;
  private static RunLeaseService leaseService;
  private static RunHeartbeatService heartbeat;
  private static CheckpointAdapter checkpointAdapter;
  private static WorkflowVersionService versionService;

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

    // Fresh defaults: nested Worker/Lease settings carry their built-in defaults; appName is left
    // null (only Spring's config binding is required to supply it in production).
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
    eventRepository = eventRepo;

    AuditService auditService = new AuditService(auditRepo);
    WorkflowEventService eventService = new WorkflowEventService(eventRepo);
    versionService =
        new WorkflowVersionService(definitionRepo, versionRepo, auditService, properties);

    transitionService = new RunStateMachineService(runRepo, stepRepo, null, eventService, null);
    leaseService = new RunLeaseService(runRepo, Clock.systemUTC(), properties);
    heartbeat = new RunHeartbeatService(runRepo, Clock.systemUTC(), properties);
    checkpointAdapter = new CheckpointAdapter(stepRepo, properties);
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

  /**
   * Builds a fresh {@link WorkflowWorker} whose execution limits come from the supplied properties.
   * The worker reads {@link AgentFlowProperties#getExecution()} once, at construction, so each test
   * must hand it its own configured instance to fix the bounds for the worker's lifetime.
   */
  private static WorkflowWorker newWorker(AgentFlowProperties props) {
    return new WorkflowWorker(
        runRepository,
        stepRepository,
        versionService,
        leaseService,
        heartbeat,
        transitionService,
        checkpointAdapter,
        null,
        List.<StepHandler>of(new DeterministicStepHandler()),
        props);
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** SHA-256 of the stored graph JSON text, hex-encoded — mirrors the version checksum. */
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

  /**
   * The deterministic {@code a -> b} NOOP graph (two steps, one edge): the first step always
   * completes, so a {@code maxSteps=1} bound stops the run at the second step — exactly the limit
   * breach the acceptance criterion describes.
   */
  private static String twoStepChainGraph() {
    return "{\"schemaVersion\":\"1.0.0\",\"nodes\":["
        + "{\"id\":\"a\",\"type\":\"NOOP\",\"config\":{\"result\":\"a-done\"}},"
        + "{\"id\":\"b\",\"type\":\"NOOP\",\"config\":{\"result\":\"b-done\"}}],"
        + "\"edges\":[{\"from\":\"a\",\"to\":\"b\"}]}";
  }

  /**
   * Registers a tenant, a DRAFT graph version and an activated version pinned to it, then creates a
   * {@code QUEUED} run. Returns the run id. Shared by every test.
   */
  private static UUID seededRun(UUID tenant) {
    // tenants.tenant_id references it (invariant 1); bind as text and cast to uuid so the insert is
    // independent of the driver's UUID parameter binding.
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES ((?)::uuid, ?, ?)",
        tenant.toString(),
        tenant.toString(),
        "ACTIVE");

    WorkflowDefinition definition =
        definitionRepository.insert(
            WorkflowDefinition.draft(
                tenant, "limits-deterministic", "Limits deterministic", "e2e@agentflow"));
    UUID workflowId = definition.id();

    String graphJson = twoStepChainGraph();
    String checksum = sha256Hex(graphJson);
    UUID versionId =
        versionRepository
            .create(tenant, workflowId, 1, graphJson, checksum, "e2e@agentflow")
            .orElseThrow()
            .id();
    versionRepository.activate(tenant, versionId, checksum);

    UUID runId = UUID.randomUUID();
    runRepository.create(runId, tenant, versionId, null, null, "e2e@agentflow");
    return runId;
  }

  @Test
  void a_run_breaching_the_step_budget_completes_as_a_documented_failed_run_with_a_limit_event() {
    UUID tenant = UUID.randomUUID();
    UUID runId = seededRun(tenant);

    // The worker reads the limits once, at construction: pin maxSteps to 1 (and keep the other
    // bounds generous) BEFORE building the worker, so the second step trips the step budget.
    AgentFlowProperties props = new AgentFlowProperties();
    props.getExecution().setMaxSteps(1);
    WorkflowWorker worker = newWorker(props);

    int processed = worker.pollOnce(tenant);

    // The run reached a terminal state and the poll made progress on it.
    assertThat(processed).isOne();

    // The controlled FAILED state is durable: read it back from the database (invariant 2).
    RunView view = runRepository.findRun(tenant, runId).orElseThrow();
    assertThat(view.status()).isEqualTo(WorkflowRunState.FAILED);

    // The limit fired after the first step succeeded and stopped the second: the first step is a
    // terminal SUCCEEDED, the second a terminal FAILED — the run was closed at the breach, not run
    // to completion.
    List<StepStatusRow> steps = stepRepository.findStepStatuses(tenant, runId);
    assertThat(steps).extracting("stepKey").containsExactlyInAnyOrder("a", "b");
    assertThat(steps)
        .extracting("status")
        .containsExactlyInAnyOrder(WorkflowStepState.SUCCEEDED, WorkflowStepState.FAILED);

    // The documented event is durable: the run.transition history carries a {@code
    // run.limit-exceeded}
    // fact (transition 14). The payload records the transition (prior -> new) and the documented
    // {@code limit_exceeded} error class token (invariant: the reason is the class, not free text).
    // This is the acceptance criterion read back from the database, not merely asserted at the
    // worker call.
    List<WorkflowEvent> history = eventRepository.findByTenantAndRunId(tenant, runId);
    assertThat(history).isNotEmpty();
    WorkflowEvent limitEvent =
        history.stream()
            .filter(e -> "run.limit-exceeded".equals(e.eventType()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "no run.limit-exceeded event in workflow_events: " + history));
    assertThat(limitEvent.runId()).isEqualTo(runId);
    assertThat(limitEvent.stepRunId()).isNull();
    assertThat(limitEvent.payloadRedacted())
        .contains("prior=RUNNING new=FAILED")
        .contains("error=limit_exceeded");
  }

  @Test
  void a_run_within_every_budget_completes_with_a_run_finished_event() {
    UUID tenant = UUID.randomUUID();
    UUID runId = seededRun(tenant);

    // The defaults (maxSteps=1000, maxToolCalls=100, ...) leave the two-step chain inside every
    // bound, so the run should complete SUCCEEDED — the positive control that the limits do not
    // interfere with a healthy run.
    WorkflowWorker worker = newWorker(new AgentFlowProperties());

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isOne();
    RunView view = runRepository.findRun(tenant, runId).orElseThrow();
    assertThat(view.status()).isEqualTo(WorkflowRunState.SUCCEEDED);

    List<StepStatusRow> steps = stepRepository.findStepStatuses(tenant, runId);
    assertThat(steps)
        .extracting("status")
        .containsExactlyInAnyOrder(WorkflowStepState.SUCCEEDED, WorkflowStepState.SUCCEEDED);

    List<WorkflowEvent> history = eventRepository.findByTenantAndRunId(tenant, runId);
    assertThat(history).isNotEmpty();
    assertThat(history).anyMatch(e -> "run.finished".equals(e.eventType()));
  }
}
