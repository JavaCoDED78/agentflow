package com.example.agentflow.messaging.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.domain.WorkflowEvent;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowEventRepositoryTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private PreparedStatement ps;

  @Test
  void insert_writes_all_columns_and_returns_persisted_id() throws SQLException {
    WorkflowEventRepository repository = new WorkflowEventRepository(jdbc);
    UUID id = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID step = UUID.randomUUID();
    WorkflowEvent event =
        new WorkflowEvent(id, tenant, run, step, "STEP_RUNNING", "step=LLM_PLAN", Instant.now());

    // Drive the PreparedStatementSetter against a real PreparedStatement: a stubbed JdbcTemplate
    // returns without running the setter, so the binds would never be exercised (audit
    // persistence testing convention).
    ArgumentCaptor<PreparedStatementSetter> setterCaptor =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(any(String.class), setterCaptor.capture())).thenReturn(1);

    UUID persisted = repository.insert(event);

    // A stubbed JdbcTemplate.update returns without running the setter, so drive the captured
    // PreparedStatementSetter against the real PreparedStatement to exercise the binds.
    setterCaptor.getValue().setValues(ps);

    Mockito.verify(ps).setObject(1, id);
    Mockito.verify(ps).setObject(2, tenant);
    Mockito.verify(ps).setObject(3, run);
    Mockito.verify(ps).setObject(4, step);
    Mockito.verify(ps).setString(5, "STEP_RUNNING");
    Mockito.verify(ps).setString(6, "step=LLM_PLAN");
    Mockito.verify(ps).setObject(7, event.occurredAt());
    assertThat(persisted).isEqualTo(id);
  }

  @Test
  void insert_binds_null_step_and_payload_for_run_level_event() throws SQLException {
    WorkflowEventRepository repository = new WorkflowEventRepository(jdbc);
    UUID id = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    WorkflowEvent event = new WorkflowEvent(id, tenant, run, null, "RUNNING", null, Instant.now());

    ArgumentCaptor<PreparedStatementSetter> setterCaptor =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(any(String.class), setterCaptor.capture())).thenReturn(1);

    repository.insert(event);

    setterCaptor.getValue().setValues(ps);

    Mockito.verify(ps).setObject(4, null); // step_run_id
    Mockito.verify(ps).setString(6, null); // payload_redacted
  }

  @Test
  void findByTenantAndRunId_scopes_by_run_and_tenant_and_orders() throws SQLException {
    WorkflowEventRepository repository = new WorkflowEventRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    WorkflowEvent expected =
        new WorkflowEvent(
            UUID.randomUUID(),
            tenant,
            run,
            null,
            "RUNNING",
            "prior=QUEUED new=RUNNING",
            Instant.now());

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class), any(UUID.class)))
        .thenReturn(new ArrayList<>(List.of(expected)));

    List<WorkflowEvent> history = repository.findByTenantAndRunId(tenant, run);

    // The WHERE scopes by run_id AND tenant_id (invariant 1) and the ORDER BY replays the history.
    Mockito.verify(jdbc)
        .query(sqlCaptor.capture(), any(RowMapper.class), any(UUID.class), any(UUID.class));
    assertThat(sqlCaptor.getValue())
        .contains("WHERE run_id = ? AND tenant_id = ?")
        .contains("ORDER BY occurred_at");
    assertThat(history).containsExactly(expected);
  }
}
