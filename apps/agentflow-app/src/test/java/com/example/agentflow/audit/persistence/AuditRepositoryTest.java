package com.example.agentflow.audit.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditEvent;
import com.example.agentflow.audit.domain.AuditOutcome;
import com.example.agentflow.audit.domain.AuditResourceType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

@ExtendWith(MockitoExtension.class)
class AuditRepositoryTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private PreparedStatement ps;

  @Test
  void insert_writes_all_columns_and_returns_persisted_id() throws SQLException {
    AuditRepository repository = new AuditRepository(jdbc);
    AuditEvent event =
        AuditEvent.created(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "alice",
            AuditAction.CREATE,
            AuditResourceType.WORKFLOW_DEFINITION,
            UUID.fromString("00000000-0000-0000-0000-000000000002"));

    // Drive the PreparedStatementSetter against a real PreparedStatement: a stubbed JdbcTemplate
    // returns without running the setter, so the binds would never be exercised (audit
    // persistence testing convention).
    ArgumentCaptor<PreparedStatementSetter> setterCaptor =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(any(String.class), setterCaptor.capture())).thenReturn(1);

    UUID id = repository.insert(event);

    // The stubbed JdbcTemplate.update returns without running the setter, so drive the captured
    // PreparedStatementSetter against the real PreparedStatement to exercise the binds.
    setterCaptor.getValue().setValues(ps);

    Mockito.verify(ps).setObject(1, event.id());
    Mockito.verify(ps).setObject(2, event.tenantId());
    Mockito.verify(ps).setString(3, "alice");
    Mockito.verify(ps).setString(4, AuditAction.CREATE.name());
    Mockito.verify(ps).setString(5, AuditResourceType.WORKFLOW_DEFINITION.name());
    Mockito.verify(ps).setObject(6, event.resourceId());
    Mockito.verify(ps).setString(7, AuditOutcome.SUCCEEDED.name());
    Mockito.verify(ps).setObject(8, null); // correlation_id
    Mockito.verify(ps).setString(9, null); // metadata_redacted
    Mockito.verify(ps).setTimestamp(10, Timestamp.from(event.createdAt()));
    assertThat(id).isEqualTo(event.id());
  }

  @Test
  void row_mapper_maps_all_columns() throws SQLException {
    AuditRowMapper mapper = new AuditRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID resource = UUID.randomUUID();
    Instant created = Instant.now();
    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("tenant_id", UUID.class)).thenReturn(tenant);
    when(rs.getString("actor_subject")).thenReturn("alice");
    when(rs.getString("action")).thenReturn(AuditAction.ACTIVATE.name());
    when(rs.getString("resource_type")).thenReturn(AuditResourceType.WORKFLOW_DEFINITION.name());
    when(rs.getObject("resource_id", UUID.class)).thenReturn(resource);
    when(rs.getString("outcome")).thenReturn(AuditOutcome.SUCCEEDED.name());
    when(rs.getObject("correlation_id", UUID.class)).thenReturn(null);
    when(rs.getString("metadata_redacted")).thenReturn("actor=alice;");
    when(rs.getTimestamp("created_at")).thenReturn(new Timestamp(created.toEpochMilli()));

    AuditEvent mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.id()).isEqualTo(id);
    assertThat(mapped.tenantId()).isEqualTo(tenant);
    assertThat(mapped.actorSubject()).isEqualTo("alice");
    assertThat(mapped.action()).isEqualTo(AuditAction.ACTIVATE);
    assertThat(mapped.resourceType()).isEqualTo(AuditResourceType.WORKFLOW_DEFINITION);
    assertThat(mapped.resourceId()).isEqualTo(resource);
    assertThat(mapped.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
    assertThat(mapped.correlationId()).isNull();
    assertThat(mapped.metadataRedacted()).isEqualTo("actor=alice;");
    // created_at is read as a Timestamp at rest and converted back to an Instant, so nanos are
    // truncated on the roundtrip — compare at millisecond precision.
    assertThat(mapped.createdAt().toEpochMilli()).isEqualTo(created.toEpochMilli());
  }
}
