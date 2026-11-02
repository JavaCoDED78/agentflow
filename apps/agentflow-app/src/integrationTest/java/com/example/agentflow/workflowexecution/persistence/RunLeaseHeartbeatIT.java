package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * PostgreSQL suite for the {@code workflow_runs} heartbeat (S07-04, the ADR-011 lease model). A
 * mocked {@link JdbcTemplate} never runs SQL, so the fenced conditional update &mdash; extend
 * {@code lease_until} only while the run still belongs to the tenant, is still owned by {@code
 * workerId}, and still holds the expected {@code lease_epoch} &mdash; is proven here over a real
 * row (the same `pgvector/pgvector:0.8.6-pg18` image as Compose, per {@code AGENTS.md}: never
 * {@code H2} as a PostgreSQL stand-in).
 *
 * <p>The S07-04 acceptance criterion &mdash; an old owner cannot renew its lease after it is
 * re-claimed &mdash; is the {@code old_owner_cannot_extend_after_reclaim} case: after the recovery
 * path re-claims the run (new owner, bumped epoch), the stale owner's heartbeat matches zero rows,
 * while the new owner's heartbeat extends it.
 */
@Testcontainers
class RunLeaseHeartbeatIT {

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

  // --- the S07-04 acceptance criterion: an old owner cannot renew its lease after re-claim
  // --------

  @Test
  void old_owner_cannot_extend_after_reclaim() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run, WorkflowRunState.RUNNING);

    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);
    UUID staleOwner = UUID.randomUUID();
    UUID newOwner = UUID.randomUUID();

    // the current owner claims, then heartbeats (the lease is still alive and matches)
    int epoch1 = claim(tenant, run, staleOwner, 30).orElseThrow();
    assertThat(repo.heartbeat(tenant, run, staleOwner, epoch1, until(30))).isEqualTo(1);

    // the current owner stops; the lease expires and the run is re-claimed (recovery)
    jdbc.update(
        "UPDATE workflow_runs SET lease_until = ? WHERE id = ? AND tenant_id = ?",
        ps -> {
          ps.setObject(1, until(-30));
          ps.setObject(2, run);
          ps.setObject(3, tenant);
        });
    int epoch2 = claim(tenant, run, newOwner, 30).orElseThrow();
    assertThat(epoch2).isEqualTo(epoch1 + 1); // the epoch is bumped on re-claim

    // the stale owner's heartbeat no longer matches: wrong owner AND wrong epoch -> 0 rows
    assertThat(repo.heartbeat(tenant, run, staleOwner, epoch1, until(30))).isEqualTo(0);
    // ...and a heartbeat with the moved epoch but the old owner is rejected too
    assertThat(repo.heartbeat(tenant, run, staleOwner, epoch2, until(30))).isEqualTo(0);

    // only the new owner (matching owner AND epoch) extends the lease
    assertThat(repo.heartbeat(tenant, run, newOwner, epoch2, until(30))).isEqualTo(1);
  }

  // --- a live owner's heartbeat extends the lease without moving the epoch
  // ------------------------

  @Test
  void live_owner_heartbeat_extends_until_without_bumping_the_epoch() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    seedRun(tenant, run, WorkflowRunState.RUNNING);

    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);
    UUID worker = UUID.randomUUID();
    int epoch = claim(tenant, run, worker, 30).orElseThrow();

    // a baseline clearly in the past: the heartbeat must push lease_until back to now + duration,
    // so the renewal is unambiguous even at TIMESTAMPTZ second-granularity (the claim and the
    // heartbeat above land in the same wall-clock second)
    OffsetDateTime baseline = OffsetDateTime.now(ZoneOffset.UTC).withNano(0).minusSeconds(5);
    jdbc.update("UPDATE workflow_runs SET lease_until = ?", ps -> ps.setObject(1, baseline));

    int affected = repo.heartbeat(tenant, run, worker, epoch, until(30));

    assertThat(affected).isEqualTo(1);
    LeaseState after = readLease(tenant, run);
    assertThat(after.owner()).isEqualTo(worker); // owner unchanged by a heartbeat
    assertThat(after.epoch()).isEqualTo(epoch); // a heartbeat never bumps the epoch
    assertThat(after.until()).isAfter(baseline); // the lease was renewed to now + duration
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static java.util.Optional<Integer> claim(UUID tenant, UUID run, UUID worker, int ttl) {
    return new WorkflowRunRepository(jdbc).claim(tenant, run, worker, until(ttl));
  }

  private static OffsetDateTime until(int deltaSeconds) {
    // truncate to seconds: a TIMESTAMPTZ column round-trips below microsecond precision, so a
    // sub-second (nanosecond) value would not compare equal after the heartbeat stores and reads it
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
