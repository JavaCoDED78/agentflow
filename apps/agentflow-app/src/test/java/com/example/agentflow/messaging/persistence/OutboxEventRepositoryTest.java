package com.example.agentflow.messaging.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.OutboxEvent;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

/**
 * DB-free tests for the {@code outbox_events} write path (S06-05): the insert writes every column
 * of the transactional outbox row and returns the row id. DB-free: the {@link JdbcTemplate} is a
 * mock, so the SQL and bound arguments are asserted directly (a stubbed {@code update} returns
 * without running the setter, so it is driven against a real {@link PreparedStatement}).
 */
class OutboxEventRepositoryTest {

  @Test
  @SuppressWarnings("unchecked")
  void insert_writes_all_outbox_columns_and_returns_the_row_id() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OutboxEventRepository repo = new OutboxEventRepository(jdbc);
    UUID id = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    OutboxEvent event =
        OutboxEvent.pending(
            id,
            eventId,
            "workflow.run.requested.v1",
            "agentflow.workflow.run-requested.v1",
            "workflow_run",
            run,
            tenant,
            "{\"runId\":\"" + run + "\"}",
            null);

    UUID persisted = repo.insert(event);

    verify(jdbc).update(sql.capture(), setter.capture());
    // the full outbox row: identity, tenant scope, aggregate, topic, key, payload, status,
    // attempts,
    // created_at
    assertThat(sql.getValue())
        .contains(
            "INSERT INTO outbox_events (id, event_id, tenant_id, aggregate_type, "
                + "aggregate_id, topic, event_key, payload, headers, status, publish_attempts, "
                + "next_attempt_at, last_error, created_at, published_at)")
        .contains("VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), NULL)");
    setter.getValue().setValues(ps);
    verify(ps).setObject(1, id); // id
    verify(ps).setObject(2, eventId); // event_id (idempotent publish key)
    verify(ps).setObject(3, tenant); // tenant_id
    verify(ps).setString(4, "workflow_run"); // aggregate_type
    verify(ps).setObject(5, run); // aggregate_id
    verify(ps).setString(6, "agentflow.workflow.run-requested.v1"); // topic
    verify(ps).setString(7, tenant + ":" + run); // event_key
    verify(ps).setString(8, "{\"runId\":\"" + run + "\"}"); // payload
    verify(ps).setString(9, null); // headers (null for a run request)
    verify(ps).setString(10, OutboxEvent.State.PENDING.name()); // status
    verify(ps).setInt(11, 0); // publish_attempts
    verify(ps).setObject(12, null); // next_attempt_at (first time)
    verify(ps).setString(13, null); // last_error
    assertThat(persisted).isEqualTo(id);
  }

  @Test
  @SuppressWarnings("unchecked")
  void select_for_claim_reads_a_bounded_oldest_first_batch_of_due_rows() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OutboxEventRepository repo = new OutboxEventRepository(jdbc);

    repo.selectForClaim(50);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> first = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> second = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> third = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> fourth = ArgumentCaptor.forClass(Object.class);
    // args for jdbc.query(sql, rowMapper, dueInstant, claimTimeoutSeconds, dueInstant, batchSize):
    // the due instant (PENDING/FAILED next_attempt_at bound), the stale-claim timeout, the due
    // instant again, and the batch bound
    verify(jdbc)
        .query(
            sql.capture(),
            anyRowMapper(),
            first.capture(),
            second.capture(),
            third.capture(),
            fourth.capture());
    assertThat(sql.getValue())
        .contains("FROM outbox_events")
        .contains("(status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))")
        .contains("(status = 'CLAIMED' AND claimed_at <= now() - (INTERVAL '1 second' * ?))")
        .contains("(status = 'FAILED' AND next_attempt_at <= ?)")
        .contains("ORDER BY created_at ASC LIMIT ?");
    // the due instant and the stale-claim timeout come from config/now; the batch is a fixed bound
    assertThat(first.getValue()).isInstanceOf(OffsetDateTime.class);
    assertThat(second.getValue()).isEqualTo(300L);
    assertThat(third.getValue()).isInstanceOf(OffsetDateTime.class);
    assertThat(fourth.getValue()).isEqualTo(50);
  }

  @Test
  @SuppressWarnings("unchecked")
  void claim_reclaims_due_pending_failed_and_stale_claimed_rows_only() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OutboxEventRepository repo = new OutboxEventRepository(jdbc);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();

    repo.claim(List.of(a, b), 300L);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> idsArg = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> timeoutArg = ArgumentCaptor.forClass(Object.class);
    verify(jdbc).query(sql.capture(), anyRowMapper(), idsArg.capture(), timeoutArg.capture());
    // the claim transitions a PENDING/FAILED row or a stale CLAIMED row (claimed_at older than the
    // timeout), bumps attempts and stamps claimed_at, fenced by id = ANY(?) + status so two
    // publishers never claim the same row, and a stale claim is re-claimed (S06-08); RETURNING the
    // updated row(s)
    assertThat(sql.getValue())
        .contains(
            "UPDATE outbox_events SET status = 'CLAIMED', publish_attempts = publish_attempts + 1, claimed_at = now()")
        .contains("WHERE id = ANY(?)")
        .contains("AND status IN ('PENDING', 'CLAIMED', 'FAILED')")
        .contains("AND (status <> 'CLAIMED' OR claimed_at <= now() - (INTERVAL '1 second' * ?))")
        .contains("RETURNING");
    // two arguments: the id list (bound as a single ARRAY) and the stale-claim timeout
    Object[] ids = (Object[]) idsArg.getValue();
    assertThat(ids).hasSize(2);
    assertThat(ids[0]).isEqualTo(a);
    assertThat(ids[1]).isEqualTo(b);
    assertThat(timeoutArg.getValue()).isEqualTo(300L);
  }

  @Test
  void mark_sent_fences_on_claimed_status() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OutboxEventRepository repo = new OutboxEventRepository(jdbc);
    UUID id = UUID.randomUUID();

    repo.markSent(id);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
    verify(jdbc).update(sql.capture(), arg.capture());
    assertThat(sql.getValue())
        .contains("UPDATE outbox_events SET status = 'SENT', published_at = now()")
        .contains("WHERE id = ? AND status = 'CLAIMED'");
    // a single bound id (the ? in the fence)
    assertThat(arg.getValue()).isEqualTo(id);
  }

  @Test
  void mark_failed_fences_on_claimed_status_and_records_attempts_error_and_retry_instant() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OutboxEventRepository repo = new OutboxEventRepository(jdbc);
    UUID id = UUID.randomUUID();

    repo.markFailed(id, 3, OffsetDateTime.now().plusSeconds(30), "broker transient");

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> attempts = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> nextAttemptAt = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> lastError = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> capturedId = ArgumentCaptor.forClass(Object.class);
    verify(jdbc)
        .update(
            sql.capture(),
            attempts.capture(),
            nextAttemptAt.capture(),
            lastError.capture(),
            capturedId.capture());
    assertThat(sql.getValue())
        .contains(
            "UPDATE outbox_events SET status = 'FAILED', publish_attempts = ?, next_attempt_at = ?, last_error = ?")
        .contains("WHERE id = ? AND status = 'CLAIMED'");
    assertThat(attempts.getValue()).isEqualTo(3);
    assertThat(nextAttemptAt.getValue()).isInstanceOf(OffsetDateTime.class);
    assertThat(lastError.getValue()).isEqualTo("broker transient");
    assertThat(capturedId.getValue()).isEqualTo(id);
  }

  private static RowMapper<?> anyRowMapper() {
    return any(RowMapper.class);
  }
}
