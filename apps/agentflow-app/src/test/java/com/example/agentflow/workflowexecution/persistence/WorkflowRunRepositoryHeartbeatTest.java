package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

/**
 * DB-free tests for the {@code workflow_runs} heartbeat (S07-04). The {@link JdbcTemplate} is a
 * mock, so the SQL and the bound arguments are asserted directly (the same convention as {@link
 * WorkflowRunRepositoryClaimTest}); the real fenced conditional update runs in {@link
 * RunLeaseHeartbeatIT} over a real PostgreSQL row.
 *
 * <p>The heartbeat extends {@code lease_until} only when the run still belongs to the tenant, is
 * still owned by {@code workerId}, and still holds the expected {@code lease_epoch} &mdash; the
 * fence (invariant 5 / ADR-011). A zero-row match &mdash; the run was re-claimed (owner moved
 * and/or epoch bumped), the run is gone, or a cross-tenant id &mdash; reports "not extended", so an
 * old owner cannot renew its lease after it is taken (S07-04 acceptance criterion).
 */
class WorkflowRunRepositoryHeartbeatTest {

  @Test
  void heartbeat_is_a_fenced_conditional_update_not_an_unconditional_write() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID worker = UUID.randomUUID();
    OffsetDateTime until = OffsetDateTime.parse("2026-01-01T00:00:30Z");
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    int affected = repo.heartbeat(tenant, run, worker, 3, until);

    assertThat(affected).isEqualTo(1);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(sql.capture(), any(PreparedStatementSetter.class));

    String query = sql.getValue();
    assertThat(query).contains("UPDATE workflow_runs");
    assertThat(query).contains("SET lease_until = ?");
    // tenant isolation is part of the WHERE (invariant 1)
    assertThat(query).contains("WHERE id = ? AND tenant_id = ?");
    // the fence: only a matching owner AND epoch renews the lease
    assertThat(query).contains("lease_owner = ? AND lease_epoch = ?");
    // the heartbeat only extends, it never bumps the epoch (only the claim does) nor clears the
    // lease
    assertThat(query).doesNotContain("lease_epoch = lease_epoch + 1");
    assertThat(query).doesNotContain("lease_owner = NULL");
  }

  @Test
  void heartbeat_reports_zero_rows_when_the_owner_or_epoch_does_not_match() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

    int affected = repo.heartbeat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, null);

    assertThat(affected).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("unchecked")
  void heartbeat_binds_expiry_run_tenant_worker_and_epoch_in_statement_order() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID worker = UUID.randomUUID();
    int epoch = 7;
    OffsetDateTime until = OffsetDateTime.parse("2026-01-01T00:00:30Z");
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    repo.heartbeat(tenant, run, worker, epoch, until);

    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    verify(jdbc).update(anyString(), setter.capture());

    // the mock never runs the setter — drive it by hand before asserting the binds (depth notes)
    PreparedStatement ps = mock(PreparedStatement.class);
    setter.getValue().setValues(ps);

    verify(ps).setObject(1, until); // lease_until = ?
    verify(ps).setObject(2, run); // WHERE id = ?
    verify(ps).setObject(3, tenant); // AND tenant_id = ?
    verify(ps).setObject(4, worker); // AND lease_owner = ?
    verify(ps).setInt(5, epoch); // AND lease_epoch = ?
  }
}
