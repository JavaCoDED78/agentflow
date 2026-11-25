package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import com.example.agentflow.messaging.persistence.WorkflowEventRepository;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import com.example.agentflow.workflowdefinition.persistence.WorkflowVersionRepository;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL outage / unknown-commit acceptance tests (S14-07) on <b>real PostgreSQL</b>
 * (Testcontainers, {@code pgvector/pgvector:0.8.6-pg18} — the same image as Compose, per {@code
 * AGENTS.md}: never {@code H2} as a stand-in for the real engine). They close the dedicated
 * PostgreSQL-outage row of the Sprint 14 failure matrix ({@code docs/testing/failure-matrix.md}),
 * whose known gap was "the connection-loss-mid-create-run / unknown-commit with idempotency-key
 * re-issue is not written".
 *
 * <p>The guarantee under test is the Sprint 14 acceptance criterion — <b>no accepted run without
 * durable state/outbox, and no duplicate on retry</b> — against a real outage rather than a dead
 * port. The outage is an actual <b>connection cut</b>: the gated write is held open while its
 * backend session is killed with {@code pg_terminate_backend} from a separate admin connection,
 * while the transaction is open and the write is in flight. The transaction cannot commit and the
 * history/run it describes never lands. The client observes a connection failure and does
 * <i>not</i> know whether the transaction committed — the genuine unknown-commit view. The request
 * is then re-issued on a fresh connection pool; the retry must produce exactly one run and one
 * event, moved together, never a silent duplicate.
 *
 * <p>Two failure modes exercise the production code paths end to end:
 *
 * <ul>
 *   <li><b>create-run cut mid-transaction</b> — the run insert has already run inside the
 *       transaction, the outbox append is gated so it provably blocks <i>while</i> the transaction
 *       is open; the backend is then killed and the gate released into the dead window. The client
 *       gets an error and retries with the <i>same</i> idempotency key and payload fingerprint; the
 *       retry must produce exactly one run and one outbox event, moved together, never a silent
 *       duplicate.
 *   <li><b>worker update cut mid-transaction</b> — the worker's conditional state transition (run
 *       {@code QUEUED → RUNNING}) has already applied inside the transaction, its history event
 *       append is gated, the backend is killed, and the write fails. The worker is retried through
 *       a fresh connection pool; the retry must land exactly one transition and exactly one {@code
 *       workflow_events} row, never a partial state.
 * </ul>
 *
 * <p>The failure is modelled at the persistence seam, not by hand-editing a row: the production
 * {@link RunService} / {@link RunStateMachineService} paths are what leave the state on disk, and
 * the cut is a real session termination, never a hand-set row. A fresh container per test keeps the
 * two independent. The pre-retry state is deliberately <b>not</b> asserted as "rolled back" from
 * the client's point of view — the client cannot know it; what is asserted is that the retry is
 * safe in either case and that what lands is always consistent. The durable state the retry sees is
 * the schema + data that survived (the server stays up); the absorbed transaction is absent by
 * construction, since the kill happens while the write is in flight.
 */
class PostgresOutageUnknownCommitIT {

  private PostgreSQLContainer postgres;

  /**
   * One DataSource per lifecycle stage, shared by the transaction and every repository built from
   * it — a TransactionTemplate binds by DataSource identity, so the same object is required for the
   * ambient transaction to be joined by the service's JdbcTemplates. A separate admin pool is what
   * cuts the gated connection (it must not be part of the transaction under test).
   */
  private DataSource liveDataSource;

  private JdbcTemplate jdbc;
  private JdbcTemplate adminJdbc;

  @BeforeEach
  void bootPostgresAndMigrate() {
    postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");
    postgres.start();
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:migrations")
        .load()
        .migrate();
    liveDataSource = freshDataSource();
    jdbc = new JdbcTemplate(liveDataSource);
    // A separate admin connection is what cuts the transaction's backend — the same role the
    // operator holds when the database goes down mid-transaction.
    adminJdbc = new JdbcTemplate(freshDataSource());
  }

  @AfterEach
  void stopPostgres() {
    if (postgres != null && postgres.isRunning()) {
      postgres.stop();
    }
  }

  private DataSource freshDataSource() {
    return new DriverManagerDataSource(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(new DataSourceTransactionManager(liveDataSource));
  }

  /** Kill the backend holding the gated transaction — the connection cut itself. */
  private void cutTheGatedConnection() {
    // The gated connection is the one holding an open transaction — the admin connection runs a
    // single query and never holds one. The kill is asynchronous, so the gate must be released
    // only once the session is really gone, or the gated write races the SIGTERM and commits on a
    // not-yet-dead socket.
    for (int i = 0; i < 100; i++) {
      // The admin's own query is a transaction too (every statement is), so exclude it —
      // only the gated connection's open transaction is terminated.
      adminJdbc.execute(
          "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
              + "WHERE xact_start IS NOT NULL AND pid <> pg_backend_pid()");
      long openTxCount =
          adminJdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity "
                  + "WHERE xact_start IS NOT NULL AND pid <> pg_backend_pid()",
              Long.class);
      if (openTxCount == 0) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for the cut to take effect");
      }
    }
    throw new IllegalStateException("the gated connection did not die after pg_terminate_backend");
  }

  // --- create-run cut mid-transaction ------------------------------------------------

  /**
   * S14-07 (create-run): the backend is killed while a keyed create-run is blocked at the outbox
   * append (the last write of the transaction). The first attempt fails with an error and the
   * client cannot know whether the transaction committed. The same request is re-issued with the
   * same idempotency key and payload fingerprint — the retry must produce exactly one run and one
   * outbox event, moved together, never a duplicate.
   */
  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  void create_run_cut_mid_transaction_retries_with_the_same_key_and_creates_no_duplicate()
      throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    UUID versionId = seedActiveVersion(tenant, workflow);
    UUID key = UUID.randomUUID();
    String payload = "{\"workflowId\":\"" + workflow + "\"}";
    String fingerprint = sha256Hex(payload);

    // The outbox append is the LAST write in the create-run transaction: the run row is already
    // applied when the gate blocks. That is the unknown-commit window.
    CountDownLatch armed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    OutboxService gated =
        new OutboxService(new OutboxEventRepository(jdbc)) {
          @Override
          public void enqueue(OutboxEvent event) {
            armed.countDown();
            awaitQuietly(release, 30, TimeUnit.SECONDS);
            super.enqueue(event);
          }
        };
    RunService service = runServiceWith(gated, jdbc);

    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    Thread createThread =
        new Thread(
            () -> {
              try {
                transaction()
                    .execute(
                        status ->
                            service.createRun(
                                tenant, "alice", new CreateRunRequest(workflow, key), fingerprint));
              } catch (Throwable t) {
                firstFailure.set(t);
              }
            },
            "postgres-outage-create-run");
    createThread.setDaemon(true);
    createThread.start();

    // The gate arms when the outbox append is reached; only then is the cut deterministic.
    try {
      assertThat(armed.await(60, TimeUnit.SECONDS))
          .as("create-run must reach the outbox enqueue before the cut")
          .isTrue();
      cutTheGatedConnection(); // cut the connection mid-transaction
    } finally {
      release.countDown(); // never leave the worker thread parked on a dead gate
    }
    joinWithTimeout(createThread, 120_000);

    assertThat(createThread.isAlive()).as("the create-run must finish after the cut").isFalse();
    assertThat(firstFailure.get())
        .as("a connection cut mid-transaction must fail the create-run, never report success")
        .isNotNull();

    // Before the retry: whatever the first attempt did, runs and outbox rows move together —
    // the create is one transaction. The kill happens while the write is in flight, so the run
    // and the event are absent together.
    assertThat(runCount(tenant)).as("the rolled-back create-run must leave no run").isZero();
    assertThat(eventCount(tenant)).as("the rolled-back create-run must leave no event").isZero();

    // The client never saw a success or a rollback — only an error it cannot interpret. The retry
    // is the safety net: same request, same key, fresh pool.
    rebuildFreshDatabase();
    RunService fresh = runServiceWith(new OutboxService(new OutboxEventRepository(jdbc)), jdbc);
    RunDto retried =
        transaction()
            .execute(
                status ->
                    fresh.createRun(
                        tenant, "alice", new CreateRunRequest(workflow, key), fingerprint));

    assertThat(retried).as("the retry must succeed after the recovery").isNotNull();
    long runsAfter = runCount(tenant);
    long eventsAfter = eventCount(tenant);
    assertThat(runsAfter).as("the retry must not create a second run").isEqualTo(1);
    assertThat(eventsAfter).as("the retry must not create a second event").isEqualTo(1);
    assertThat(runsAfter).isEqualTo(eventsAfter);
    assertThat(retried.id()).isEqualTo(onlyRunId(tenant));
    assertThat(runStatus(tenant)).isEqualTo("QUEUED");
    assertThat(runVersion(tenant)).isEqualTo(versionId);
  }

  // --- worker update cut mid-transaction -------------------------------------------------

  /**
   * S14-07 (worker update): the backend is killed while the worker's conditional state transition
   * (run {@code QUEUED → RUNNING}) is blocked at the history-event append — the last write of the
   * worker's transition transaction. The first attempt fails with an error and the worker cannot
   * know whether the transition committed. The same fenced transition is re-issued through a fresh
   * connection pool; the retry must land exactly one transition and exactly one history row, never
   * a partial state.
   */
  @Test
  @Timeout(value = 300, unit = TimeUnit.SECONDS)
  void worker_update_cut_mid_transaction_retries_cleanly_without_a_duplicate() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    UUID versionId = seedActiveVersion(tenant, workflow);
    UUID runId = UUID.randomUUID();

    WorkflowRunRepository runRepository = new WorkflowRunRepository(jdbc);
    WorkflowStepRunRepository stepRepository = new WorkflowStepRunRepository(jdbc);

    runRepository.create(runId, tenant, versionId, null, null, "worker@agentflow");

    // Claim the run the way the worker does — the claimed epoch fences every later write.
    AgentFlowProperties properties = new AgentFlowProperties();
    RunLeaseService lease = new RunLeaseService(runRepository, Clock.systemUTC(), properties);
    int claimedEpoch = lease.claim(tenant, runId);
    assertThat(claimedEpoch).isPositive();

    // The history event append is the LAST write in the worker's transition transaction: the
    // conditional run update has already applied when the gate blocks.
    CountDownLatch armed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    WorkflowEventService gatedEvents =
        new WorkflowEventService(new WorkflowEventRepository(jdbc)) {
          @Override
          public UUID append(
              UUID tenantId, UUID runId, UUID stepRunId, String eventType, String payload) {
            armed.countDown();
            awaitQuietly(release, 30, TimeUnit.SECONDS);
            return super.append(tenantId, runId, stepRunId, eventType, payload);
          }
        };
    RunStateMachineService gatedService =
        new RunStateMachineService(runRepository, stepRepository, null, gatedEvents, null);

    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    Thread workerThread =
        new Thread(
            () -> {
              try {
                transaction()
                    .execute(
                        status ->
                            gatedService.transitionRun(
                                tenant,
                                runId,
                                WorkflowRunState.RUNNING,
                                "run.started",
                                "worker@agentflow",
                                claimedEpoch));
              } catch (Throwable t) {
                firstFailure.set(t);
              }
            },
            "postgres-outage-worker-update");
    workerThread.setDaemon(true);
    workerThread.start();

    try {
      assertThat(armed.await(60, TimeUnit.SECONDS))
          .as("the worker transition must reach the history append before the cut")
          .isTrue();
      cutTheGatedConnection(); // cut the connection mid-transaction
    } finally {
      release.countDown(); // never leave the worker thread parked on a dead gate
    }
    joinWithTimeout(workerThread, 120_000);

    assertThat(workerThread.isAlive())
        .as("the worker transition must finish after the cut")
        .isFalse();
    assertThat(firstFailure.get())
        .as("a connection cut mid-transaction must fail the transition, never report success")
        .isNotNull();

    // Before the retry: the kill happens while the write is in flight, so the transition cannot
    // have committed — the run and its history are absent together, never a moved run without its
    // transition fact.
    assertThat(workflowEventsFor(tenant, runId))
        .as("the rolled-back transition must leave no history row")
        .isEmpty();
    assertThat(runStatusFor(tenant, runId))
        .as("the transaction must have rolled the state change back")
        .isEqualTo("QUEUED");

    // The client never saw a success or a rollback — only an error it cannot interpret. The retry
    // is the safety net: same fenced transition, fresh pool.
    rebuildFreshDatabase();
    WorkflowRunRepository freshRunRepository = new WorkflowRunRepository(jdbc);
    WorkflowStepRunRepository freshStepRepository = new WorkflowStepRunRepository(jdbc);
    WorkflowEventService freshEvents = new WorkflowEventService(new WorkflowEventRepository(jdbc));
    RunStateMachineService fresh =
        new RunStateMachineService(
            freshRunRepository, freshStepRepository, null, freshEvents, null);

    WorkflowRunState retried =
        transaction()
            .execute(
                status ->
                    fresh.transitionRun(
                        tenant,
                        runId,
                        WorkflowRunState.RUNNING,
                        "run.started",
                        "worker@agentflow",
                        claimedEpoch));

    assertThat(retried)
        .as("the retry must succeed after the recovery")
        .isEqualTo(WorkflowRunState.RUNNING);
    assertThat(runStatusFor(tenant, runId)).isEqualTo("RUNNING");
    // A run moved through the state machine leaves exactly one history row for the transition —
    // never a duplicate, never a silently missing fact.
    assertThat(workflowEventsFor(tenant, runId))
        .as("exactly one transition fact after the retry")
        .containsExactly("run.started");
  }

  // --- helpers ---------------------------------------------------------------------

  /** Discard the dead pool and rebuild one against the same server. */
  private void rebuildFreshDatabase() {
    liveDataSource = freshDataSource();
    jdbc = new JdbcTemplate(liveDataSource);
    adminJdbc = new JdbcTemplate(freshDataSource());
  }

  private RunService runServiceWith(OutboxService outbox, JdbcTemplate jdbc) {
    return new RunService(
        new WorkflowRunRepository(jdbc),
        new IdempotencyKeyService(new WorkflowRunRepository(jdbc)),
        outbox,
        new WorkflowVersionService(
            new WorkflowDefinitionRepository(jdbc),
            new WorkflowVersionRepository(jdbc),
            new AuditService(null),
            null),
        null,
        null,
        null,
        null);
  }

  private long runCount(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM workflow_runs WHERE tenant_id = ?", Long.class, tenant);
  }

  private long eventCount(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_events WHERE tenant_id = ?", Long.class, tenant);
  }

  private UUID onlyRunId(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT id FROM workflow_runs WHERE tenant_id = ?", UUID.class, tenant);
  }

  private String runStatus(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT status FROM workflow_runs WHERE tenant_id = ?", String.class, tenant);
  }

  private UUID runVersion(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT workflow_version_id FROM workflow_runs WHERE tenant_id = ?", UUID.class, tenant);
  }

  private String runStatusFor(UUID tenant, UUID runId) {
    return jdbc.queryForObject(
        "SELECT status FROM workflow_runs WHERE tenant_id = ? AND id = ?",
        String.class,
        tenant,
        runId);
  }

  private java.util.List<String> workflowEventsFor(UUID tenant, UUID runId) {
    return jdbc.query(
        "SELECT event_type FROM workflow_events WHERE tenant_id = ? AND run_id = ?",
        (rs, n) -> rs.getString(1),
        tenant,
        runId);
  }

  private static void awaitQuietly(CountDownLatch latch, long timeout, TimeUnit unit) {
    try {
      latch.await(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void joinWithTimeout(Thread thread, long timeoutMillis) {
    try {
      thread.join(timeoutMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String sha256Hex(String value) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append("0123456789abcdef".charAt((b >> 4) & 0xF));
        hex.append("0123456789abcdef".charAt(b & 0xF));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }

  private UUID seedActiveVersion(UUID tenant, UUID workflow) {
    // A tenant with a DRAFT definition and an ACTIVATED version — the run pins to it.
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, tenant);
          ps.setString(2, "tenant-" + tenant);
        });
    jdbc.update(
        "INSERT INTO workflow_definitions (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, workflow);
          ps.setObject(2, tenant);
          ps.setString(3, "wf-" + workflow);
        });
    UUID versionId = UUID.randomUUID();
    String validGraph =
        "{\"schemaVersion\":\"1.0.0\",\"nodes\":[{\"id\":\"n1\",\"type\":\"FINALIZE\"}],\"edges\":[]}";
    jdbc.update(
        "INSERT INTO workflow_definition_versions "
            + "(id, workflow_id, version, graph_json, status, checksum) "
            + "VALUES (?, ?, 1, ?::jsonb, 'ACTIVATED', ?)",
        ps -> {
          ps.setObject(1, versionId);
          ps.setObject(2, workflow);
          ps.setString(3, validGraph);
          ps.setString(4, sha256Hex(validGraph));
        });
    return versionId;
  }
}
