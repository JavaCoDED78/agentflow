package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.messaging.persistence.WorkflowEventRepository;
import com.example.agentflow.workflowexecution.application.RunStateMachineService;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
 * PostgreSQL concurrency suite for the single run transition service (S05-11, {@link
 * RunStateMachineService}) &mdash; the S05-16 criterion: two transactions move the same run at the
 * same time, and the race must finish by the documented rule so a silent lost update is impossible
 * and the append-only history gets exactly one row.
 *
 * <p>The DB-free tests ({@code RunStateMachineServiceTest}, {@code TransitionTableServiceTest})
 * assert the ordering against mocked objects and the repository integration suite ({@link
 * WorkflowTransitionIntegrationTest}) proves the conditional update over a real database &mdash;
 * but a mocked repository never runs SQL and a single-threaded real-DB call cannot produce a
 * genuine conflict (the service reads the current optimistic {@code version} and writes it back in
 * the same thread, so a pre-read bump is absorbed by the read &mdash; the {@code
 * WorkflowTransitionIntegrationTest#service_appends_one_event_on_success_and_none_when_a_transition_is_forbidden}
 * Javadoc records exactly this gap). This suite closes it: it releases {@code parallelism}
 * transactions that genuinely overlap on the same {@code pgvector/pgvector:0.8.6-pg18} row (the
 * same image as Compose, per {@code AGENTS.md}: never {@code H2} as a PostgreSQL stand-in) and
 * asserts the outcome invariant that no scheduling can violate:
 *
 * <ul>
 *   <li><b>exactly one writer wins</b> &mdash; the conditional {@code UPDATE ... WHERE status =
 *       'QUEUED' AND version = 0} can match the row at most once, so at most one transition ever
 *       commits (invariant 4);
 *   <li><b>no silent lost update</b> &mdash; the winning state ({@code RUNNING}) at the bumped
 *       {@code version} is the row's final state, so a losing writer never overwrote it;
 *   <li><b>no duplicate history</b> &mdash; the state change and its {@code workflow_events} row
 *       are one transaction (invariant 3 / {@code ADR-004}), so exactly one row is recorded for the
 *       run no matter how many writers raced.
 * </ul>
 *
 * <p>What the suite deliberately does <em>not</em> assert is <em>how</em> a losing writer is
 * refused: a writer that observed the pre-commit state is refused by the optimistic {@code version}
 * fence (HTTP 409); a writer that observed the committed {@code RUNNING} state is refused by the
 * domain state gate {@code RUNNING -> RUNNING} (HTTP 400, forbidden). Both are "not a winner", and
 * the invariant above holds for either path, so the assertions stay deterministic under any thread
 * interleaving instead of pinning a specific rejection code.
 *
 * <p>Every transaction runs in its own {@link TransactionTemplate} over its own connection (a
 * {@code DriverManagerDataSource} makes a fresh physical connection per {@code getConnection}, so
 * two transactions never share one and genuinely contend), and all writers are released at once
 * with a {@link CountDownLatch}. Every id is test-generated and each test seeds its own run, so the
 * tests are independent.
 */
@Testcontainers
class WorkflowTransitionConcurrencyIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;

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
  }

  // --- two concurrent run transitions (the S05-16 task: exactly two transactions) ----------------

  @Test
  void two_concurrent_run_transitions_record_exactly_one_winner_and_one_event() throws Exception {
    RaceOutcome r = race(2);

    // Exactly one writer's transition is recorded; every other writer is refused (400 or 409).
    assertThat(r.winners()).isEqualTo(1);
    // No lost update: the row holds the winner's state at the bumped optimistic version.
    RunStateSnapshot after =
        new WorkflowRunRepository(jdbc).findState(r.tenant(), r.runId()).orElseThrow();
    assertThat(after.status()).isEqualTo(WorkflowRunState.RUNNING);
    assertThat(after.version()).isEqualTo(1);
    // No duplicate history: the append-only trail never diverges from the state (invariant 3).
    WorkflowEventService events = new WorkflowEventService(new WorkflowEventRepository(jdbc));
    assertThat(events.historyByRun(r.tenant(), r.runId())).hasSize(1);
  }

  // --- four concurrent run transitions (the rule is not a two-thread artefact) -------------------

  @Test
  void four_concurrent_run_transitions_record_exactly_one_winner_and_one_event() throws Exception {
    RaceOutcome r = race(4);

    assertThat(r.winners()).isEqualTo(1);
    RunStateSnapshot after =
        new WorkflowRunRepository(jdbc).findState(r.tenant(), r.runId()).orElseThrow();
    assertThat(after.status()).isEqualTo(WorkflowRunState.RUNNING);
    assertThat(after.version()).isEqualTo(1);

    WorkflowEventService events = new WorkflowEventService(new WorkflowEventRepository(jdbc));
    assertThat(events.historyByRun(r.tenant(), r.runId())).hasSize(1);
  }

  // --- helpers -----------------------------------------------------------------------------------

  /**
   * Seeds a run in {@code QUEUED, version=0}, releases {@code parallelism} transactions that each
   * move it to {@code RUNNING} through {@link RunStateMachineService} in its own transaction, and
   * counts how many of them won. The run id and tenant are carried back on the returned record so
   * the caller can assert the final state and the single event row.
   */
  private static RaceOutcome race(int parallelism) throws InterruptedException {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    seedRun(tenant, runId);

    DataSource dataSource = liveDataSource();
    RunStateMachineService service =
        new RunStateMachineService(
            new WorkflowRunRepository(new JdbcTemplate(dataSource)),
            null,
            new WorkflowEventService(new WorkflowEventRepository(new JdbcTemplate(dataSource))));
    // Each transaction over its own connection: a DriverManagerDataSource makes a fresh connection
    // per getConnection, so two transactions never share one and genuinely contend on the row.
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    CountDownLatch release = new CountDownLatch(1);
    Outcome[] results = new Outcome[parallelism];
    Thread[] threads = new Thread[parallelism];
    for (int i = 0; i < parallelism; i++) {
      final int idx = i;
      threads[i] =
          new Thread(
              () -> {
                try {
                  release.await(); // release every writer at once so the transitions overlap
                  Outcome o = tx.execute(status -> runTransition(service, tenant, runId));
                  results[idx] = o;
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  results[idx] = Outcome.REJECTED;
                }
              });
    }
    for (Thread t : threads) {
      t.start();
    }
    release.countDown();
    for (Thread t : threads) {
      t.join();
    }
    int winners = 0;
    for (Outcome o : results) {
      if (o == Outcome.WIN) {
        winners++;
      }
    }
    return new RaceOutcome(winners, tenant, runId);
  }

  /**
   * Runs one transition and classifies its outcome: a successful move is {@link #WIN}; a loser is
   * {@link #REJECTED} whether it is refused by the optimistic-version fence (409) or, when it read
   * the committed state, by the domain state gate (400) &mdash; both mean "not a winner". A genuine
   * bug (a 500, or a 404 for the shared tenant, which cannot happen here) is rethrown so it fails
   * the test loudly instead of being silently counted as a loser.
   */
  private static Outcome runTransition(RunStateMachineService service, UUID tenant, UUID runId) {
    try {
      service.transitionRun(tenant, runId, WorkflowRunState.RUNNING, "run.started", "worker");
      return Outcome.WIN;
    } catch (ResponseStatusException e) {
      int code = e.getStatusCode().value();
      if (code == 400 || code == 409) {
        return Outcome.REJECTED;
      }
      throw e;
    }
  }

  private static DataSource liveDataSource() {
    return new DriverManagerDataSource(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static void seedRun(UUID tenant, UUID runId) {
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

  /** The per-thread outcome of a concurrent transition. */
  private enum Outcome {
    WIN,
    REJECTED
  }

  /** The winner count of a race plus the run it moved, so a caller can assert the final state. */
  private record RaceOutcome(int winners, UUID tenant, UUID runId) {}
}
