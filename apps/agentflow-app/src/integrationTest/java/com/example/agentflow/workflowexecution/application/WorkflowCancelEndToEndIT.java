package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.persistence.AuditRepository;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.messaging.persistence.WorkflowEventRepository;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import com.example.agentflow.workflowdefinition.persistence.WorkflowVersionRepository;
import com.example.agentflow.workflowexecution.domain.Checkpoint;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end (real PostgreSQL) verification of the S07-12 cooperative cancellation path.
 *
 * <p>The S07-12 acceptance criterion is that <em>cancellation does not erase a completed result and
 * does not move an already-terminal run</em>. These tests boot a real PostgreSQL instance with the
 * project's own Flyway migrations, wire the real repositories / state machine / lease / checkpoint
 * beans, build the {@link WorkflowWorker} with the MVP {@link DeterministicStepHandler} and the
 * {@link RunService} request path, and drive a deterministic {@code a -> b} workflow through {@code
 * runService.requestCancellation} and {@link WorkflowWorker#pollOnce}.
 *
 * <p>This is the same <strong>controlled standalone</strong> style as {@link
 * WorkflowWorkerEndToEndIT}: one Testcontainers PostgreSQL, real lease/fencing, a guarded state
 * machine that writes the run-state event and the audit trail in the same transaction, without
 * depending on Kafka, Keycloak or the full Spring context.
 */
class WorkflowCancelEndToEndIT {

  /** The Testcontainers image matches the integration-suite convention (pgvector on PG 18). */
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static AgentFlowProperties properties;
  private static JdbcTemplate jdbc;
  private static WorkflowDefinitionRepository definitionRepository;
  private static WorkflowVersionRepository versionRepository;
  private static WorkflowRunRepository runRepository;
  private static WorkflowStepRunRepository stepRepository;
  private static WorkflowEventRepository eventRepository;
  private static RunStateMachineService transitionService;
  private static RunLeaseService leaseService;
  private static CheckpointAdapter checkpointAdapter;
  private static WorkflowVersionService versionService;
  private static WorkflowWorker worker;
  private static RunService runService;

  @BeforeAll
  static void startDatabaseAndWireBeans() {
    POSTGRES.start();

    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:migrations")
        .load()
        .migrate();

    properties = new AgentFlowProperties();

    jdbc = new JdbcTemplate(dataSource());

    WorkflowDefinitionRepository definitionRepo = new WorkflowDefinitionRepository(jdbc);
    WorkflowVersionRepository versionRepo = new WorkflowVersionRepository(jdbc);
    WorkflowRunRepository runRepo = new WorkflowRunRepository(jdbc);
    WorkflowStepRunRepository stepRepo = new WorkflowStepRunRepository(jdbc);
    WorkflowEventRepository eventRepo = new WorkflowEventRepository(jdbc);
    AuditRepository auditRepo = new AuditRepository(jdbc);

    definitionRepository = definitionRepo;
    versionRepository = versionRepo;
    runRepository = runRepo;
    stepRepository = stepRepo;
    eventRepository = eventRepo;

    AuditService auditService = new AuditService(auditRepo);
    WorkflowEventService eventService = new WorkflowEventService(eventRepo);
    versionService =
        new WorkflowVersionService(definitionRepo, versionRepo, auditService, properties);

    transitionService = new RunStateMachineService(runRepo, stepRepo, null, eventService);
    leaseService = new RunLeaseService(runRepo, Clock.systemUTC(), properties);
    RunHeartbeatService heartbeat = new RunHeartbeatService(runRepo, Clock.systemUTC(), properties);
    checkpointAdapter = new CheckpointAdapter(stepRepo, properties);

    // A RunService with no idempotency/outbox repositories: the cancellation path needs none, and
    // both collaborators are optional (required = false) for the no-database boot.
    IdempotencyKeyService idempotencyKeyService = new IdempotencyKeyService(null);
    OutboxService outboxService = new OutboxService(null);
    runService =
        new RunService(
            runRepo, idempotencyKeyService, outboxService, versionService, transitionService, null);

    worker =
        new WorkflowWorker(
            runRepo,
            stepRepo,
            versionService,
            leaseService,
            heartbeat,
            transitionService,
            checkpointAdapter,
            null,
            List.<StepHandler>of(new DeterministicStepHandler()),
            properties);
  }

  @AfterAll
  static void stopDatabase() {
    if (POSTGRES != null && POSTGRES.isRunning()) {
      POSTGRES.stop();
    }
  }

  private static DataSource dataSource() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }

  /** Registers a tenant, a {@code NOOP} {@code a -> b} graph and a QUEUED run pinned to it. */
  private static Setup registerWorkflow(UUID tenant, UUID runId) {
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES ((?)::uuid, ?, ?)",
        tenant.toString(),
        tenant.toString(),
        "ACTIVE");

    UUID workflowId =
        definitionRepository
            .insert(
                WorkflowDefinition.draft(
                    tenant, "e2e-cancel", "E2E cancel workflow", "e2e@agentflow"))
            .id();

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

    runRepository.create(runId, tenant, versionId, null, null, "e2e@agentflow");
    return new Setup(tenant, workflowId, versionId, runId);
  }

  @Test
  void cancel_a_queued_run_drains_over_real_sql_and_cancels_the_not_started_steps() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    Setup setup = registerWorkflow(tenant, runId);

    // Phase 1 (RunService): a queued run moves to the cancellation-in-flight state.
    assertThat(runService.requestCancellation(tenant, runId, "e2e@agentflow").status())
        .isEqualTo("CANCEL_REQUESTED");

    // Phase 2 (Worker): the worker re-claims the CANCEL_REQUESTED run and drains it over real SQL.
    int processed = worker.pollOnce(tenant);
    assertThat(processed).isEqualTo(1);

    // The run closes to CANCELLED once every step is terminal.
    RunView view = runRepository.findRun(tenant, runId).orElseThrow();
    assertThat(view.status()).isEqualTo(WorkflowRunState.CANCELLED);

    // The not-yet-started steps are cancelled; nothing executed, so no output was produced.
    List<StepStatusRow> steps = stepRepository.findStepStatuses(tenant, runId);
    assertThat(steps).extracting("stepKey").containsExactlyInAnyOrder("a", "b");
    assertThat(steps)
        .extracting("status")
        .containsExactlyInAnyOrder(WorkflowStepState.CANCELLED, WorkflowStepState.CANCELLED);
    assertThat(stepRepository.findStepOutputs(tenant, runId)).isEmpty();
    // No checkpoint was persisted for a cancelled step (invariant 4: only executed steps are
    // checkpointed, and here no step executed at all): the step row exists but its output is null.
    assertThat(
            stepRepository.findCheckpoint(tenant, runId, "a").map(CheckpointSnapshot::outputInline))
        .isEmpty();
    assertThat(
            stepRepository.findCheckpoint(tenant, runId, "b").map(CheckpointSnapshot::outputInline))
        .isEmpty();

    // The audit trail records the cancel request, the step cancels and the run close.
    List<com.example.agentflow.messaging.domain.WorkflowEvent> events =
        eventRepository.findByTenantAndRunId(tenant, runId);
    assertThat(events)
        .extracting(e -> e.eventType())
        .contains("run.cancel", "step.cancelled", "run.cancelled");
    assertThat(events).extracting(e -> e.eventType()).doesNotContain("run.finished", "run.started");
  }

  @Test
  void cancel_preserves_a_completed_step_and_cancels_only_the_pending_dependent() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    // Build a partial state directly: a completed step "a" and a pending dependent "b".
    Setup setup = registerWorkflow(tenant, runId);
    stepRepository.createStep(runId, tenant, "a", 1, "e2e@agentflow");
    stepRepository.createStep(runId, tenant, "b", 1, "e2e@agentflow");
    // Complete "a" and record its redacted output; leave "b" PENDING (guarded by the lease fence).
    stepRepository.transition(
        runId, tenant, "a", WorkflowStepState.PENDING, 1, WorkflowStepState.SUCCEEDED, null, null);
    stepRepository.saveCheckpoint(
        tenant, runId, "a", 1, new Checkpoint("1.0", "{\"result\":\"a-done\"}"));

    // Request cancellation, then let the worker drain.
    runService.requestCancellation(tenant, runId, "e2e@agentflow");
    worker.pollOnce(tenant);

    // The completed step keeps its result and checkpoint (invariant 4).
    RunView view = runRepository.findRun(tenant, runId).orElseThrow();
    assertThat(view.status()).isEqualTo(WorkflowRunState.CANCELLED);
    StepStatusRow a =
        stepRepository.findStepStatuses(tenant, runId).stream()
            .filter(s -> s.stepKey().equals("a"))
            .findFirst()
            .orElseThrow();
    StepStatusRow b =
        stepRepository.findStepStatuses(tenant, runId).stream()
            .filter(s -> s.stepKey().equals("b"))
            .findFirst()
            .orElseThrow();
    assertThat(a.status()).isEqualTo(WorkflowStepState.SUCCEEDED);
    assertThat(b.status()).isEqualTo(WorkflowStepState.CANCELLED);
    assertThat(stepRepository.findCheckpoint(tenant, runId, "a"))
        .map(CheckpointSnapshot::outputInline)
        .hasValue("{\"result\":\"a-done\"}");
  }

  @Test
  void cancel_is_refused_on_an_already_completed_run_by_the_state_machine() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    Setup setup = registerWorkflow(tenant, runId);

    // Complete the run: one poll drains both steps and the run closes to SUCCEEDED.
    worker.pollOnce(tenant);
    assertThat(runRepository.findRun(tenant, runId).orElseThrow().status())
        .isEqualTo(WorkflowRunState.SUCCEEDED);

    // The state machine refuses SUCCEEDED -> CANCELLED: cancel never moves a terminal run.
    assertThatThrownBy(
            () ->
                transitionService.transitionRun(
                    tenant, runId, WorkflowRunState.CANCELLED, "run.cancelled", "e2e@agentflow"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    // The run is untouched by the refused transition.
    assertThat(runRepository.findRun(tenant, runId).orElseThrow().status())
        .isEqualTo(WorkflowRunState.SUCCEEDED);
  }

  /** The immutable identifiers a helper build produces. */
  private record Setup(UUID tenant, UUID workflowId, UUID versionId, UUID runId) {}

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
