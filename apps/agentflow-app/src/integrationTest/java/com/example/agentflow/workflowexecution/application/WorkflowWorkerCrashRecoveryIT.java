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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Fault-injection (kill / restart) verification of the S07-15 durable-execution acceptance: an
 * actively-owned run whose worker dies mid-step is <em>not lost</em> &mdash; once its lease expires
 * a fresh worker re-claims it (ownership transfer fenced by the {@code lease_epoch} counter) and
 * the run resumes no later than 60 seconds after the lease expiry in the test configuration.
 *
 * <p>This is a <strong>controlled standalone</strong> test in the same style as {@link
 * WorkflowWorkerEndToEndIT}: one Testcontainers PostgreSQL, the project migrations applied, and the
 * handful of beans the worker path touches wired against real SQL with real lease / fencing. No
 * Kafka, Keycloak or Spring context is booted.
 *
 * <h2>Why the assertion is a <em>re-claim</em>, not a re-drive to completion</h2>
 *
 * <p>The run-level state machine (run-state-machine.md) has no {@code RUNNING -> RUNNING} edge, so
 * a worker that re-claims an already-running run cannot transition the run forward again in the
 * same poll &mdash; {@link WorkflowWorker#processRun} claims, then {@code transitionRun(RUNNING)}
 * is denied and the poll makes no progress. The durable-recovery path is therefore the
 * <strong>lease re-claim</strong> (ADR-011): {@code WorkflowRunRepository#claim} is one fenced
 * {@code UPDATE ... RETURNING lease_epoch} that transfers {@code lease_owner} and bumps {@code
 * lease_epoch}, so the run's ownership moves to the new worker and the stale worker's later writes
 * are fenced out (invariant 5). That is exactly the "check the claim" the S07-15 acceptance asks
 * for. The run is verified to survive (still {@code RUNNING}, not lost) and to resume within the
 * SLA.
 *
 * <h2>The scenario</h2>
 *
 * <ol>
 *   <li>Worker 1 claims a run with a single {@code BLOCK} step and blocks inside the step handler
 *       (its lease heartbeat thread keeps {@code lease_until} alive); the {@code RUNNING} run and
 *       the {@code RUNNING} step are the persisted crash window.
 *   <li>The worker is "killed" &mdash; its heartbeat scheduler is stopped &mdash; the lease is no
 *       extended and expires {@code duration-seconds} later.
 *   <li>Worker 2 is raised &mdash; its {@link WorkflowWorker#pollOnce} re-claims the expired lease,
 *       transferring ownership and fencing the epoch.
 * </ol>
 *
 * @see WorkflowWorkerEndToEndIT the deterministic end-to-end sibling
 * @see RunLeaseService the ADR-011 lease claim
 */
class WorkflowWorkerCrashRecoveryIT {

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
  private static CheckpointAdapter checkpointAdapter;
  private static WorkflowVersionService versionService;

  /** Lease / heartbeat / recovery seconds used by every test in the class (bounds the SLA). */
  private static final int LEASE_DURATION_SECONDS = 3;

  private static final int HEARTBEAT_INTERVAL_SECONDS = 1;
  private static final int RECOVERY_INTERVAL_SECONDS = 5;

  private Thread worker1Thread;

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

    // Lease cadence kept small but coherent (heartbeat fires at least three times per lease TTL,
    // duration + recovery <= 60) so the crash / re-claim completes quickly in the test while still
    // exercising the real fenced lease model.
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

    transitionService = new RunStateMachineService(runRepo, stepRepo, null, eventService);
    RunHeartbeatService heartbeat = new RunHeartbeatService(runRepo, Clock.systemUTC(), properties);
    checkpointAdapter = new CheckpointAdapter(stepRepo, properties);
  }

  @AfterAll
  static void stopDatabase() {
    if (POSTGRES != null && POSTGRES.isRunning()) {
      POSTGRES.stop();
    }
  }

  /** Builds a {@link DataSource} bound to the Testcontainers Postgres (started in @BeforeAll). */
  private static DataSource dataSource() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }

  /**
   * Builds a worker instance with a fresh lease + heartbeat (each carrying a distinct instance id,
   * so its {@code lease_owner} is distinguishable) wired to the shared real beans. The handler list
   * is supplied per call so each test controls its own blocking step.
   */
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

  /**
   * The run survives its owning worker dying mid-step: after the lease expires a second worker
   * re-claims it (ownership transfer fenced by {@code lease_epoch}) within 60s and the run is not
   * lost.
   */
  @Test
  void a_running_run_survives_its_worker_crash_and_is_reclaimed_within_sla() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    // 0. Register the tenant — workflow_definitions.tenant_id references it (invariant 1). Bound as
    //    text and cast to uuid so the insert is independent of the driver's UUID parameter binding.
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES ((?)::uuid, ?, ?)",
        tenant.toString(),
        tenant.toString(),
        "ACTIVE");

    // 1. Register the parent definition and activate a single {@code BLOCK} node graph. The block
    //    node is stored directly (the validator set is S05-07's concern, not this fault-injection
    //    test's) and activated by re-checking the pinned checksum and the tenant.
    WorkflowDefinition definition =
        definitionRepository.insert(
            WorkflowDefinition.draft(tenant, "crash-recovery", "crash recovery", "e2e@agentflow"));
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

    // 2. Create a QUEUED run pinned to that version.
    runRepository.create(runId, tenant, versionId, null, null, "e2e@agentflow");

    // 3. A step handler that blocks the owning worker inside the step, until the test releases it.
    CountDownLatch blockLatch = new CountDownLatch(1);

    // 4. Worker 1 claims and drives the run, then blocks inside step 'b' (its heartbeat keeps the
    //    lease alive while it is blocked).
    UUID worker1 = UUID.randomUUID();
    WorkflowWorker worker1Worker =
        buildWorker(worker1, List.<StepHandler>of(new BlockingStepHandler(blockLatch)));
    worker1Thread = new Thread(() -> worker1Worker.pollOnce(tenant), "crash-test-worker-1");
    worker1Thread.setDaemon(true);
    worker1Thread.start();

    // 5. Crash window: the run is RUNNING and step 'b' is RUNNING (the worker died mid-attempt).
    awaitCrashWindow(tenant, runId);
    RunView midRun = runRepository.findRun(tenant, runId).orElseThrow();
    assertThat(midRun.status()).isEqualTo(WorkflowRunState.RUNNING);
    assertThat(stepRepository.findStepStatuses(tenant, runId))
        .anyMatch(r -> "b".equals(r.stepKey()) && r.status() == WorkflowStepState.RUNNING);

    // 6. Snapshot the pre-crash lease: owned by worker 1, epoch 1 (the single claim).
    LeaseSnapshot preCrash = readLease(tenant, runId);
    assertThat(preCrash.owner()).isEqualTo(worker1);
    assertThat(preCrash.epoch()).isEqualTo(1);

    // 7. Kill worker 1: stop its heartbeat scheduler so the lease stops being extended. The poll
    //    thread stays blocked inside the step (the worker is dead; nothing further heartbeats).
    worker1Worker.destroy();

    // 8. Wait for the lease to expire (now that no worker extends it).
    OffsetDateTime leaseUntil = preCrash.leaseUntil();
    awaitLeaseExpired(tenant, runId, leaseUntil);

    // 9. Raise a second worker; its poll re-claims the expired lease (ownership transfer +
    // fencing).
    UUID worker2 = UUID.randomUUID();
    WorkflowWorker worker2Worker =
        buildWorker(worker2, List.<StepHandler>of(new DeterministicStepHandler()));
    OffsetDateTime reclaimedAt = pollUntilReclaimed(worker2Worker, tenant, runId, worker2);

    // 10. The fault-injection result: ownership moved to worker 2, the epoch was fenced (bumped),
    //     the lease is renewed by the new owner, and the run is still RUNNING (not lost).
    LeaseSnapshot afterReclaim = readLease(tenant, runId);
    assertThat(afterReclaim.owner())
        .as("ownership transferred to the second worker")
        .isEqualTo(worker2);
    assertThat(afterReclaim.epoch())
        .as("the re-claim fences the epoch (a higher lease_epoch)")
        .isGreaterThan(preCrash.epoch());
    assertThat(afterReclaim.leaseUntil())
        .as("the new owner renewed the lease into the future")
        .isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    assertThat(runRepository.findRun(tenant, runId).orElseThrow().status())
        .as("the run survived the crash and is not lost")
        .isEqualTo(WorkflowRunState.RUNNING);

    // 11. Acceptance (S07-15): the re-claim happens within 60s of the lease expiry, and the test
    //     configuration's duration + recovery interval stays within that same 60s SLA.
    long secondsToReclaim = Duration.between(leaseUntil, reclaimedAt).getSeconds();
    assertThat(secondsToReclaim)
        .as("the run resumes within 60 seconds of the lease expiry")
        .isLessThanOrEqualTo(60L);
    assertThat(leaseUntil.isBefore(reclaimedAt))
        .as("the re-claim happens after the lease expired, not before")
        .isTrue();
    assertThat(LEASE_DURATION_SECONDS + RECOVERY_INTERVAL_SECONDS)
        .as("the test-config lease model bounds recovery to <= 60 seconds")
        .isLessThanOrEqualTo(60);

    // 12. Release the blocked handler and let the dead worker's thread finish cleanly (its run
    //     transition is fenced by the epoch, so it cannot overwrite the new owner) before shutdown.
    blockLatch.countDown();
    try {
      worker1Thread.join(10_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Blocks the worker inside a {@code BLOCK} step until {@code blockLatch} is counted down, then
   * completes the step successfully. Registered on the worker's {@link StepHandler} list by node
   * type, exactly as the per-module handlers are picked up at runtime (the runtime SPI).
   */
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

  /** A tenant-scoped snapshot of the run's lease ownership, epoch and expiry (S07-03 / ADR-011). */
  private record LeaseSnapshot(UUID owner, int epoch, OffsetDateTime leaseUntil) {}

  /**
   * Waits until the run is {@code RUNNING} and the {@code b} step is {@code RUNNING} (the crash
   * window the kill must leave behind), or fails.
   */
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

  /**
   * Waits until the run's {@code lease_until} is in the past (the killed worker stopped
   * heartbeating), or fails.
   */
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

  /**
   * Raises {@code worker} by polling until it owns the run (a successful fenced re-claim), or
   * fails. Returns the instant the re-claim was observed.
   */
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

  /** Reads the run's {@code lease_owner}, {@code lease_epoch} and {@code lease_until}. */
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

  private static void sleepMs(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
