package com.example.agentflow.messaging.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.InboxMessage;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

/**
 * DB-free tests for the {@code inbox_messages} write path (S06-04 / S06-10): the claim insert
 * writes the dedup key and the {@code PENDING} row, and a {@link DuplicateKeyException} (the
 * composite PK rejecting a second delivery) is reported as {@code false}. DB-free: the {@link
 * JdbcTemplate} is a mock, so the SQL and bound arguments are asserted directly.
 */
class InboxMessageRepositoryTest {

  @Test
  void claim_inserts_the_dedup_key_as_pending_and_returns_true() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    InboxMessageRepository repo = new InboxMessageRepository(jdbc);
    UUID eventId = UUID.randomUUID();
    PreparedStatement ps = mock(PreparedStatement.class);
    org.mockito.ArgumentCaptor<PreparedStatementSetter> setter =
        org.mockito.ArgumentCaptor.forClass(PreparedStatementSetter.class);

    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);
    InboxMessage claim = InboxMessage.claiming("agent:run:step", eventId);

    boolean inserted = repo.claim(claim);

    assertThat(inserted).isTrue();
    verify(jdbc).update(anyString(), setter.capture());
    // the dedup key: consumer_name, event_id (status='PENDING', attempts=0, created_at=now() in
    // SQL)
    setter.getValue().setValues(ps);
    verify(ps).setString(1, "agent:run:step"); // consumer_name
    verify(ps).setObject(2, eventId); // event_id
  }

  @Test
  void claim_a_second_delivery_of_the_same_pair_returns_false() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    InboxMessageRepository repo = new InboxMessageRepository(jdbc);
    UUID eventId = UUID.randomUUID();

    // a second insert collides on the composite PK (consumer_name, event_id)
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class)))
        .thenThrow(new DuplicateKeyException("duplicate key value violates unique constraint"));

    boolean inserted = repo.claim(InboxMessage.claiming("agent:run:step", eventId));

    assertThat(inserted).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void mark_processed_moves_a_pending_row_to_processed_and_records_outcome() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    InboxMessageRepository repo = new InboxMessageRepository(jdbc);
    UUID eventId = UUID.randomUUID();

    repo.markProcessed("agent:run:step", eventId, "prior=RUNNING new=SUCCEEDED");

    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.ArgumentCaptor<Object> outcome = org.mockito.ArgumentCaptor.forClass(Object.class);
    org.mockito.ArgumentCaptor<String> consumer = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.ArgumentCaptor<Object> event = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(jdbc).update(sql.capture(), outcome.capture(), consumer.capture(), event.capture());
    assertThat(sql.getValue())
        .contains(
            "UPDATE inbox_messages SET status = 'PROCESSED', outcome = ?, processed_at = now()")
        .contains("WHERE consumer_name = ? AND event_id = ? AND status = 'PENDING'");
    assertThat(outcome.getValue()).isEqualTo("prior=RUNNING new=SUCCEEDED"); // outcome
    assertThat(consumer.getValue()).isEqualTo("agent:run:step"); // consumer_name
    assertThat(event.getValue()).isEqualTo(eventId); // event_id
  }

  @Test
  @SuppressWarnings("unchecked")
  void mark_failed_moves_a_row_to_failed_and_records_attempts_error_and_next_attempt() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    InboxMessageRepository repo = new InboxMessageRepository(jdbc);
    UUID eventId = UUID.randomUUID();

    repo.markFailed("agent:run:step", eventId, 3, null, "TransientExecutionError: attempt 3");

    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.ArgumentCaptor<Object> attempts = org.mockito.ArgumentCaptor.forClass(Object.class);
    org.mockito.ArgumentCaptor<Object> nextAttemptAt =
        org.mockito.ArgumentCaptor.forClass(Object.class);
    org.mockito.ArgumentCaptor<String> lastError =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.ArgumentCaptor<String> consumer = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.ArgumentCaptor<Object> event = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(jdbc)
        .update(
            sql.capture(),
            attempts.capture(),
            nextAttemptAt.capture(),
            lastError.capture(),
            consumer.capture(),
            event.capture());
    assertThat(sql.getValue())
        .contains(
            "UPDATE inbox_messages SET status = 'FAILED', attempts = ?, next_attempt_at = ?, last_error = ?")
        .contains("WHERE consumer_name = ? AND event_id = ?");
    assertThat(attempts.getValue()).isEqualTo(3); // attempts
    assertThat(nextAttemptAt.getValue()).isNull(); // next_attempt_at (terminal hold)
    assertThat(lastError.getValue()).isEqualTo("TransientExecutionError: attempt 3"); // last_error
    assertThat(consumer.getValue()).isEqualTo("agent:run:step"); // consumer_name
    assertThat(event.getValue()).isEqualTo(eventId); // event_id
  }

  @Test
  @SuppressWarnings("unchecked")
  void find_by_consumer_and_event_runs_the_dedup_key_query() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    InboxMessageRepository repo = new InboxMessageRepository(jdbc);
    UUID eventId = UUID.randomUUID();

    repo.findByConsumerAndEvent("agent:run:step", eventId);

    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.ArgumentCaptor<Object> first = org.mockito.ArgumentCaptor.forClass(Object.class);
    org.mockito.ArgumentCaptor<Object> second = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(jdbc).query(sql.capture(), any(RowMapper.class), first.capture(), second.capture());
    assertThat(sql.getValue())
        .contains(
            "SELECT consumer_name, event_id, status, attempts, next_attempt_at, last_error, outcome, processed_at, created_at FROM inbox_messages")
        .contains("WHERE consumer_name = ? AND event_id = ?");
    assertThat(first.getValue()).isEqualTo("agent:run:step");
    assertThat(second.getValue()).isEqualTo(eventId);
  }

  @Test
  void find_by_consumer_and_event_is_empty_when_no_claim_exists() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    InboxMessageRepository repo = new InboxMessageRepository(jdbc);
    UUID eventId = UUID.randomUUID();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());

    Optional<InboxMessage> row = repo.findByConsumerAndEvent("agent:run:step", eventId);

    assertThat(row).isEmpty();
  }

  @Test
  void count_by_consumer_runs_the_count_query() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    InboxMessageRepository repo = new InboxMessageRepository(jdbc);

    when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(0);
    repo.countByConsumer("agent:run:step");

    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForObject(sql.capture(), any(Class.class), any());
    assertThat(sql.getValue())
        .contains("SELECT COUNT(*) FROM inbox_messages WHERE consumer_name = ?");
  }

  @Test
  void count_by_consumer_and_status_filters_on_status() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    InboxMessageRepository repo = new InboxMessageRepository(jdbc);

    when(jdbc.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(0);
    repo.countByConsumerAndStatus("agent:run:step", InboxMessage.Status.PROCESSED);

    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForObject(sql.capture(), any(Class.class), any(), any());
    assertThat(sql.getValue())
        .contains("SELECT COUNT(*) FROM inbox_messages WHERE consumer_name = ? AND status = ?");
  }
}
