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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

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
}
