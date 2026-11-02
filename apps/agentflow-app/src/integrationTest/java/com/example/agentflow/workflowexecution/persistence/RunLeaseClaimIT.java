package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
 * PostgreSQL concurrency suite for the {@code workflow_runs} atomic claim (S07-03, {@link
 * WorkflowRunRepository#claim}; the ADR-011 lease model). The DB-free tests ({@link
 * WorkflowRunRepositoryClaimTest}, {@link RunLeaseServiceTest}) assert the SQL binds and the
 * service wiring against mocked objects, but a mocked {@link JdbcTemplate} never runs SQL and a
 * single-threaded real-DB call cannot produce a genuine claim race. This suite closes that gap: two
 * threads claim the same {@code pgvector/pgvector:0.8.6-pg18} row (the same image as Compose, per
 * {@code AGENTS.md}: never {@code H2} as a PostgreSQL stand-in) over independent physical
 * connections, and assert the invariant that no scheduling can violate &mdash; exactly one worker
 * ends up holding {@code lease_owner}, so the append-only history can never diverge for one run
 * (invariant 4 / the conditional-update rule).
 *
 * <p>Each test seeds its own run, so the tests are independent. The claim is exercised through the
 * real {@link WorkflowRunRepository} over a real row, passing {@code lease_until} explicitly (the
 * service computes it from the injected {@code Clock} per {@link RunLeaseServiceTest}).
 */
@Testcontainers
class RunLeaseClaimIT {

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

  // --- the S07-03 acceptance criterion: one atomic claim, one owner -----------------------------

  @Test
  void two_concurrent_claims_leave_exactly_one_owner() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run, WorkflowRunState.QUEUED);

    UUID workerA = UUID.randomUUID();
    UUID workerB = UUID.randomUUID();
    OffsetDateTime until = until(30);

    int winners = 0;
    CountDownLatch release = new CountDownLatch(1);
    int[] result = new int[2];
    Thread[] threads =
        new Thread[] {
          workerThread(
              () -> {
                release.await();
                result[0] = claim(tenant, run, workerA, until).isPresent() ? 1 : 0;
              },
              release),
          workerThread(
              () -> {
                release.await();
                result[1] = claim(tenant, run, workerB, until).isPresent() ? 1 : 0;
              },
              release)
        };
    for (Thread t : threads) {
      t.start();
    }
    release.countDown();
    for (Thread t : threads) {
      t.join();
    }
    winners = result[0] + result[1];

    // Exactly one worker won the atomic claim; the other observed the row already taken.
    assertThat(winners).isEqualTo(1);
    // The row's lease_owner is the winner's worker instance, not both.
    LeaseState lease = readLease(tenant, run);
    assertThat(lease.owner()).isIn(workerA, workerB);
    assertThat(lease.owner()).isNotNull();
    assertThat(lease.epoch()).isGreaterThanOrEqualTo(1);
  }

  // --- a fresh QUEUED run is claimable and receives the lease ----------------------------------

  @Test
  void fresh_run_is_claimed_with_owner_until_and_first_epoch() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run, WorkflowRunState.QUEUED);
    UUID worker = UUID.randomUUID();

    OffsetDateTime until = until(30);
    Optional<Integer> epoch = claim(tenant, run, worker, until);

    assertThat(epoch).isPresent();
    assertThat(epoch.get()).isEqualTo(1);
    LeaseState lease = readLease(tenant, run);
    assertThat(lease.owner()).isEqualTo(worker);
    assertThat(lease.until()).isEqualTo(until);
    assertThat(lease.epoch()).isEqualTo(1);
  }

  // --- a live lease cannot be stolen by another worker ------------------------------------------

  @Test
  void run_owned_by_a_live_worker_is_not_claimed_by_another() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run, WorkflowRunState.RUNNING);
    // the run is already owned, and the lease has not expired
    jdbc.update(
        "UPDATE workflow_runs SET lease_owner = ?, lease_until = ? WHERE id = ? AND tenant_id = ?",
        ps -> {
          ps.setObject(1, UUID.randomUUID());
          ps.setObject(2, until(30));
          ps.setObject(3, run);
          ps.setObject(4, tenant);
        });

    Optional<Integer> epoch =
        claim(tenant, run, UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));

    assertThat(epoch).isEmpty();
    // the original owner is untouched
    assertThat(readLease(tenant, run).owner()).isNotNull();
  }

  // --- a terminal run is never re-claimed -------------------------------------------------------

  @Test
  void terminal_run_is_not_claimed_even_with_an_expired_lease() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run, WorkflowRunState.SUCCEEDED);
    // an expired lease would otherwise match a naive "lease_expired" predicate
    jdbc.update(
        "UPDATE workflow_runs SET lease_owner = ?, lease_until = ? WHERE id = ? AND tenant_id = ?",
        ps -> {
          ps.setObject(1, UUID.randomUUID());
          ps.setObject(2, until(-30));
          ps.setObject(3, run);
          ps.setObject(4, tenant);
        });

    Optional<Integer> epoch = claim(tenant, run, UUID.randomUUID(), until(30));

    assertThat(epoch).isEmpty();
  }

  // --- an expired lease is reclaimed and the epoch is bumped ------------------------------------

  @Test
  void expired_lease_is_reclaimed_and_the_epoch_is_bumped() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run, WorkflowRunState.RUNNING);
    UUID deadOwner = UUID.randomUUID();
    jdbc.update(
        "UPDATE workflow_runs SET lease_owner = ?, lease_until = ?, lease_epoch = 5 WHERE id = ? AND tenant_id = ?",
        ps -> {
          ps.setObject(1, deadOwner);
          ps.setObject(2, until(-30));
          ps.setObject(3, run);
          ps.setObject(4, tenant);
        });

    OffsetDateTime until = until(30);
    Optional<Integer> epoch = claim(tenant, run, UUID.randomUUID(), until);

    assertThat(epoch).isPresent();
    assertThat(epoch.get())
        .isEqualTo(6); // bumped from 5, so the dead owner's in-flight writes fence
    LeaseState lease = readLease(tenant, run);
    assertThat(lease.owner()).isNotEqualTo(deadOwner);
    assertThat(lease.owner()).isNotNull();
    assertThat(lease.until()).isEqualTo(until);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private Optional<Integer> claim(UUID tenant, UUID run, UUID worker, OffsetDateTime until) {
    return new WorkflowRunRepository(jdbc).claim(tenant, run, worker, until);
  }

  /** A worker body that may block on interruptible coordination (a {@link CountDownLatch}). */
  @FunctionalInterface
  private interface InterruptibleBody {
    void run() throws InterruptedException;
  }

  private static Thread workerThread(InterruptibleBody body, CountDownLatch release) {
    return new Thread(
        () -> {
          try {
            body.run();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
  }

  private static OffsetDateTime until(int deltaSeconds) {
    // truncate to seconds: a TIMESTAMPTZ column round-trips below microsecond precision, so a
    // sub-second (nanosecond) value would not compare equal after the claim stores and reads it
    return OffsetDateTime.now(ZoneOffset.UTC).withNano(0).plusSeconds(deltaSeconds);
  }

  private static void seedRun(UUID tenant, UUID run, WorkflowRunState status) {
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
        "INSERT INTO workflow_definition_versions (id, workflow_id, version, graph_json, checksum) VALUES (?, ?, 1, ?::jsonb, ?)",
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
          ps.setString(4, status.name());
        });
  }

  private static LeaseState readLease(UUID tenant, UUID run) {
    List<LeaseState> rows =
        jdbc.query(
            "SELECT lease_owner, lease_until, lease_epoch FROM workflow_runs "
                + "WHERE id = ? AND tenant_id = ?",
            (rs, rowNum) ->
                new LeaseState(
                    (UUID) rs.getObject("lease_owner"),
                    rs.getObject("lease_until", OffsetDateTime.class),
                    rs.getInt("lease_epoch")),
            run,
            tenant);
    return rows.get(0);
  }

  private record LeaseState(UUID owner, OffsetDateTime until, int epoch) {}
}
