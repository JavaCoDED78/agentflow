package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.workflowexecution.domain.ConflictException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 * PostgreSQL suite for the guarded approval decision writes ({@code S11-05}: {@link
 * ApprovalRepository#recordReject} and {@link ApprovalRepository#expirePending}). The DB-free
 * {@link ApprovalRepositoryTest} asserts the SQL text and the binds against a mocked {@link
 * JdbcTemplate}, but a mock never runs the conditional {@code UPDATE} — this suite drives the real
 * repository against {@code pgvector/pgvector:0.8.6-pg18} (the same image as Compose, per {@code
 * AGENTS.md}: never {@code H2} as a PostgreSQL stand-in) and proves the two properties a mock
 * cannot: a racing approve/reject pair affects <em>exactly one</em> row (invariant 4, at-least-once
 * {@code ADR-003}), and an expiry never writes a human actor — an expired row cannot be read back
 * as a human decision.
 *
 * <p>Each test seeds its own tenant/run/approval rows, so the tests are independent.
 */
@Testcontainers
class ApprovalDecisionWriteIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;
  private static ApprovalRepository repository;

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
    repository = new ApprovalRepository(jdbc);
  }

  @Test
  void reject_marks_the_pending_row_rejected_and_bumps_the_version() {
    Seeded seeded = seedApproval("reject-write", OffsetDateTime.now().plusHours(1), "PENDING");
    OffsetDateTime decisionAt = OffsetDateTime.of(2026, 11, 14, 12, 0, 0, 0, ZoneOffset.UTC);
    UUID key = UUID.randomUUID();

    repository.recordReject(
        seeded.tenant,
        seeded.run,
        seeded.approval,
        "approver@acme.example",
        decisionAt,
        "too risky",
        key,
        "fingerprint",
        0);

    ApprovalRow decided = repository.find(seeded.tenant, seeded.run, seeded.approval).orElseThrow();
    assertThat(decided.status()).isEqualTo("REJECTED");
    assertThat(decided.decisionActor()).isEqualTo("approver@acme.example");
    assertThat(decided.decisionAt()).isEqualTo(decisionAt);
    assertThat(decided.decisionComment()).isEqualTo("too risky");
    assertThat(decided.idempotencyKey()).isEqualTo(key);
    assertThat(decided.idempotencyFingerprint()).isEqualTo("fingerprint");
    // the payload binding rides the row unread by the decision path but never lost: the digest the
    // approval was requested under reads back verbatim (S11-06 re-validates it on approve)
    assertThat(decided.payloadFingerprint()).isEqualTo("fp");
    assertThat(decided.version()).isEqualTo(1);
  }

  @Test
  void a_second_decision_on_the_decided_row_is_a_conflict() {
    Seeded seeded = seedApproval("double-decision", OffsetDateTime.now().plusHours(1), "PENDING");
    OffsetDateTime decisionAt = OffsetDateTime.now();

    repository.recordReject(
        seeded.tenant,
        seeded.run,
        seeded.approval,
        "approver@acme.example",
        decisionAt,
        "no",
        null,
        null,
        0);

    // the observed version moved with the first decision — the stale write matches zero rows
    assertThatThrownBy(
            () ->
                repository.recordApprove(
                    seeded.tenant,
                    seeded.run,
                    seeded.approval,
                    "another@acme.example",
                    decisionAt,
                    null,
                    null,
                    null,
                    0))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("another decision won the race");

    // and the same write guarded on the new version still refuses: the row is no longer PENDING
    assertThatThrownBy(
            () ->
                repository.recordApprove(
                    seeded.tenant,
                    seeded.run,
                    seeded.approval,
                    "another@acme.example",
                    decisionAt,
                    null,
                    null,
                    null,
                    1))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("another decision won the race");

    ApprovalRow row = repository.find(seeded.tenant, seeded.run, seeded.approval).orElseThrow();
    assertThat(row.status()).isEqualTo("REJECTED");
    assertThat(row.decisionActor()).isEqualTo("approver@acme.example");
  }

  @Test
  void an_approve_and_a_reject_race_affects_exactly_one_row() throws Exception {
    Seeded seeded =
        seedApproval("approve-reject-race", OffsetDateTime.now().plusHours(1), "PENDING");
    OffsetDateTime decisionAt = OffsetDateTime.now();
    CountDownLatch release = new CountDownLatch(1);
    boolean[] approveWon = new boolean[1];
    boolean[] rejectWon = new boolean[1];

    Thread approver =
        workerThread(
            () -> {
              release.await();
              try {
                repository.recordApprove(
                    seeded.tenant,
                    seeded.run,
                    seeded.approval,
                    "approver-a@acme.example",
                    decisionAt,
                    null,
                    null,
                    null,
                    0);
                approveWon[0] = true;
              } catch (ConflictException e) {
                approveWon[0] = false;
              }
            });
    Thread rejecter =
        workerThread(
            () -> {
              release.await();
              try {
                repository.recordReject(
                    seeded.tenant,
                    seeded.run,
                    seeded.approval,
                    "approver-b@acme.example",
                    decisionAt,
                    null,
                    null,
                    null,
                    0);
                rejectWon[0] = true;
              } catch (ConflictException e) {
                rejectWon[0] = false;
              }
            });
    approver.start();
    rejecter.start();
    release.countDown();
    approver.join();
    rejecter.join();

    // exactly one guarded write matched the row: two competing decisions never overwrite each
    // other (invariant 4)
    assertThat((approveWon[0] ? 1 : 0) + (rejectWon[0] ? 1 : 0)).isEqualTo(1);

    ApprovalRow row = repository.find(seeded.tenant, seeded.run, seeded.approval).orElseThrow();
    assertThat(row.status()).isIn("APPROVED", "REJECTED");
    assertThat(row.version()).isEqualTo(1);
    assertThat(row.decisionActor())
        .isEqualTo(approveWon[0] ? "approver-a@acme.example" : "approver-b@acme.example");
  }

  @Test
  void expire_pending_marks_expired_without_a_human_actor() {
    Seeded seeded = seedApproval("expiry-write", OffsetDateTime.now().minusSeconds(1), "PENDING");
    // a whole-microsecond instant: PostgreSQL stores TIMESTAMPTZ at microsecond precision, so a
    // nanosecond-carrying clock reading would never read back identical
    OffsetDateTime expiredAt = OffsetDateTime.of(2026, 11, 14, 12, 0, 0, 0, ZoneOffset.UTC);

    repository.expirePending(seeded.tenant, seeded.run, seeded.approval, expiredAt, 0);

    ApprovalRow row = repository.find(seeded.tenant, seeded.run, seeded.approval).orElseThrow();
    assertThat(row.status()).isEqualTo("EXPIRED");
    // an expiry has no human behind it: only decision_at marks the observed deadline, so the row
    // can never be read back as a human decision
    assertThat(row.decisionActor()).isNull();
    assertThat(row.decisionComment()).isNull();
    assertThat(row.decisionAt()).isEqualTo(expiredAt);
    assertThat(row.version()).isEqualTo(1);

    // a human decision after the expiry is a conflict: the row is no longer PENDING
    assertThatThrownBy(
            () ->
                repository.recordReject(
                    seeded.tenant,
                    seeded.run,
                    seeded.approval,
                    "late@acme.example",
                    OffsetDateTime.now(),
                    null,
                    null,
                    null,
                    1))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void find_overdue_pending_returns_only_overdue_pending_oldest_first() {
    OffsetDateTime now = OffsetDateTime.now();
    Seeded overdueOlder = seedApproval("scan-older", now.minusHours(2), "PENDING");
    Seeded overdueNewer = seedApproval("scan-newer", now.minusHours(1), "PENDING");
    seedApproval("scan-no-expiry", null, "PENDING");
    seedApproval("scan-future", now.plusHours(1), "PENDING");
    seedApproval("scan-decided", now.minusHours(3), "REJECTED");

    List<OverdueApprovalRow> overdue = repository.findOverduePending(now, 16);

    assertThat(overdue.stream().map(OverdueApprovalRow::id))
        .containsExactly(overdueOlder.approval, overdueNewer.approval);
    OverdueApprovalRow first = overdue.get(0);
    assertThat(first.tenantId()).isEqualTo(overdueOlder.tenant);
    assertThat(first.runId()).isEqualTo(overdueOlder.run);
    assertThat(first.stepKey()).isEqualTo("scan-older");
    assertThat(first.version()).isEqualTo(0);
  }

  // --- seeding ----------------------------------------------------------------------------------

  private record Seeded(UUID tenant, UUID run, UUID approval) {}

  private Seeded seedApproval(String stepKey, OffsetDateTime expiry, String status) {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID approval = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')", tenant, "t-" + tenant);
    UUID definition = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workflow_definitions (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
        definition,
        tenant,
        "wf-" + definition);
    UUID version = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workflow_definition_versions (id, workflow_id, version, graph_json, checksum)"
            + " VALUES (?, ?, 1, ?::jsonb, ?)",
        version,
        definition,
        "{\"nodes\":[]}",
        "c");
    jdbc.update(
        "INSERT INTO workflow_runs (id, tenant_id, workflow_version_id, status, attempt)"
            + " VALUES (?, ?, ?, 'QUEUED', 1)",
        run,
        tenant,
        version);
    jdbc.update(
        "INSERT INTO approvals (id, tenant_id, run_id, step_key, status, action,"
            + " payload_fingerprint, requester, expiry, decision_actor, decision_comment,"
            + " decision_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        approval,
        tenant,
        run,
        stepKey,
        status,
        "the gated action",
        "fp",
        "system",
        expiry,
        "REJECTED".equals(status) ? "approver@acme.example" : null,
        "REJECTED".equals(status) ? "no" : null,
        "REJECTED".equals(status) ? OffsetDateTime.now() : null);
    return new Seeded(tenant, run, approval);
  }

  private static Thread workerThread(org.junit.jupiter.api.function.Executable executable) {
    return new Thread(
        () -> {
          try {
            executable.execute();
          } catch (Throwable t) {
            throw new AssertionError(t);
          }
        });
  }
}
