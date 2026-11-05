package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.workflowexecution.domain.ConflictException;
import java.util.List;
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
 * PostgreSQL integration suite for the {@code workflow_step_attempt_history} table (S07-14),
 * exercising the real {@link WorkflowStepAttemptHistoryRepository} against a real {@code
 * pgvector/pgvector:0.8.6-pg18} (the same image as Compose, per {@code AGENTS.md}: never {@code H2}
 * as a PostgreSQL stand-in).
 *
 * <p>The DB-free assertions on the repository (the SQL text and the bound arguments) prove the code
 * emits the right statements, but a mocked {@link JdbcTemplate} never runs SQL and a
 * single-threaded real-DB call cannot observe them. This suite closes that gap: the {@code
 * MAX(attempt) + 1} attempt numbering, the {@code COALESCE(MAX(attempt)} fencing on conclude, the
 * {@code JOIN ... WHERE r.tenant_id = ?} tenant scoping of the read, the {@code setNull} bind of a
 * {@code null} worker, and the {@code TIMESTAMPTZ &rarr; OffsetDateTime} round-trip all run on a
 * real driver over a real row.
 *
 * <p>The table stores no {@code tenant_id}: tenant isolation flows through the parent {@code
 * workflow_runs} row, so the reads and the fenced write each re-check the parent's {@code
 * tenant_id} in SQL (invariant 1) &mdash; a cross-tenant run is indistinguishable from a missing
 * one. Every test seeds its own run, so the tests are independent.
 */
@Testcontainers
class WorkflowStepAttemptHistoryRepositoryIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;
  private static WorkflowStepAttemptHistoryRepository repository;

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
    repository = new WorkflowStepAttemptHistoryRepository(jdbc);
  }

  // --- happy path: begin then conclude records a durable, tenant-scoped row --------------------

  @Test
  void begin_then_conclude_records_a_row_with_the_safe_error_category() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run);

    // The attempt begins when the step enters {@code RUNNING}: a row with a 1-based attempt number
    // and no outcome yet (invariant 6 &mdash; the side effect has not happened).
    repository.beginAttempt(tenant, run, "llm_plan", "worker-1");
    assertThat(readAttempt(tenant, run, "llm_plan", 1).outcome()).isNull();

    // The attempt concludes when the step leaves {@code RUNNING}, with the safe {@code ErrorClass}
    // token and the redacted reason &mdash; never the raw error or the secret payload (invariant 8
    // / S07-14 "safe error category").
    repository.concludeAttempt(
        tenant, run, "llm_plan", "FAILED", "transient_infra", "connection refused");

    AttemptHistoryRow row = readAttempt(tenant, run, "llm_plan", 1);
    assertThat(row.attempt()).isEqualTo(1);
    assertThat(row.stepKey()).isEqualTo("llm_plan");
    assertThat(row.workerInstance()).isEqualTo("worker-1");
    assertThat(row.outcome()).isEqualTo("FAILED");
    // the safe category, not the raw message
    assertThat(row.errorClass()).isEqualTo("transient_infra");
    assertThat(row.errorSummary()).isEqualTo("connection refused");
    // TIMESTAMPTZ round-trips to OffsetDateTime without a cast (the read uses the typed getObject)
    assertThat(row.startedAt()).isNotNull();
    assertThat(row.finishedAt()).isNotNull();
    assertThat(row.finishedAt()).isAfterOrEqualTo(row.startedAt());
  }

  // --- the retry gets a fresh, monotonic attempt number ----------------------------------------

  @Test
  void a_retry_gets_a_monotonic_attempt_number() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run);

    repository.beginAttempt(tenant, run, "llm_plan", "worker-1");
    repository.concludeAttempt(tenant, run, "llm_plan", "RETRY_WAIT", null, null);

    // A retry is a fresh attempt: MAX(attempt) + 1, monotonic per (run, step_key).
    repository.beginAttempt(tenant, run, "llm_plan", "worker-1");
    repository.concludeAttempt(tenant, run, "llm_plan", "SUCCEEDED", null, null);

    List<AttemptHistoryRow> history = repository.findHistory(tenant, run);
    assertThat(history).hasSize(2);
    // ordered by step_key then attempt (ascending), so each step's attempts group chronologically
    assertThat(history.get(0).stepKey()).isEqualTo("llm_plan");
    assertThat(history.get(0).attempt()).isEqualTo(1);
    assertThat(history.get(0).outcome()).isEqualTo("RETRY_WAIT");
    assertThat(history.get(1).stepKey()).isEqualTo("llm_plan");
    assertThat(history.get(1).attempt()).isEqualTo(2);
    assertThat(history.get(1).outcome()).isEqualTo("SUCCEEDED");
  }

  // --- ambiguous external outcome (S07-16) -------------------------------------------------

  @Test
  void a_provider_timeout_is_recorded_as_a_retryable_ambiguous_attempt() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run);

    // A provider timed out before returning a response, so the side effect *may* have completed.
    // The attempt begins when the step enters RUNNING; the handler surfaced PROVIDER_TIMEOUT, and
    // the classifier (S07-11) treats it as a transient, retryable fault.
    repository.beginAttempt(tenant, run, "external_call", "worker-1");
    repository.concludeAttempt(
        tenant, run, "external_call", "RETRY_WAIT", "provider_timeout", "response lost on timeout");

    // The ambiguous attempt is visible in history with its safe category (invariant 8): the
    // operator sees "retryable provider timeout" without the secret payload, so the run is
    // scheduled to be re-claimed — the step is neither SUCCEEDED nor FAILED.
    AttemptHistoryRow row = readAttempt(tenant, run, "external_call", 1);
    assertThat(row.outcome()).isEqualTo("RETRY_WAIT");
    assertThat(row.errorClass()).isEqualTo("provider_timeout");
    assertThat(row.errorSummary()).isEqualTo("response lost on timeout");

    // A retry is a fresh, monotonic attempt: a re-run after an ambiguous outcome is possible, and
    // the attempt counter makes it observable (the side effect may have run more than once).
    repository.beginAttempt(tenant, run, "external_call", "worker-2");
    repository.concludeAttempt(tenant, run, "external_call", "SUCCEEDED", null, null);
    assertThat(readAttempt(tenant, run, "external_call", 2).attempt()).isEqualTo(2);
  }

  @Test
  void an_in_flight_attempt_marks_the_ambiguous_crash_window_with_a_null_outcome() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run);

    // The worker begins the attempt (the step is RUNNING) and then crashes mid-attempt before the
    // outcome is recorded: the response is unknown, so the attempt is ambiguous.
    repository.beginAttempt(tenant, run, "external_call", "worker-1");

    // The history records the in-flight attempt with a NULL outcome — the crash-window marker,
    // queryable tenant-scoped (invariant 1) — never a clean success/failure. Recovery re-claims it.
    AttemptHistoryRow row = readAttempt(tenant, run, "external_call", 1);
    assertThat(row.outcome()).isNull();
    assertThat(row.finishedAt()).isNull();
    assertThat(row.workerInstance()).isEqualTo("worker-1");
    assertThat(repository.findHistory(tenant, run)).hasSize(1);
  }

  // --- write side of tenant isolation (invariant 1) --------------------------------------------

  @Test
  void conclude_scopes_by_tenant_and_a_foreign_run_is_a_conflict() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run);

    repository.beginAttempt(tenant, run, "llm_plan", "worker-1");

    // Another tenant cannot write this run's attempt: the {@code EXISTS} guard matches only a row
    // whose parent run belongs to the caller's tenant (invariant 1). A foreign conclude is a
    // conflict, so it never silently overwrites the current owner's history.
    assertThatThrownBy(
            () ->
                repository.concludeAttempt(
                    UUID.randomUUID(), run, "llm_plan", "FAILED", null, null))
        .isInstanceOf(ConflictException.class);

    // The row is untouched: the foreign write changed nothing (still no outcome).
    assertThat(readAttempt(tenant, run, "llm_plan", 1).outcome()).isNull();
  }

  // --- fencing + coalesce on conclude ----------------------------------------------------------

  @Test
  void a_duplicate_conclude_is_fenced_to_the_highest_attempt_and_keeps_a_prior_error() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run);

    repository.beginAttempt(tenant, run, "llm_plan", "worker-1");
    repository.concludeAttempt(tenant, run, "llm_plan", "FAILED", "transient_infra", "boom");

    // A second conclude has no fresh attempt to fence, so it targets the highest attempt (1) again;
    // the {@code COALESCE} keeps the fault the first conclude recorded instead of clobbering it
    // with a blank value on the later clean outcome.
    repository.concludeAttempt(tenant, run, "llm_plan", "SUCCEEDED", null, null);

    AttemptHistoryRow row = readAttempt(tenant, run, "llm_plan", 1);
    assertThat(row.attempt()).isEqualTo(1);
    // the outcome is overwritten by the later conclude ...
    assertThat(row.outcome()).isEqualTo("SUCCEEDED");
    // ... while the prior safe error survives it
    assertThat(row.errorClass()).isEqualTo("transient_infra");
  }

  // --- read side of tenant isolation (invariant 1) ---------------------------------------------

  @Test
  void find_history_returns_no_rows_for_a_run_of_another_tenant() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run);

    repository.beginAttempt(tenant, run, "llm_plan", "worker-1");

    // The {@code JOIN ... WHERE r.tenant_id = ?} excludes a run of another tenant, so a foreign
    // read
    // returns no rows &mdash; indistinguishable from a missing run (invariant 1).
    assertThat(repository.findHistory(UUID.randomUUID(), run)).isEmpty();
  }

  // --- a null worker binds with setNull and round-trips ----------------------------------------

  @Test
  void a_null_worker_instance_is_stored_and_round_trips() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run);

    // An attempt begun without an actor stores a NULL worker_instance (the write setNull path).
    repository.beginAttempt(tenant, run, "llm_plan", null);

    assertThat(readAttempt(tenant, run, "llm_plan", 1).workerInstance()).isNull();
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static AttemptHistoryRow readAttempt(UUID tenant, UUID run, String stepKey, int attempt) {
    return repository.findHistory(tenant, run).stream()
        .filter(r -> r.stepKey().equals(stepKey) && r.attempt() == attempt)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no attempt row for " + stepKey + "#" + attempt));
  }

  /**
   * Seeds a tenant, definition, active version and a {@code QUEUED} run (the FK graph for the run).
   */
  private static void seedRun(UUID tenant, UUID run) {
    UUID definitionId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
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
          ps.setString(3, "wf-" + run);
        });
    jdbc.update(
        "INSERT INTO workflow_definition_versions "
            + "(id, workflow_id, version, graph_json, checksum) VALUES (?, ?, 1, ?::jsonb, ?)",
        ps -> {
          ps.setObject(1, versionId);
          ps.setObject(2, definitionId);
          ps.setString(3, "{\"nodes\":[]}");
          ps.setString(4, "c");
        });
    jdbc.update(
        "INSERT INTO workflow_runs (id, tenant_id, workflow_version_id, status) VALUES (?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, run);
          ps.setObject(2, tenant);
          ps.setObject(3, versionId);
          ps.setString(4, "QUEUED");
        });
  }
}
