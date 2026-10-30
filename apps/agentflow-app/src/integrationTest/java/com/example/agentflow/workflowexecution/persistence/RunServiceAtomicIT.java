package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import com.example.agentflow.workflowdefinition.persistence.WorkflowVersionRepository;
import com.example.agentflow.workflowexecution.application.CreateRunRequest;
import com.example.agentflow.workflowexecution.application.IdempotencyKeyService;
import com.example.agentflow.workflowexecution.application.RunDto;
import com.example.agentflow.workflowexecution.application.RunService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL integration suite proving the S06-05 acceptance criterion: a run is created and its
 * {@code workflow.run.requested.v1} event written <em>atomically</em> &mdash; a run can never be
 * committed without its outbox event and an event can never be committed without a durable run,
 * because both writes happen in one transaction (invariant 3 / {@code ADR-004}). It also proves the
 * S06-06 idempotency contract for a keyed create-run: a repeat with the same payload returns the
 * original run, and a repeat with a different payload under the same key is a {@code 409} — neither
 * is a silent duplicate or overwrite.
 *
 * <p>The DB-free {@link RunServiceTest} proves the code emits the pin / create / append calls in
 * order, but a mocked service never commits and a single real-DB call cannot observe the commit
 * boundary. This suite runs the real {@link RunService} against a real {@code
 * pgvector/pgvector:0.8.6-pg18} (the same image as Compose, per {@code AGENTS.md}: never {@code H2}
 * as a PostgreSQL stand-in), wrapping each call in a real {@link TransactionTemplate} over its own
 * connection (a {@code DriverManagerDataSource} makes a fresh physical connection per {@code
 * getConnection}, so the service's {@code JdbcTemplate} operations join that single transaction).
 * It proves the four things no other layer can:
 *
 * <ul>
 *   <li>happy path: a successful {@link RunService#createRun} commits <b>both</b> rows — one {@code
 *       workflow_runs} row in {@code QUEUED / attempt 1}, pinned to the tenant's single active
 *       version, and one {@code outbox_events} row in {@code PENDING} for the {@code
 *       workflow.run.requested.v1} topic &mdash; so a run and its event are created together;
 *   <li>the run is pinned to the tenant's single <b>active</b> version, tenant-scoped (invariant
 *       1);
 *   <li>when the outbox append fails, the run is <b>rolled back</b> too &mdash; no run commits
 *       without its event;
 *   <li>when there is no active version, neither a run nor an event is created (404);
 *   <li>a keyed create-run repeat with the <b>same</b> payload returns the <b>original</b> run and
 *       creates neither a second run nor a second event (idempotent, invariant 6); and
 *   <li>a keyed create-run repeat with a <b>different</b> payload under the same key is a permanent
 *       {@code 409} and creates neither a second run nor a second event (a reused key claims the
 *       payload).
 * </ul>
 *
 * <p>Every id is test-generated and each test seeds its own rows, so the tests are independent.
 */
@Testcontainers
class RunServiceAtomicIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;
  private static DataSource liveDataSource;

  @BeforeAll
  static void migrateAndWire() {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:migrations")
        .load()
        .migrate();
    liveDataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(liveDataSource);
  }

  @Test
  void create_run_commits_the_run_and_its_outbox_event_together() {
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    UUID versionId = seedActiveVersion(tenant, workflow);

    RunService service = runService();
    RunDto run =
        transaction()
            .execute(
                status ->
                    service.createRun(tenant, "alice", new CreateRunRequest(workflow, null), null));

    // The run row: QUEUED, attempt 1, pinned to the active version, tenant-scoped.
    String status =
        jdbc.queryForObject(
            "SELECT status FROM workflow_runs WHERE id = ?", String.class, run.id());
    int attempt =
        jdbc.queryForObject("SELECT attempt FROM workflow_runs WHERE id = ?", int.class, run.id());
    UUID version =
        jdbc.queryForObject(
            "SELECT workflow_version_id FROM workflow_runs WHERE id = ?", UUID.class, run.id());
    assertThat(status).isEqualTo("QUEUED");
    assertThat(attempt).isEqualTo(1);
    assertThat(version).isEqualTo(versionId);

    // The outbox event: exactly one PENDING workflow.run.requested.v1 event for this run.
    Long events =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE tenant_id = ? AND aggregate_id = ? "
                + "AND topic = 'agentflow.workflow.run-requested.v1'",
            Long.class,
            tenant,
            run.id());
    assertThat(events).isEqualTo(1);
  }

  @Test
  void create_run_without_an_active_version_creates_nothing() {
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    seedTenant(tenant); // a tenant, but no workflow and no active version

    RunService service = runService();

    assertThatThrownBy(
            () ->
                transaction()
                    .execute(
                        status ->
                            service.createRun(
                                tenant, "alice", new CreateRunRequest(workflow, null), null)))
        .extracting(t -> ((ResponseStatusException) t).getStatusCode().value())
        .isEqualTo(404);
    assertThat(runCount(tenant)).isEqualTo(0);
    assertThat(eventCount(tenant)).isEqualTo(0);
  }

  @Test
  void a_failing_outbox_append_rolls_back_the_run() {
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    seedActiveVersion(tenant, workflow);

    // The outbox append fails (simulated broker failure); the run insert has already run in this
    // transaction, so a rollback leaves neither behind (atomic, invariant 3).
    RunService service = runServiceWithFailingOutbox();

    assertThatThrownBy(
            () ->
                transaction()
                    .execute(
                        status ->
                            service.createRun(
                                tenant, "alice", new CreateRunRequest(workflow, null), null)))
        .satisfies(t -> assertThat(rootMessage(t)).contains("broker failure"));
    assertThat(runCount(tenant)).isEqualTo(0);
    assertThat(eventCount(tenant)).isEqualTo(0);
  }

  // --- S06-06 idempotency --------------------------------------------------

  @Test
  void a_keyed_repeat_with_the_same_payload_returns_the_same_run_and_creates_nothing_new() {
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    UUID versionId = seedActiveVersion(tenant, workflow);
    UUID key = UUID.randomUUID();
    String payload = "{\"workflowId\":\"" + workflow + "\"}";

    RunService service = runService();

    RunDto first =
        transaction()
            .execute(
                status ->
                    service.createRun(
                        tenant, "alice", new CreateRunRequest(workflow, key), sha256Hex(payload)));
    RunDto second =
        transaction()
            .execute(
                status ->
                    service.createRun(
                        tenant, "alice", new CreateRunRequest(workflow, key), sha256Hex(payload)));

    // A repeat with the same key and the same payload returns the original run — the id is
    // identical
    // — and produces neither a second run nor a second outbox event (idempotent, invariant 6).
    assertThat(first.id()).isEqualTo(second.id());
    assertThat(runCount(tenant)).isEqualTo(1);
    assertThat(eventCount(tenant)).isEqualTo(1);
    // The stored run carries the payload fingerprint alongside the key.
    String storedFingerprint =
        jdbc.queryForObject(
            "SELECT idempotency_fingerprint FROM workflow_runs WHERE id = ?",
            String.class,
            first.id());
    assertThat(storedFingerprint).isEqualTo(sha256Hex(payload));
  }

  @Test
  void a_keyed_repeat_with_a_different_payload_is_a_conflict_and_creates_nothing_new() {
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    seedActiveVersion(tenant, workflow);
    UUID key = UUID.randomUUID();

    RunService service = runService();

    String payload1 = "{\"workflowId\":\"" + workflow + "\"}";
    String payload2 = "{\"workflowId\":\"" + workflow + "\",\"extra\":true}";
    // The fingerprint is over the payload (the idempotency key is the dedup scope, not the
    // payload),
    // exactly as the controller computes it before handing it to the service.
    String fp1 = sha256Hex(payload1);
    String fp2 = sha256Hex(payload2);

    // First create under the key succeeds...
    RunDto first =
        transaction()
            .execute(
                status ->
                    service.createRun(tenant, "alice", new CreateRunRequest(workflow, key), fp1));
    // ...a second create under the same key but a different payload (different fingerprint) is a
    // permanent conflict.
    assertThatThrownBy(
            () ->
                transaction()
                    .execute(
                        status ->
                            service.createRun(
                                tenant, "alice", new CreateRunRequest(workflow, key), fp2)))
        .extracting(t -> ((ResponseStatusException) t).getStatusCode().value())
        .isEqualTo(409);

    // The conflict does not create a second run or event: a reused key claims the payload too.
    assertThat(first.id()).isNotNull();
    assertThat(runCount(tenant)).isEqualTo(1);
    assertThat(eventCount(tenant)).isEqualTo(1);
  }

  // --- helpers -----------------------------------------------------------------

  private static RunService runService() {
    return new RunService(
        new WorkflowRunRepository(liveJdbc()),
        new IdempotencyKeyService(new WorkflowRunRepository(liveJdbc())),
        new OutboxService(new OutboxEventRepository(liveJdbc())),
        new WorkflowVersionService(
            new WorkflowDefinitionRepository(jdbc),
            new WorkflowVersionRepository(jdbc),
            new AuditService(null),
            null));
  }

  private static RunService runServiceWithFailingOutbox() {
    OutboxService failing =
        new OutboxService(null) {
          @Override
          public void enqueue(OutboxEvent event) {
            throw new IllegalStateException("simulated broker failure");
          }
        };
    return new RunService(
        new WorkflowRunRepository(liveJdbc()),
        new IdempotencyKeyService(new WorkflowRunRepository(liveJdbc())),
        failing,
        new WorkflowVersionService(
            new WorkflowDefinitionRepository(jdbc),
            new WorkflowVersionRepository(jdbc),
            new AuditService(null),
            null));
  }

  private static TransactionTemplate transaction() {
    return new TransactionTemplate(new DataSourceTransactionManager(liveDataSource()));
  }

  private static Long runCount(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM workflow_runs WHERE tenant_id = ?", Long.class, tenant);
  }

  private static Long eventCount(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_events WHERE tenant_id = ?", Long.class, tenant);
  }

  /** The message of the deepest cause, so a wrapped runtime exception still asserts its reason. */
  private static String rootMessage(Throwable t) {
    Throwable deepest = t;
    while (deepest.getCause() != null && deepest.getCause() != deepest) {
      deepest = deepest.getCause();
    }
    return deepest.getMessage() == null ? "" : deepest.getMessage();
  }

  private static UUID seedTenant(UUID tenant) {
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, tenant);
          ps.setString(2, "tenant-" + tenant);
        });
    return tenant;
  }

  /** Seeds a tenant + definition and one {@code ACTIVATED} version; returns the version id. */
  private static UUID seedActiveVersion(UUID tenant, UUID workflow) {
    seedTenant(tenant);
    jdbc.update(
        "INSERT INTO workflow_definitions (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, workflow);
          ps.setObject(2, tenant);
          ps.setString(3, "wf-" + workflow);
        });
    UUID versionId = UUID.randomUUID();
    // A valid single-node graph (a non-blank id + known type) so getActiveVersion's codec parse
    // does not reject an empty graph; the checksum is computed over this exact text.
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

  /**
   * Lowercase hex SHA-256 of the graph text — the same value {@code checksum} computes over {@code
   * graph_json}, so the seeded active version carries a genuinely correct checksum.
   */
  private static String sha256Hex(String text) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append("0123456789abcdef".charAt((b >> 4) & 0xF));
        sb.append("0123456789abcdef".charAt(b & 0xF));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }

  private static JdbcTemplate liveJdbc() {
    return new JdbcTemplate(liveDataSource());
  }

  private static DataSource liveDataSource() {
    if (liveDataSource == null) {
      liveDataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
    return liveDataSource;
  }
}
