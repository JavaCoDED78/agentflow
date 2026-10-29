package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.messaging.persistence.WorkflowEventRepository;
import com.example.agentflow.workflowexecution.application.RunStateMachineService;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import java.util.EnumMap;
import java.util.Map;
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
 * RunStateMachineService}) — the S05-16 criterion: two transactions move the same run at the same
 * time, and the race must finish by the documented rule so a silent lost update is impossible and
 * the append-only history gets exactly one row.
 *
 * <p>The DB-free tests ({@code RunStateMachineServiceTest}, {@code TransitionTableServiceTest})
 * assert the ordering against mocked objects and the repository integration suite ({@link
 * WorkflowTransitionIntegrationTest}) proves the conditional update over a real database — but a
 * mocked repository never runs SQL and a sequential real-DB call cannot produce a genuine conflict:
 * the service reads the current optimistic {@code version} and writes it back in the same thread,
 * so a pre-read bump is absorbed by the read (the {@code WorkflowTransitionIntegrationTest} Javadoc
 * records exactly this gap). This suite closes it: it runs two transactions that actually contend
 * on the same row over a real {@code pgvector/pgvector:0.8.6-pg18} (the same image as Compose, per
 * {@code AGENTS.md}: never {@code H2} as a PostgreSQL stand-in), so it is the only suite that
 * proves the three claims together:
 *
 * <ul>
 *   <li><b>exactly one writer wins</b> &mdash; PostgreSQL {@code READ COMMITTED} locks the row on
 *       the winning {@code UPDATE} and the loser's {@code UPDATE} re-checks its {@code status} /
 *       {@code version} guard against the committed row, so the loser's conditional update matches
 *       no row and is refused (invariant 4 / optimistic lock);
 *   <li><b>no silent lost update</b> &mdash; the loser never overwrites the winner's commit: the
 *       row stays in the winner's state at the bumped {@code version}, so one of two competing
 *       updates can never overwrite the other;
 *   <li><b>no duplicate history</b> &mdash; the state change and its {@code workflow_events} row
 *       are one transaction (invariant 3 / {@code ADR-004}), so exactly one row is recorded for the
 *       run no matter how many writers raced.
 * </ul>
 *
 * <p>Both transactions are released simultaneously with a {@link
 * java.util.concurrent.CountDownLatch} and each runs in its own {@link TransactionTemplate}
 * transaction over its own connection, so the two {@code transitionRun} calls genuinely overlap:
 * the fast tenant-scoped read in both completes before either commits, both observe {@code (QUEUED,
 * version=0)}, and the row lock on the first {@code UPDATE} forces the second to lose the
 * transition. Every id is test-generated and each test seeds its own run, so the tests are
 * independent.
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
  void two_concurrent_run_transitions_end_with_one_winner_no_lost_update_and_one_event()
      throws Exception {
    RaceResult result = race(2);

    // The race finishes by the documented rule: exactly one writer wins, the other is refused
    // (a 409 optimistic conflict) — never a silent success for both and never a lost update.
    assertThat(result.get(Outcome.WIN)).isEqualTo(1);
    assertThat(result.get(Outcome.CONFLICT)).isEqualTo(1);
    assertThat(result.get(Outcome.INTERRUPTED)).isEqualTo(0);

    final UUID tenant = result.runTenant();
    final UUID runId = result.runId();
    // The row is in the winner's state at the bumped version: the loser did not overwrite it.
    RunStateSnapshot after = new WorkflowRunRepository(jdbc).findState(tenant, runId).orElseThrow();
    assertThat(after.status()).isEqualTo(WorkflowRunState.RUNNING);
    assertThat(after.version()).isEqualTo(1);

    // Exactly one history row: the append-only trail never diverges from the state (invariant 3).
    WorkflowEventService events = new WorkflowEventService(new WorkflowEventRepository(jdbc));
    assertThat(events.historyByRun(tenant, runId)).hasSize(1);
    assertThat(events.historyByRun(tenant, runId).get(0).eventType()).isEqualTo("run.started");
  }

  // --- four concurrent run transitions (the rule is not a two-thread artefact) -------------------

  @Test
  void four_concurrent_run_transitions_still_end_with_one_winner_and_one_event() throws Exception {
    RaceResult result = race(4);

    assertThat(result.get(Outcome.WIN)).isEqualTo(1);
    assertThat(result.get(Outcome.CONFLICT)).isEqualTo(3);
    assertThat(result.get(Outcome.INTERRUPTED)).isEqualTo(0);

    final UUID tenant = result.runTenant();
    final UUID runId = result.runId();
    RunStateSnapshot after = new WorkflowRunRepository(jdbc).findState(tenant, runId).orElseThrow();
    assertThat(after.status()).isEqualTo(WorkflowRunState.RUNNING);
    assertThat(after.version()).isEqualTo(1);

    WorkflowEventService events = new WorkflowEventService(new WorkflowEventRepository(jdbc));
    assertThat(events.historyByRun(tenant, runId)).hasSize(1);
  }

  // --- helpers -----------------------------------------------------------------------------------

  /**
   * Seeds a run in {@code QUEUED, version=0}, releases {@code parallelism} transactions that each
   * move it to {@code RUNNING} through {@link RunStateMachineService} in its own transaction, and
   * returns the per-outcome count keyed by {@link Outcome}. The run id and tenant are carried back
   * on the returned map so the caller can assert the final state and the single event row.
   */
  private static RaceResult race(int parallelism) throws InterruptedException {
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
    Map<Outcome, Integer> counts = new EnumMap<Outcome, Integer>(Outcome.class);
    synchronized (counts) {
      counts.put(Outcome.WIN, 0);
      counts.put(Outcome.CONFLICT, 0);
      counts.put(Outcome.INTERRUPTED, 0);
    }
    Outcome[] result = new Outcome[parallelism];
    Thread[] threads = new Thread[parallelism];
    for (int i = 0; i < parallelism; i++) {
      final int idx = i;
      threads[i] =
          new Thread(
              () -> {
                try {
                  release.await(); // start the race with every writer in flight at once
                  Outcome o =
                      tx.execute(
                          status -> {
                            Outcome outcome = runTransition(service, tenant, runId);
                            synchronized (counts) {
                              counts.put(outcome, counts.get(outcome) + 1);
                            }
                            return outcome;
                          });
                  result[idx] = o;
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  result[idx] = Outcome.INTERRUPTED;
                }
              });
    }
    for (Thread t : threads) {
      t.start();
    }
    // Release every writer at once so the transitions genuinely overlap; without this the
    // workers block on release.await() and the race would never start.
    release.countDown();
    for (Thread t : threads) {
      t.join();
    }
    return new RaceResult(counts, tenant, runId);
  }

  /**
   * Runs one transition and maps its outcome: a successful move is {@link #WIN}; the conditional
   * update's zero-rows conflict (another writer won) is the service's {@code 409} {@link
   * ResponseStatusException}; anything else (a {@code 400} forbidden transition, a {@code 404}) is
   * rethrown so it fails the test loudly rather than being counted as a race winner or loser.
   */
  private static Outcome runTransition(RunStateMachineService service, UUID tenant, UUID runId) {
    try {
      service.transitionRun(tenant, runId, WorkflowRunState.RUNNING, "run.started", "worker");
      return Outcome.WIN;
    } catch (ResponseStatusException e) {
      if (e.getStatusCode().value() == 409) {
        return Outcome.CONFLICT;
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
    CONFLICT,
    INTERRUPTED
  }

  /**
   * The outcome counts of a race plus the run it moved, so a caller can assert the final state and
   * the single appended event without re-reading the seeded ids.
   */
  private static final class RaceResult {
    private final Map<Outcome, Integer> counts;
    private final UUID tenant;
    private final UUID runId;

    private RaceResult(Map<Outcome, Integer> counts, UUID tenant, UUID runId) {
      this.counts = counts;
      this.tenant = tenant;
      this.runId = runId;
    }

    Integer get(Outcome outcome) {
      return counts.get(outcome);
    }

    UUID runTenant() {
      return tenant;
    }

    UUID runId() {
      return runId;
    }
  }
}
