package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.workflowexecution.domain.ConflictException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

/**
 * DB-free tests for {@link ApprovalRepository} (S11-02 / S11-03 insert, S11-04 decision path). The
 * {@link JdbcTemplate} is a mock, so the SQL and the bound arguments are asserted directly (the
 * same convention as {@link WorkflowRunRepositoryTest}); the real insert and the real conditional
 * decision update run under a real PostgreSQL row in the Sprint 11 integration test.
 *
 * <p>The insert is a single {@code INSERT} of a {@code PENDING} row carrying the tenant (invariant
 * 1), the run and step it gates, the redacted {@code action} summary and the payload fingerprint —
 * never the payload (invariant 8). {@code created_at} defaults to {@code now()} in SQL, so the
 * application writes no timestamp (the DB is the authoritative creation instant, invariant 10).
 */
class ApprovalRepositoryTest {

  @Test
  @SuppressWarnings("unchecked")
  void find_scopes_the_lookup_on_the_tenant_run_and_approval() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID approval = UUID.randomUUID();
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(Collections.emptyList());

    assertThat(repo.find(tenant, run, approval)).isEmpty();

    verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
    // the full triple: a cross-tenant id (or a guessed id under another run) is indistinguishable
    // from a missing one (invariant 1)
    assertThat(sql.getValue()).contains("WHERE tenant_id = ? AND run_id = ? AND id = ?");
    assertThat(args.getValue()[0]).isEqualTo(tenant);
    assertThat(args.getValue()[1]).isEqualTo(run);
    assertThat(args.getValue()[2]).isEqualTo(approval);
  }

  @Test
  @SuppressWarnings("unchecked")
  void find_maps_the_decision_relevant_columns() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    UUID id = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    OffsetDateTime decisionAt = OffsetDateTime.parse("2026-11-14T12:00:00Z");
    ApprovalRow row =
        new ApprovalRow(
            id,
            run,
            "send-report",
            "payload-fingerprint",
            "APPROVED",
            null,
            1,
            "approver",
            decisionAt,
            "ok",
            key,
            "fingerprint");
    List<ApprovalRow> rows = new ArrayList<>(Collections.singletonList(row));
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<RowMapper> mapper = ArgumentCaptor.forClass(RowMapper.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    // the captures answer the stubbing itself — the same shape as the sibling find_checkpoint test
    doReturn(rows).when(jdbc).query(sql.capture(), mapper.capture(), args.capture());
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("run_id", UUID.class)).thenReturn(run);
    when(rs.getString("step_key")).thenReturn("send-report");
    when(rs.getString("payload_fingerprint")).thenReturn("payload-fingerprint");
    when(rs.getString("status")).thenReturn("APPROVED");
    when(rs.getInt("version")).thenReturn(1);
    when(rs.getString("decision_actor")).thenReturn("approver");
    when(rs.getObject("decision_at", OffsetDateTime.class)).thenReturn(decisionAt);
    when(rs.getString("decision_comment")).thenReturn("ok");
    when(rs.getObject("idempotency_key", UUID.class)).thenReturn(key);
    when(rs.getString("idempotency_fingerprint")).thenReturn("fingerprint");

    assertThat(repo.find(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())).contains(row);
    assertThat(sql.getValue())
        .contains("FROM approvals")
        .contains("WHERE tenant_id = ? AND run_id = ? AND id = ?");
    assertThat(args.getValue()).hasSize(3);

    // drive the RowMapper on the mocked ResultSet — a stubbed JdbcTemplate never runs it
    ApprovalRow mapped = (ApprovalRow) mapper.getValue().mapRow(rs, 1);
    assertThat(mapped.status()).isEqualTo("APPROVED");
    assertThat(mapped.stepKey()).isEqualTo("send-report");
    assertThat(mapped.payloadFingerprint()).isEqualTo("payload-fingerprint");
    assertThat(mapped.decisionActor()).isEqualTo("approver");
    assertThat(mapped.decisionAt()).isEqualTo(decisionAt);
    assertThat(mapped.idempotencyKey()).isEqualTo(key);
    assertThat(mapped.idempotencyFingerprint()).isEqualTo("fingerprint");
  }

  @Test
  @SuppressWarnings("unchecked")
  void find_by_idempotency_key_scopes_the_lookup_to_the_tenant() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(Collections.emptyList());

    assertThat(repo.findByIdempotencyKey(tenant, key)).isEmpty();

    verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
    // tenant-scoped like the create-run idempotency lookup: another tenant's row under the same
    // key is indistinguishable from absent (invariant 1)
    assertThat(sql.getValue()).contains("WHERE tenant_id = ? AND idempotency_key = ?");
    assertThat(args.getValue()[0]).isEqualTo(tenant);
    assertThat(args.getValue()[1]).isEqualTo(key);
  }

  @Test
  @SuppressWarnings("unchecked")
  void record_approve_binds_the_decision_and_guards_on_pending_and_version() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID approval = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    OffsetDateTime decisionAt = OffsetDateTime.parse("2026-11-14T12:00:00Z");
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    repo.recordApprove(tenant, run, approval, "approver", decisionAt, "ok", key, "fingerprint", 7);

    verify(jdbc).update(sql.capture(), setter.capture());
    // the write is the guarded transition itself: only a PENDING row at the observed version
    // moves, so two competing decisions never overwrite each other (invariant 4). The status is a
    // bound internal literal (recordApprove / recordReject share recordDecision), never caller
    // input — the status vocabulary is closed.
    assertThat(sql.getValue())
        .contains("SET status = ?, decision_actor = ?, decision_at = ?")
        .contains("WHERE id = ? AND tenant_id = ? AND run_id = ? AND status = 'PENDING'")
        .contains("AND version = ?");
    setter.getValue().setValues(ps);
    verify(ps).setString(1, "APPROVED");
    verify(ps).setString(2, "approver");
    verify(ps).setObject(3, decisionAt);
    verify(ps).setString(4, "ok");
    verify(ps).setObject(5, key);
    verify(ps).setString(6, "fingerprint");
    verify(ps).setObject(7, decisionAt);
    verify(ps).setObject(8, approval);
    verify(ps).setObject(9, tenant);
    verify(ps).setObject(10, run);
    verify(ps).setInt(11, 7);
  }

  @Test
  @SuppressWarnings("unchecked")
  void record_approve_binds_null_comment_and_null_key_as_sql_null() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    repo.recordApprove(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "approver",
        OffsetDateTime.parse("2026-11-14T12:00:00Z"),
        null,
        null,
        null,
        0);

    verify(jdbc).update(anyString(), setter.capture());
    setter.getValue().setValues(ps);
    // comment, key and fingerprint are bound at 4 / 5 / 6 of the shared decision write — the
    // null halves land as SQL NULL (status=1, actor=2, decision_at=3 stay bound)
    verify(ps).setNull(4, Types.VARCHAR);
    verify(ps).setNull(5, Types.OTHER);
    verify(ps).setNull(6, Types.VARCHAR);
  }

  @Test
  @SuppressWarnings("unchecked")
  void record_approve_zero_rows_becomes_a_conflict() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

    assertThatThrownBy(
            () ->
                repo.recordApprove(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "approver",
                    OffsetDateTime.parse("2026-11-14T12:00:00Z"),
                    null,
                    null,
                    null,
                    3))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("another decision won the race");
  }

  @Test
  @SuppressWarnings("unchecked")
  void record_reject_binds_the_decision_and_guards_on_pending_and_version() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID approval = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    OffsetDateTime decisionAt = OffsetDateTime.parse("2026-11-14T12:00:00Z");
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    repo.recordReject(
        tenant, run, approval, "approver", decisionAt, "too risky", key, "fingerprint", 7);

    verify(jdbc).update(sql.capture(), setter.capture());
    // the write is the guarded transition itself: only a PENDING row at the observed version
    // moves, so a reject never overwrites a concurrent approve or another reject (invariant 4).
    // The status is a bound internal literal (recordApprove / recordReject share the write), never
    // caller input — the status vocabulary is closed.
    assertThat(sql.getValue())
        .contains("SET status = ?, decision_actor = ?, decision_at = ?")
        .contains("WHERE id = ? AND tenant_id = ? AND run_id = ? AND status = 'PENDING'")
        .contains("AND version = ?");
    setter.getValue().setValues(ps);
    verify(ps).setString(1, "REJECTED");
    verify(ps).setString(2, "approver");
    verify(ps).setObject(3, decisionAt);
    verify(ps).setString(4, "too risky");
    verify(ps).setObject(5, key);
    verify(ps).setString(6, "fingerprint");
    verify(ps).setObject(7, decisionAt);
    verify(ps).setObject(8, approval);
    verify(ps).setObject(9, tenant);
    verify(ps).setObject(10, run);
    verify(ps).setInt(11, 7);
  }

  @Test
  @SuppressWarnings("unchecked")
  void record_reject_zero_rows_becomes_a_conflict() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

    assertThatThrownBy(
            () ->
                repo.recordReject(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "approver",
                    OffsetDateTime.parse("2026-11-14T12:00:00Z"),
                    "too risky",
                    null,
                    null,
                    3))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("another decision won the race");
  }

  @Test
  @SuppressWarnings("unchecked")
  void expire_pending_moves_the_row_and_guards_on_pending_expiry_and_version() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID approval = UUID.randomUUID();
    OffsetDateTime decisionAt = OffsetDateTime.parse("2026-11-14T12:00:00Z");
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    repo.expirePending(tenant, run, approval, decisionAt, 2);

    verify(jdbc).update(sql.capture(), setter.capture());
    // expiry is the same guarded transition as a human decision: only a still-PENDING row at the
    // observed version moves, so an expiry can never win over a human decision. The expiry
    // predicate lives in the scan (findOverduePending), not here: the write only fences status
    // and version, and it writes no actor / comment — an expired row can never be read back as a
    // human decision.
    assertThat(sql.getValue())
        .contains("SET status = 'EXPIRED', decision_at = ?")
        .contains("version = version + 1, updated_at = ?")
        .contains("WHERE id = ? AND tenant_id = ? AND run_id = ?")
        .contains("status = 'PENDING'")
        .contains("AND version = ?");
    assertThat(sql.getValue()).doesNotContain("decision_actor = ?");
    setter.getValue().setValues(ps);
    verify(ps).setObject(1, decisionAt);
    verify(ps).setObject(2, decisionAt);
    verify(ps).setObject(3, approval);
    verify(ps).setObject(4, tenant);
    verify(ps).setObject(5, run);
    verify(ps).setInt(6, 2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void expire_pending_zero_rows_becomes_a_conflict() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

    assertThatThrownBy(
            () ->
                repo.expirePending(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    OffsetDateTime.parse("2026-11-14T12:00:00Z"),
                    2))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("a decision won the race");
  }

  @Test
  @SuppressWarnings("unchecked")
  void find_overdue_pending_scans_past_the_cutoff_bounded_and_oldest_first() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    OffsetDateTime cutoff = OffsetDateTime.parse("2026-11-14T12:00:00Z");
    OverdueApprovalRow row =
        new OverdueApprovalRow(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "send-report",
            3,
            "requester@acme.example");
    List<OverdueApprovalRow> rows = new ArrayList<>(Collections.singletonList(row));
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<RowMapper> mapper = ArgumentCaptor.forClass(RowMapper.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    doReturn(rows).when(jdbc).query(sql.capture(), mapper.capture(), args.capture());
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id", UUID.class)).thenReturn(row.id());
    when(rs.getObject("tenant_id", UUID.class)).thenReturn(row.tenantId());
    when(rs.getObject("run_id", UUID.class)).thenReturn(row.runId());
    when(rs.getString("step_key")).thenReturn(row.stepKey());
    when(rs.getInt("version")).thenReturn(row.version());
    when(rs.getString("requester")).thenReturn(row.requester());

    assertThat(repo.findOverduePending(cutoff, 16)).containsExactly(row);
    // the scan carries the rows back with their tenant: the sweep fans out per tenant through the
    // run pre-check; the predicate is status + an expiry already past the cutoff, never a NULL
    // expiry, ordered oldest-first and bounded so one pass fails a limited batch
    assertThat(sql.getValue())
        .contains("FROM approvals")
        .contains("LEFT JOIN workflow_runs")
        .contains("created_by AS requester")
        .contains("status = 'PENDING'")
        .contains("a.expiry IS NOT NULL AND a.expiry < ?")
        .contains("ORDER BY a.expiry ASC LIMIT ?");
    assertThat(args.getValue()[0]).isEqualTo(cutoff);
    assertThat(args.getValue()[1]).isEqualTo(16);

    // drive the RowMapper on the mocked ResultSet — a stubbed JdbcTemplate never runs it
    OverdueApprovalRow mapped = (OverdueApprovalRow) mapper.getValue().mapRow(rs, 1);
    assertThat(mapped.id()).isEqualTo(row.id());
    assertThat(mapped.tenantId()).isEqualTo(row.tenantId());
    assertThat(mapped.runId()).isEqualTo(row.runId());
    assertThat(mapped.stepKey()).isEqualTo("send-report");
    assertThat(mapped.version()).isEqualTo(3);
    assertThat(mapped.requester()).isEqualTo("requester@acme.example");
  }

  @Test
  @SuppressWarnings("unchecked")
  void create_inserts_a_pending_approval_scoped_by_tenant() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    String step = "send-monthly-report";
    String action = "Send the monthly financial report to finance@acme.example";
    String fingerprint = "abcdef0123456789";
    String actor = "agentflow-worker";
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    ApprovalRecord created = repo.create(tenant, run, step, action, fingerprint, actor, actor);

    verify(jdbc).update(sql.capture(), setter.capture());
    // one insert of a PENDING row, tenant first (the scope) then the run / step (the resource)
    assertThat(sql.getValue())
        .contains(
            "INSERT INTO approvals (id, tenant_id, run_id, step_key, status, action, payload_fingerprint, requester, created_by)")
        .contains("VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)");
    // the id is generated by the application (UUID primary key); the tenant is bound second (the
    // scope) and the run third — the resource the approval gates
    setter.getValue().setValues(ps);
    verify(ps).setObject(2, tenant);
    verify(ps).setObject(3, run);
    verify(ps).setString(4, step);
    verify(ps).setString(5, action);
    verify(ps).setString(6, fingerprint);
    verify(ps).setString(7, actor);
    verify(ps).setString(8, actor);
    assertThat(created.id()).isNotNull();
    assertThat(created.stepKey()).isEqualTo(step);
  }

  @Test
  @SuppressWarnings("unchecked")
  void create_binds_null_requester_and_created_by_as_sql_null() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ApprovalRepository repo = new ApprovalRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    String action = "Send the monthly financial report to finance@acme.example";
    String fingerprint = "abcdef0123456789";
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    // begun without an actor (a workflow-driven request): the nullable columns are SQL NULL
    ApprovalRecord created =
        repo.create(tenant, run, "send-report", action, fingerprint, null, null);

    verify(jdbc).update(anyString(), setter.capture());
    setter.getValue().setValues(ps);
    verify(ps).setObject(2, tenant);
    verify(ps).setObject(3, run);
    verify(ps).setString(4, "send-report");
    verify(ps).setString(5, action);
    verify(ps).setString(6, fingerprint);
    verify(ps).setNull(7, Types.VARCHAR);
    verify(ps).setNull(8, Types.VARCHAR);
    assertThat(created.id()).isNotNull();
  }
}
