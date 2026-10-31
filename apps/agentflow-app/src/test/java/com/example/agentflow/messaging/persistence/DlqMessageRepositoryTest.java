package com.example.agentflow.messaging.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.DlqMessage;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

/**
 * DB-free tests for the {@code dlq_messages} write path (S06-11): a mocked {@link JdbcTemplate}
 * lets the SQL and bound arguments be asserted directly. The {@code record} is an idempotent upsert
 * &mdash; the composite {@code (consumer_name, event_id)} with {@code ON CONFLICT ... DO NOTHING}
 * (invariant 3 / ADR-003) &mdash; and the {@code event_id} is the same id as the outbox event id
 * and the envelope {@code eventId}.
 */
class DlqMessageRepositoryTest {

  @Test
  @SuppressWarnings("unchecked")
  void record_inserts_the_dlq_record_and_is_idempotent_on_conflict() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DlqMessageRepository repo = new DlqMessageRepository(jdbc);
    DlqMessage record =
        DlqMessage.deadLetter(
            UUID.randomUUID(),
            "agent-run.v1",
            "agent:run:step",
            DlqMessage.Status.DEAD_LETTER,
            3,
            "IllegalArgumentException: malformed");
    PreparedStatement ps = mock(PreparedStatement.class);

    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);
    boolean inserted = repo.record(record);

    assertThat(inserted).isTrue();
    org.mockito.ArgumentCaptor<PreparedStatementSetter> setter =
        org.mockito.ArgumentCaptor.forClass(PreparedStatementSetter.class);
    verify(jdbc).update(anyString(), setter.capture());
    setter.getValue().setValues(ps);
    // bind order matches the INSERT columns (recorded_at is now())
    verify(ps).setObject(1, record.id()); // id (gen_random_uuid in SQL)
    verify(ps).setObject(2, record.eventId()); // event_id (the envelope id; invariant 3)
    verify(ps).setString(3, record.topic()); // topic
    verify(ps).setString(4, record.consumerName()); // consumer_name (dedup key)
    verify(ps).setString(5, record.status().name()); // status
    verify(ps).setInt(6, record.attempts()); // attempts
    verify(ps).setString(7, record.lastError()); // last_error
  }

  @Test
  @SuppressWarnings("unchecked")
  void record_runs_the_idempotent_upsert_sql() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DlqMessageRepository repo = new DlqMessageRepository(jdbc);
    DlqMessage record =
        DlqMessage.deadLetter(
            UUID.randomUUID(),
            "agent-run.v1",
            "agent:run:step",
            DlqMessage.Status.RETRY,
            1,
            "transient");

    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);
    repo.record(record);

    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(sql.capture(), any(PreparedStatementSetter.class));
    assertThat(sql.getValue())
        .contains("INSERT INTO dlq_messages")
        .contains("ON CONFLICT (consumer_name, event_id) DO NOTHING");
  }

  @Test
  @SuppressWarnings("unchecked")
  void find_by_consumer_and_event_runs_the_dedup_key_query() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DlqMessageRepository repo = new DlqMessageRepository(jdbc);
    UUID eventId = UUID.randomUUID();

    repo.findByConsumerAndEvent("agent:run:step", eventId);

    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.ArgumentCaptor<Object> first = org.mockito.ArgumentCaptor.forClass(Object.class);
    org.mockito.ArgumentCaptor<Object> second = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(jdbc).query(sql.capture(), any(RowMapper.class), first.capture(), second.capture());
    assertThat(sql.getValue())
        .contains(
            "SELECT id, event_id, topic, consumer_name, status, attempts, last_error, "
                + "recorded_at FROM dlq_messages")
        .contains("WHERE consumer_name = ? AND event_id = ?");
    assertThat(first.getValue()).isEqualTo("agent:run:step");
    assertThat(second.getValue()).isEqualTo(eventId);
  }

  @Test
  void find_by_consumer_and_event_is_empty_when_no_record_exists() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DlqMessageRepository repo = new DlqMessageRepository(jdbc);
    UUID eventId = UUID.randomUUID();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenReturn(java.util.List.of());

    Optional<DlqMessage> row = repo.findByConsumerAndEvent("agent:run:step", eventId);

    assertThat(row).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void count_by_consumer_runs_the_count_query() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DlqMessageRepository repo = new DlqMessageRepository(jdbc);

    when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(0);
    repo.countByConsumer("agent:run:step");

    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForObject(sql.capture(), any(Class.class), any());
    assertThat(sql.getValue())
        .contains("SELECT COUNT(*) FROM dlq_messages WHERE consumer_name = ?");
  }

  @Test
  @SuppressWarnings("unchecked")
  void count_by_consumer_and_status_filters_on_status() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DlqMessageRepository repo = new DlqMessageRepository(jdbc);

    when(jdbc.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(0);
    repo.countByConsumerAndStatus("agent:run:step", DlqMessage.Status.DEAD_LETTER);

    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForObject(sql.capture(), any(Class.class), any(), any());
    assertThat(sql.getValue())
        .contains("SELECT COUNT(*) FROM dlq_messages WHERE consumer_name = ? AND status = ?");
  }
}
