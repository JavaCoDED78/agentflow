package com.example.agentflow.workflowexecution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * DB-free tests for {@code workflow_runs} atomic claim (S07-03). The {@link JdbcTemplate} is a
 * mock, so the SQL and the bound arguments are asserted directly (the same convention as {@link
 * WorkflowStepRunRepositoryTest}); the real conditional update under concurrency runs in {@link
 * RunLeaseClaimIT} over a real PostgreSQL row.
 *
 * <p>The claim is a single conditional {@code UPDATE ... (WHERE lease_owner IS NULL OR lease_until
 * < now()) AND status not terminal} guarded by the tenant predicate (invariant 1) that atomically
 * sets {@code lease_owner}/{@code lease_until}, bumps {@code lease_epoch} (fencing, ADR-011) and
 * {@code RETURNING lease_epoch} so the winner learns its fence in the same statement. A zero-row
 * match &mdash; the run is terminal, owned by a live worker, or a cross-tenant id &mdash; returns
 * {@link Optional#empty()} &mdash; indistinguishable from a missing run.
 */
class WorkflowRunRepositoryClaimTest {

  @Test
  @SuppressWarnings("unchecked")
  void claim_scopes_by_tenant_and_only_matches_when_owner_absent_or_lease_expired()
      throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID worker = UUID.randomUUID();
    OffsetDateTime leaseUntil = OffsetDateTime.parse("2026-01-01T00:00:30Z");

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(Collections.singletonList(1));

    Optional<Integer> epoch = repo.claim(tenant, run, worker, leaseUntil);

    assertThat(epoch).isPresent();
    assertThat(epoch.get()).isEqualTo(1);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());

    String query = sql.getValue();
    // claim is a conditional update, not an unconditional write (exactly one owner, invariant 4)
    assertThat(query).contains("UPDATE workflow_runs");
    assertThat(query).contains("SET lease_owner = ?, lease_until = ?");
    assertThat(query).contains("lease_epoch = lease_epoch + 1");
    assertThat(query).contains("RETURNING lease_epoch");
    // tenant isolation is part of the WHERE (invariant 1), a cross-tenant row matches nothing
    assertThat(query).contains("WHERE id = ? AND tenant_id = ?");
    // terminal runs are never re-claimed; a QUEUED/RETRY_WAIT run or an expired lease is claimable
    assertThat(query).contains("status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')");
    assertThat(query).contains("lease_owner IS NULL OR lease_until < now()");

    Object[] bound = args.getValue();
    assertThat(bound[0]).isEqualTo(worker);
    assertThat(bound[1]).isEqualTo(leaseUntil);
    assertThat(bound[2]).isEqualTo(run);
    assertThat(bound[3]).isEqualTo(tenant);
  }

  @Test
  void claim_returns_empty_when_no_row_matches() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowRunRepository repo = new WorkflowRunRepository(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(Collections.emptyList());

    assertThat(repo.claim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null)).isEmpty();
  }
}
