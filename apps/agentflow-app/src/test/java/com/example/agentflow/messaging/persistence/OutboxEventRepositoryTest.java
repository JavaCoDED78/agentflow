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
    // args for jdbc.query(sql, rowMapper, dueInstant, batchSize): the due bound, then the batch
    // bound
    verify(jdbc).query(sql.capture(), anyRowMapper(), first.capture(), second.capture());
    assertThat(sql.getValue())
        .contains("FROM outbox_events")
        .contains("WHERE status = 'PENDING'")
        .contains("(next_attempt_at IS NULL OR next_attempt_at <= ?)")
        .contains("ORDER BY created_at ASC LIMIT ?");
    // the due instant is bounded by now() and the batch is a fixed bound — both from config
    assertThat(first.getValue()).isInstanceOf(OffsetDateTime.class);
    assertThat(second.getValue()).isEqualTo(50);
  }

  @Test
  @SuppressWarnings("unchecked")
  void claim_atomicly_fences_on_status_pending_and_returns_only_claimed_rows() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OutboxEventRepository repo = new OutboxEventRepository(jdbc);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();

    repo.claim(List.of(a, b));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> idsArg = ArgumentCaptor.forClass(Object.class);
    verify(jdbc).query(sql.capture(), anyRowMapper(), idsArg.capture());
    // the claim is a conditional UPDATE … WHERE status = 'PENDING': atomic (S06-07 acceptance),
    // it bumps publish_attempts and RETURNINGs the updated row(s)
    assertThat(sql.getValue())
        .contains(
            "UPDATE outbox_events SET status = 'CLAIMED', publish_attempts = publish_attempts + 1")
        .contains("WHERE id = ANY (?) AND status = 'PENDING' RETURNING");
    // exactly one argument, the id list, bound as a single ARRAY
    Object[] ids = (Object[]) idsArg.getValue();
    assertThat(ids).hasSize(2);
    assertThat(ids[0]).isEqualTo(a);
    assertThat(ids[1]).isEqualTo(b);
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
