package com.example.agentflow.toolexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.toolexecution.domain.ToolInvocationOutcome;
import com.example.agentflow.toolexecution.domain.ToolInvocationRecord;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-PostgreSQL integration test for the {@code tool_invocations} path (S08-13). The {@code
 * V3_3_17} migration is applied (together with the rest of {@code classpath:migrations}) against a
 * real {@code pgvector/pgvector:0.8.6-pg18} container, and the {@link ToolInvocationRepository} is
 * exercised on it: a record round-trips, a run's history reads back in append order, and the tenant
 * predicate is part of the query itself — a cross-tenant run id is indistinguishable from a missing
 * one (invariant 1). No H2 is used as a stand-in for PostgreSQL (S03-08).
 */
@Testcontainers
class ToolInvocationRepositoryIT {

  @Container
  static PostgreSQLContainer pg =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.6-pg18"))
          .withDatabaseName("agentflow");

  private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID RUN = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
            .locations("classpath:migrations")
            .load();
    flyway.migrate();
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
  }

  @BeforeEach
  void clean() {
    // The Testcontainers DB is shared across the @Test methods in this class (Flyway runs once in
    // @BeforeAll), so reset the history table between tests to keep each one isolated.
    jdbc.update("DELETE FROM tool_invocations");
  }

  @Test
  void record_round_trips_through_the_history_store() {
    ToolInvocationRepository repo = new ToolInvocationRepository(jdbc);
    ToolInvocationRecord record =
        ToolInvocationRecord.recorded(
            TENANT_A,
            RUN,
            "step-1",
            "calculator",
            "1.0.0",
            ToolRiskClass.READ_ONLY,
            1,
            ToolInvocationOutcome.SUCCEEDED,
            5,
            "step-run:step-1",
            null,
            null);

    repo.insert(record);

    List<ToolInvocationRecord> found = repo.findByTenantAndRun(TENANT_A, RUN);
    assertThat(found).hasSize(1);
    ToolInvocationRecord row = found.get(0);
    assertThat(row.id()).isEqualTo(record.id());
    assertThat(row.tenantId()).isEqualTo(TENANT_A);
    assertThat(row.runId()).isEqualTo(RUN);
    assertThat(row.stepRunId()).isEqualTo("step-1");
    assertThat(row.toolName()).isEqualTo("calculator");
    assertThat(row.toolVersion()).isEqualTo("1.0.0");
    assertThat(row.riskClass()).isEqualTo(ToolRiskClass.READ_ONLY);
    assertThat(row.attempt()).isEqualTo(1);
    assertThat(row.outcome()).isEqualTo(ToolInvocationOutcome.SUCCEEDED);
    assertThat(row.latencyMs()).isEqualTo(5);
    assertThat(row.outputReference()).isEqualTo("step-run:step-1");
    assertThat(row.errorClass()).isNull();
    assertThat(row.errorSummary()).isNull();
    // created_at is a database default; it only has to be present on the row.
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_invocations", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void a_faulted_attempt_round_trips_with_the_redacted_failure_tokens() {
    ToolInvocationRepository repo = new ToolInvocationRepository(jdbc);
    ToolInvocationRecord record =
        ToolInvocationRecord.recorded(
            TENANT_A,
            RUN,
            "step-2",
            "calculator",
            "1.0.0",
            ToolRiskClass.WRITE,
            3,
            ToolInvocationOutcome.FAULTED,
            42,
            null,
            "division-by-zero",
            "CalculationException(division-by-zero)");

    repo.insert(record);

    ToolInvocationRecord row = repo.findByTenantAndRun(TENANT_A, RUN).get(0);
    assertThat(row.attempt()).isEqualTo(3);
    assertThat(row.outcome()).isEqualTo(ToolInvocationOutcome.FAULTED);
    assertThat(row.outputReference()).isNull();
    assertThat(row.errorClass()).isEqualTo("division-by-zero");
    assertThat(row.errorSummary()).isEqualTo("CalculationException(division-by-zero)");
  }

  @Test
  void a_runs_history_reads_back_in_append_order_across_attempts() {
    ToolInvocationRepository repo = new ToolInvocationRepository(jdbc);
    repo.insert(
        record(TENANT_A, "step-1", 1, ToolInvocationOutcome.THROTTLED, "concurrency_limited"));
    repo.insert(record(TENANT_A, "step-1", 2, ToolInvocationOutcome.TIMED_OUT, "tool_timeout"));
    repo.insert(record(TENANT_A, "step-1", 3, ToolInvocationOutcome.SUCCEEDED, null));

    List<ToolInvocationRecord> history = repo.findByTenantAndRun(TENANT_A, RUN);

    assertThat(history).hasSize(3);
    assertThat(history.stream().map(ToolInvocationRecord::attempt)).containsExactly(1, 2, 3);
    assertThat(history.stream().map(ToolInvocationRecord::outcome))
        .containsExactly(
            ToolInvocationOutcome.THROTTLED,
            ToolInvocationOutcome.TIMED_OUT,
            ToolInvocationOutcome.SUCCEEDED);
  }

  @Test
  void a_null_step_run_and_null_failure_tokens_round_trip() {
    ToolInvocationRepository repo = new ToolInvocationRepository(jdbc);
    ToolInvocationRecord record =
        ToolInvocationRecord.recorded(
            TENANT_A,
            RUN,
            null,
            "calculator",
            "1.0.0",
            ToolRiskClass.READ_ONLY,
            1,
            ToolInvocationOutcome.SUCCEEDED,
            0,
            null,
            null,
            null);

    repo.insert(record);

    ToolInvocationRecord row = repo.findByTenantAndRun(TENANT_A, RUN).get(0);
    assertThat(row.stepRunId()).isNull();
    assertThat(row.outputReference()).isNull();
    assertThat(row.errorClass()).isNull();
    assertThat(row.errorSummary()).isNull();
  }

  @Test
  void a_cross_tenant_run_id_is_indistinguishable_from_a_missing_one() {
    ToolInvocationRepository repo = new ToolInvocationRepository(jdbc);
    repo.insert(record(TENANT_A, "step-1", 1, ToolInvocationOutcome.SUCCEEDED, null));

    // Same run id, wrong tenant: the tenant predicate is part of the query (invariant 1), so the
    // read returns nothing rather than leaking another tenant's history.
    assertThat(repo.findByTenantAndRun(TENANT_B, RUN)).isEmpty();
    // The owning tenant's read is unaffected.
    assertThat(repo.findByTenantAndRun(TENANT_A, RUN)).hasSize(1);
  }

  private static ToolInvocationRecord record(
      UUID tenant,
      String stepRunId,
      int attempt,
      ToolInvocationOutcome outcome,
      String errorClass) {
    return ToolInvocationRecord.recorded(
        tenant,
        RUN,
        stepRunId,
        "calculator",
        "1.0.0",
        ToolRiskClass.READ_ONLY,
        attempt,
        outcome,
        0,
        outcome == ToolInvocationOutcome.SUCCEEDED ? "step-run:" + stepRunId : null,
        errorClass,
        null);
  }
}
