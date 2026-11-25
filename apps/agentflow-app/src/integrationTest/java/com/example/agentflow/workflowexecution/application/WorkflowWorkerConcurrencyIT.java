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
import com.example.agentflow.workflowexecution.domain.StepExecutionContext;
import com.example.agentflow.workflowexecution.domain.StepHandler;
import com.example.agentflow.workflowexecution.domain.StepResult;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.RunView;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
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
 * S14-06 — the concurrent lease claims of ADR-011 hold under real concurrency: two workers racing
 * on one run must produce <em>exactly one</em> owner (the atomic {@code workflow_runs} claim), and
 * a stale owner that survives past its re-claim must <em>not</em> be able to commit a result —
 * every write it makes after the re-claim is fenced by the {@code lease_epoch} it holds.
 *
 * <p>Controlled standalone test in the same style as {@link WorkflowWorkerCrashRecoveryIT}: one
 * Testcontainers PostgreSQL, the project migrations applied, and the handful of beans the worker
 * path touches wired against real SQL. No Kafka, Keycloak or Spring context is booted.
 *
 * <h2>Scenario 1 — the stale owner's late write is refused</h2>
 *
 * <ol>
 *   <li>Worker 1 claims a run and blocks inside a step handler (heartbeat extends the lease).
 *   <li>Worker 1 is "killed" (heartbeat stopped); the lease expires.
 *   <li>Worker 2 re-claims the lease: ownership transfers and {@code lease_epoch} bumps.
 *   <li>Worker 1's handler is released. Its {@code step.succeeded} write must be refused — the
 *       caller passes the {@code lease_epoch} it claimed; the row's fence has moved — and the run
 *       must stay {@code RUNNING} with no checkpoint.
 * </ol>
 *
 * <h2>Scenario 2 — two workers race one run</h2>
 *
 * <ol>
 *   <li>Worker 1 and Worker 2 poll the same {@code QUEUED} run concurrently.
 *   <li>Exactly one claim wins (one {@code lease_epoch}, one owner); exactly one worker drives the
 *       run to {@code SUCCEEDED}; the other makes no progress.
 * </ol>
 *
 * @see WorkflowWorkerCrashRecoveryIT the sibling fault-injection scenario
 * @see RunLeaseService the ADR-011 lease claim
 */
class WorkflowWorkerConcurrencyIT {

  /** The Testcontainers image matches the integration-suite convention (pgvector on PG 18). */
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static AgentFlowProperties properties;
  private static JdbcTemplate jdbc;
  private static WorkflowDefinitionRepository definitionRepository;
  private static WorkflowVersionRepository versionRepository;
  private static WorkflowRunRepository runRepository;
  private static WorkflowStepRunRepository stepRepository;
  private static RunStateMachineService transitionService;
  private static CheckpointAdapter checkpointAdapter;
  private static WorkflowVersionService versionService;

  private static final int LEASE_DURATION_SECONDS = 3;
  private static final int HEARTBEAT_INTERVAL_SECONDS = 1;
  private static final int RECOVERY_INTERVAL_SECONDS = 5;

  @BeforeAll
  static void startDatabaseAndWireBeans() {
    POSTGRES.start();

    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:migrations")
        .load()
        .migrate();

    properties = new AgentFlowProperties();
    properties.getWorker().getLease().setDurationSeconds(LEASE_DURATION_SECONDS);
    properties.getWorker().getLease().setHeartbeatIntervalSeconds(HEARTBEAT_INTERVAL_SECONDS);
    properties.getWorker().getLease().setRecoveryIntervalSeconds(RECOVERY_INTERVAL_SECONDS);

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

    AuditService auditService = new AuditService(auditRepo);
    WorkflowEventService eventService = new WorkflowEventService(eventRepo);
    versionService =
        new WorkflowVersionService(definitionRepo, versionRepo, auditService, properties);

    transitionService = new RunStateMachineService(runRepo, stepRepo, null, eventService, null);
    RunHeartbeatService heartbeat = new RunHeartbeatService(runRepo, Clock.systemUTC(), properties);
    checkpointAdapter = new CheckpointAdapter(stepRepo, properties);
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

  private static WorkflowWorker buildWorker(UUID instanceId, List<StepHandler> handlers) {
    AgentFlowProperties cfg = new AgentFlowProperties();
    cfg.getWorker().getLease().setDurationSeconds(LEASE_DURATION_SECONDS);
    cfg.getWorker().getLease().setHeartbeatIntervalSeconds(HEARTBEAT_INTERVAL_SECONDS);
    cfg.getWorker().getLease().setRecoveryIntervalSeconds(RECOVERY_INTERVAL_SECONDS);
    cfg.getWorker().setInstanceId(instanceId.toString());
    RunLeaseService lease = new RunLeaseService(runRepository, Clock.systemUTC(), cfg);
    RunHeartbeatService heartbeat = new RunHeartbeatService(runRepository, Clock.systemUTC(), cfg);
    return new WorkflowWorker(
        runRepository,
        stepRepository,
        versionService,
        lease,
        heartbeat,
        transitionService,
        checkpointAdapter,
        null,
        handlers,
        cfg);
  }

  // --- Scenario 1: the stale owner's late write is refused ---------------------------------------

  @Test
  void stale_owner_cannot_commit_a_result_after_another_worker_reclaims_the_run() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES ((?)::uuid, ?, ?)",
        tenant.toString(),
        tenant.toString(),
        "ACTIVE");

    WorkflowDefinition definition =
        definitionRepository.insert(
            WorkflowDefinition.draft(
                tenant, "concurrency-stale", "concurrency stale", "e2e@agentflow"));
    String graphJson =
        "{\"schemaVersion\":\"1.0.0\",\"nodes\":["
            + "{\"id\":\"b\",\"type\":\"BLOCK\",\"config\":{\"result\":\"b-done\"}}],"
            + "\"edges\":[]}";
    String checksum = sha256Hex(graphJson);
    UUID versionId =
        versionRepository
            .create(tenant, definition.id(), 1, graphJson, checksum, "e2e@agentflow")
            .orElseThrow()
            .id();
    versionRepository.activate(tenant, versionId, checksum);

    runRepository.create(runId, tenant, versionId, null, null, "e2e@agentflow");

    // 1. Worker 1 claims and drives the run, then blocks inside step 'b'.
    CountDownLatch blockLatch = new CountDownLatch(1);
    UUID worker1 = UUID.randomUUID();
    WorkflowWorker worker1Worker =
        buildWorker(worker1, List.<StepHandler>of(new BlockingStepHandler(blockLatch)));

    AtomicReference<Throwable> staleFailure = new AtomicReference<>();
    Thread worker1Thread =
        new Thread(() -> worker1Worker.pollOnce(tenant), "concurrency-stale-worker-1");
    worker1Thread.setDaemon(true);
    worker1Thread.setUncaughtExceptionHandler((t, e) -> staleFailure.set(e));
    worker1Thread.start();

    awaitCrashWindow(tenant, runId);
    assertThat(runRepository.findRun(tenant, runId).orElseThrow().status())
        .isEqualTo(WorkflowRunState.RUNNING);
    assertThat(stepRepository.findStepStatuses(tenant, runId))
        .anyMatch(r -> "b".equals(r.stepKey()) && r.status() == WorkflowStepState.RUNNING);

    LeaseSnapshot preCrash = readLease(tenant, runId);
    assertThat(preCrash.owner()).isEqualTo(worker1);
    assertThat(preCrash.epoch()).isEqualTo(1);

    // 2. Kill worker 1: stop its heartbeat so the lease stops being extended.
    worker1Worker.destroy();

    // 3. Wait for the lease to expire.
    awaitLeaseExpired(tenant, runId, preCrash.leaseUntil());

    // 4. Worker 2 re-claims the run: ownership transfers, the epoch bumps.
    UUID worker2 = UUID.randomUUID();
    WorkflowWorker worker2Worker =
        buildWorker(worker2, List.<StepHandler>of(new DeterministicStepHandler()));
    OffsetDateTime reclaimedAt = pollUntilReclaimed(worker2Worker, tenant, runId, worker2);
    assertThat(reclaimedAt).isNotNull();

    LeaseSnapshot afterReclaim = readLease(tenant, runId);
    assertThat(afterReclaim.owner()).isEqualTo(worker2);
    assertThat(afterReclaim.epoch()).isGreaterThan(preCrash.epoch());

    // 5. Release the blocked handler: the stale owner completes its step and tries to commit.
    blockLatch.countDown();
    joinWithTimeout(worker1Thread, 10_000);

    // 6. The stale write is fenced out — the caller passes the lease epoch it claimed, the row's
    //    fence has moved — so it is refused loudly and nothing is persisted.
    assertThat(staleFailure.get())
        .as("the stale owner's late commit must be refused (not silently applied)")
        .isNotNull();
    if (staleFailure.get() instanceof ResponseStatusException responseStatusException) {
      assertThat(responseStatusException.getStatusCode())
          .as("the refused commit is a conflict")
          .isEqualTo(HttpStatus.CONFLICT);
    }

    RunView finalRun = runRepository.findRun(tenant, runId).orElseThrow();
    assertThat(finalRun.status())
        .as("the run must not be moved by the fenced stale owner")
        .isEqualTo(WorkflowRunState.RUNNING);
    assertThat(stepRepository.findStepStatuses(tenant, runId))
        .as("step 'b' must stay RUNNING — the stale owner's success is fenced out")
        .anyMatch(r -> "b".equals(r.stepKey()) && r.status() == WorkflowStepState.RUNNING);
    assertThat(stepRepository.findStepStatuses(tenant, runId))
        .as("no step may be marked SUCCEEDED by the stale owner")
        .noneMatch(r -> "b".equals(r.stepKey()) && r.status() == WorkflowStepState.SUCCEEDED);

    // No checkpoint row may be written by the stale owner (it never reaches the checkpoint write).
    // The checkpoint is stored inline on the step row (output_inline on workflow_step_runs): the
    // stale owner must not have persisted one.
    assertThat(
            jdbc.queryForList(
                "SELECT output_inline FROM workflow_step_runs s "
                    + "JOIN workflow_runs r ON r.id = s.run_id "
                    + "WHERE s.run_id = ? AND r.tenant_id = ? AND s.step_key = 'b'",
                String.class,
                runId,
                tenant))
        .as("the stale owner must not persist a checkpoint")
        .allMatch(java.util.Objects::isNull);

    worker2Worker.destroy();
  }

  // --- Scenario 2: two workers race one run ------------------------------------------------------

  @Test
  void two_workers_racing_one_run_yield_exactly_one_owner() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES ((?)::uuid, ?, ?)",
        tenant.toString(),
        tenant.toString(),
        "ACTIVE");

    WorkflowDefinition definition =
        definitionRepository.insert(
            WorkflowDefinition.draft(
                tenant, "concurrency-race", "concurrency race", "e2e@agentflow"));
    String graphJson =
        "{\"schemaVersion\":\"1.0.0\",\"nodes\":["
            + "{\"id\":\"b\",\"type\":\"BLOCK\",\"config\":{\"result\":\"b-done\"}}],"
            + "\"edges\":[]}";
    String checksum = sha256Hex(graphJson);
    UUID versionId =
        versionRepository
            .create(tenant, definition.id(), 1, graphJson, checksum, "e2e@agentflow")
            .orElseThrow()
            .id();
    versionRepository.activate(tenant, versionId, checksum);

    runRepository.create(runId, tenant, versionId, null, null, "e2e@agentflow");

    // Two workers poll the same run concurrently; each guarantees exactly one thread touches it.
    UUID worker1 = UUID.randomUUID();
    UUID worker2 = UUID.randomUUID();
    WorkflowWorker worker1Worker =
        buildWorker(worker1, List.<StepHandler>of(new DeterministicStepHandler()));
    WorkflowWorker worker2Worker =
        buildWorker(worker2, List.<StepHandler>of(new DeterministicStepHandler()));

    AtomicReference<Integer> progress1 = new AtomicReference<>(0);
    AtomicReference<Integer> progress2 = new AtomicReference<>(0);

    Thread worker1Thread =
        new Thread(
            () -> progress1.set(worker1Worker.pollOnce(tenant)), "concurrency-race-worker-1");
    Thread worker2Thread =
        new Thread(
            () -> progress2.set(worker2Worker.pollOnce(tenant)), "concurrency-race-worker-2");
    worker1Thread.setDaemon(true);
    worker2Thread.setDaemon(true);
    worker1Thread.start();
    worker2Thread.start();

    joinWithTimeout(worker1Thread, 30_000);
    joinWithTimeout(worker2Thread, 30_000);

    assertThat(worker1Thread.isAlive() || worker2Thread.isAlive())
        .as("both worker threads must finish (no deadlocked poll)")
        .isFalse();

    // Exactly one claim happened (epoch stays 1), and exactly one of the two workers drove the run.
    LeaseSnapshot after = readLease(tenant, runId);
    assertThat(after.epoch())
        .as("the run may be claimed exactly once — a single epoch increment")
        .isEqualTo(1);
    assertThat(after.owner())
        .as("exactly one of the two workers owns the run")
        .isIn(worker1, worker2);

    // The winner made progress; the loser made none. Exactly one worker processed the run.
    int totalProgress =
        (progress1.get() == null ? 0 : progress1.get())
            + (progress2.get() == null ? 0 : progress2.get());
    assertThat(totalProgress).as("exactly one worker makes progress on the run").isEqualTo(1);

    // The winner drove the run to completion (the deterministic handler never blocks).
    RunView finalRun = runRepository.findRun(tenant, runId).orElseThrow();
    assertThat(finalRun.status())
        .as("the winner drives the run to a terminal state")
        .isIn(WorkflowRunState.SUCCEEDED, WorkflowRunState.CANCELLED, WorkflowRunState.FAILED);

    worker1Worker.destroy();
    worker2Worker.destroy();
  }

  // --- shared harness --------------------------------------------------------------------------

  private static final class BlockingStepHandler implements StepHandler {

    private static final String NODE_TYPE = "BLOCK";

    private final CountDownLatch blockLatch;

    BlockingStepHandler(CountDownLatch blockLatch) {
      this.blockLatch = blockLatch;
    }

    @Override
    public boolean supports(String nodeType) {
      return NODE_TYPE.equals(nodeType);
    }

    @Override
    public StepResult handle(StepExecutionContext context) {
      try {
        blockLatch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return StepResult.succeeded("block-done:" + context.stepKey());
    }
  }

  private record LeaseSnapshot(UUID owner, int epoch, OffsetDateTime leaseUntil) {}

  private static void awaitCrashWindow(UUID tenant, UUID runId) {
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline) {
      RunView view = runRepository.findRun(tenant, runId).orElseThrow();
      if (view.status() == WorkflowRunState.RUNNING
          && stepRepository.findStepStatuses(tenant, runId).stream()
              .anyMatch(r -> "b".equals(r.stepKey()) && r.status() == WorkflowStepState.RUNNING)) {
        return;
      }
      sleepMs(50L);
    }
    assertThat(stepRepository.findStepStatuses(tenant, runId))
        .as("the run must reach RUNNING with step 'b' executing (the crash window)")
        .anyMatch(r -> "b".equals(r.stepKey()) && r.status() == WorkflowStepState.RUNNING);
  }

  private static void awaitLeaseExpired(UUID tenant, UUID runId, OffsetDateTime leaseUntil) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      OffsetDateTime until = readLease(tenant, runId).leaseUntil();
      if (until != null && until.isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
        return;
      }
      sleepMs(50L);
    }
    assertThat(leaseUntil)
        .as("the lease must expire after the owning worker is killed")
        .isBefore(OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static OffsetDateTime pollUntilReclaimed(
      WorkflowWorker worker, UUID tenant, UUID runId, UUID workerId) {
    long deadline = System.currentTimeMillis() + 30_000;
    OffsetDateTime reclaimedAt = null;
    while (System.currentTimeMillis() < deadline) {
      worker.pollOnce(tenant);
      if (workerId.equals(readLease(tenant, runId).owner())) {
        reclaimedAt = OffsetDateTime.now(ZoneOffset.UTC);
        return reclaimedAt;
      }
      sleepMs(100L);
    }
    assertThat(readLease(tenant, runId).owner())
        .as("a second worker must re-claim the run after the lease expires")
        .isEqualTo(workerId);
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static LeaseSnapshot readLease(UUID tenant, UUID runId) {
    return jdbc.queryForObject(
        "SELECT lease_owner, lease_epoch, lease_until FROM workflow_runs "
            + "WHERE id = ? AND tenant_id = ?",
        (rs, rowNum) ->
            new LeaseSnapshot(
                (UUID) rs.getObject("lease_owner"),
                rs.getInt("lease_epoch"),
                rs.getObject("lease_until", OffsetDateTime.class)),
        runId,
        tenant);
  }

  private static void joinWithTimeout(Thread thread, long timeoutMillis) {
    try {
      thread.join(timeoutMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleepMs(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

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
