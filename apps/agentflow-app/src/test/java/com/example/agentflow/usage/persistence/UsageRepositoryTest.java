package com.example.agentflow.usage.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.ChatUsage;
import com.example.agentflow.usage.domain.Measurement;
import com.example.agentflow.usage.domain.UsageRecordDto;
import com.example.agentflow.usage.domain.UsageType;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

/**
 * DB-free coverage of {@link UsageRepository}. The {@link JdbcTemplate} is a stub and the {@link
 * PreparedStatementSetter} is driven against a real {@link PreparedStatement} (audit persistence
 * testing convention), so the binds are exercised even though no database runs.
 */
@ExtendWith(MockitoExtension.class)
class UsageRepositoryTest {

  @Mock private JdbcTemplate jdbc;

  @Mock private PreparedStatement ps;

  @Test
  void insert_binds_all_columns_in_order() throws SQLException {
    UsageRepository repository = new UsageRepository(jdbc);
    UsageRecordDto record =
        new UsageRecordDto(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
            "step-2",
            3,
            UsageType.TOOL,
            Measurement.PROVIDER_REPORTED,
            "FAKE",
            null,
            "ticket_lookup",
            "1.0.0",
            100L,
            40L,
            new BigDecimal("0.05"),
            new BigDecimal("0.04"),
            "1k-tokens",
            "USD",
            "req-789",
            "{\"trace_id\":\"abc\"}");

    ArgumentCaptor<PreparedStatementSetter> setterCaptor =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), setterCaptor.capture())).thenReturn(1);

    repository.insert(record);
    setterCaptor.getValue().setValues(ps);

    verify(ps).setObject(1, record.id());
    verify(ps).setObject(2, record.tenantId());
    verify(ps).setObject(3, record.runId());
    verify(ps).setString(4, "step-2");
    verify(ps).setInt(5, 3);
    verify(ps).setString(6, UsageType.TOOL.name());
    verify(ps).setString(7, Measurement.PROVIDER_REPORTED.name());
    verify(ps).setString(8, "FAKE");
    verify(ps).setString(9, null); // model: none for a tool measurement
    verify(ps).setString(10, "ticket_lookup");
    verify(ps).setString(11, "1.0.0");
    verify(ps).setObject(12, 100L);
    verify(ps).setObject(13, 40L);
    verify(ps).setObject(14, new BigDecimal("0.05"));
    verify(ps).setObject(15, new BigDecimal("0.04"));
    verify(ps).setString(16, "1k-tokens");
    verify(ps).setString(17, "USD");
    verify(ps).setString(18, "req-789");
    verify(ps).setString(19, "{\"trace_id\":\"abc\"}");
  }

  @Test
  void insert_writes_null_token_and_cost_columns_as_null() throws SQLException {
    UsageRepository repository = new UsageRepository(jdbc);
    // A model measurement whose provider reported no tokens / cost — an unknown, recorded as null.
    UsageRecordDto record =
        UsageRecordDto.fromChatResponse(
            UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
            null,
            1,
            "FAKE",
            new ChatResponse("gpt-fake", null, "stop", null));

    ArgumentCaptor<PreparedStatementSetter> setterCaptor =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), setterCaptor.capture())).thenReturn(1);

    repository.insert(record);
    setterCaptor.getValue().setValues(ps);

    // setNull / setObject(col, null): the binds must propagate the unknown as SQL NULL, never 0.
    verify(ps).setObject(12, null);
    verify(ps).setObject(13, null);
    verify(ps).setObject(14, null);
    verify(ps).setObject(15, null);
  }

  @Test
  void find_by_tenant_and_run_builds_tenant_filtered_sql_and_binds() throws SQLException {
    UsageRepository repository = new UsageRepository(jdbc);
    UsageRecordDto record =
        new UsageRecordDto(
            UUID.randomUUID(),
            UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
            null,
            1,
            UsageType.MODEL,
            Measurement.ESTIMATED,
            "FAKE",
            "gpt-fake",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class), any(UUID.class)))
        .thenReturn(List.of(record));

    List<UsageRecordDto> rows =
        repository.findByTenantAndRun(
            UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            UUID.fromString("00000000-0000-0000-0000-0000000000bb"));

    assertThat(rows).containsExactly(record);

    // Capture the SQL in verify (a captor must be consumed there, not declared and unused).
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), any(UUID.class), any(UUID.class));
    assertThat(sqlCaptor.getValue())
        .contains("FROM usage_records")
        .contains("tenant_id = ? AND run_id = ?")
        .contains("ORDER BY created_at, attempt");
  }

  @Test
  void insert_uses_uuid_objects_for_key_columns() throws SQLException {
    // The real-PostgreSQL pitfall: a UUID bound as its string is invisible in the DB. The keys are
    // bound as UUID objects so pgvector / the UUID PK accept them.
    UsageRepository repository = new UsageRepository(jdbc);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    UUID run = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    UsageRecordDto record =
        UsageRecordDto.fromChatResponse(
            tenant, run, null, 1, "FAKE", new ChatResponse("m", "x", null, new ChatUsage(1, 1, 2)));

    ArgumentCaptor<PreparedStatementSetter> setterCaptor =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), setterCaptor.capture())).thenReturn(1);

    repository.insert(record);
    setterCaptor.getValue().setValues(ps);

    verify(ps).setObject(2, tenant);
    verify(ps).setObject(3, run);
  }
}
