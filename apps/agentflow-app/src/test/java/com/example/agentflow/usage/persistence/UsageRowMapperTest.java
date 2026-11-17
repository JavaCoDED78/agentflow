package com.example.agentflow.usage.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.usage.domain.Measurement;
import com.example.agentflow.usage.domain.UsageRecordDto;
import com.example.agentflow.usage.domain.UsageType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free coverage of {@link UsageRowMapper}. The {@code ResultSet} is a stub, so the mapper's
 * reads are driven directly — mirroring {@code WorkflowEventRowMapperTest}.
 */
class UsageRowMapperTest {

  @Test
  void maps_all_columns_including_null_step_run() throws SQLException {
    UsageRowMapper mapper = new UsageRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    UUID run = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("tenant_id", UUID.class)).thenReturn(tenant);
    when(rs.getObject("run_id", UUID.class)).thenReturn(run);
    when(rs.getString("step_run_id")).thenReturn(null);
    when(rs.getInt("attempt")).thenReturn(2);
    when(rs.getString("usage_type")).thenReturn(UsageType.MODEL.name());
    when(rs.getString("measurement")).thenReturn(Measurement.ESTIMATED.name());
    when(rs.getString("provider")).thenReturn("FAKE");
    when(rs.getString("model")).thenReturn("gpt-fake");
    when(rs.getString("tool_name")).thenReturn(null);
    when(rs.getString("tool_version")).thenReturn(null);
    when(rs.getObject("input_tokens")).thenReturn(12L);
    when(rs.getObject("output_tokens")).thenReturn(6L);
    when(rs.getObject("estimated_cost")).thenReturn(new BigDecimal("0.012"));
    when(rs.getObject("provider_cost")).thenReturn(new BigDecimal("0.011"));
    when(rs.getString("unit")).thenReturn("1k-tokens");
    when(rs.getString("currency")).thenReturn("USD");
    when(rs.getString("provider_request_id")).thenReturn("req-789");
    when(rs.getString("provider_metadata")).thenReturn("{\"trace_id\":\"abc\"}");
    when(rs.getString("price_version")).thenReturn("gpt-4o/2026-01-01");
    when(rs.getString("pricing_revision")).thenReturn("pricing-2026-05-01");

    UsageRecordDto mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.id()).isEqualTo(id);
    assertThat(mapped.tenantId()).isEqualTo(tenant);
    assertThat(mapped.runId()).isEqualTo(run);
    assertThat(mapped.stepRunId()).isNull();
    assertThat(mapped.attempt()).isEqualTo(2);
    assertThat(mapped.usageType()).isEqualTo(UsageType.MODEL);
    assertThat(mapped.measurement()).isEqualTo(Measurement.ESTIMATED);
    assertThat(mapped.provider()).isEqualTo("FAKE");
    assertThat(mapped.model()).isEqualTo("gpt-fake");
    assertThat(mapped.toolName()).isNull();
    assertThat(mapped.toolVersion()).isNull();
    assertThat(mapped.inputTokens()).isEqualTo(12L);
    assertThat(mapped.outputTokens()).isEqualTo(6L);
    assertThat(mapped.estimatedCost()).isEqualByComparingTo(new BigDecimal("0.012"));
    assertThat(mapped.providerCost()).isEqualByComparingTo(new BigDecimal("0.011"));
    assertThat(mapped.unit()).isEqualTo("1k-tokens");
    assertThat(mapped.currency()).isEqualTo("USD");
    assertThat(mapped.providerRequestId()).isEqualTo("req-789");
    assertThat(mapped.providerMetadata()).isEqualTo("{\"trace_id\":\"abc\"}");
    // S12-03: the price version + catalogue revision are read back.
    assertThat(mapped.priceVersion()).isEqualTo("gpt-4o/2026-01-01");
    assertThat(mapped.pricingRevision()).isEqualTo("pricing-2026-05-01");
  }

  @Test
  void maps_null_token_and_cost_columns_as_null() throws SQLException {
    UsageRowMapper mapper = new UsageRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("tenant_id", UUID.class)).thenReturn(tenant);
    when(rs.getObject("run_id", UUID.class)).thenReturn(run);
    when(rs.getString("step_run_id")).thenReturn(null);
    when(rs.getInt("attempt")).thenReturn(1);
    when(rs.getString("usage_type")).thenReturn(UsageType.TOOL.name());
    when(rs.getString("measurement")).thenReturn(Measurement.PROVIDER_REPORTED.name());
    when(rs.getString("provider")).thenReturn("FAKE");
    when(rs.getString("model")).thenReturn(null);
    when(rs.getString("tool_name")).thenReturn("ticket_lookup");
    when(rs.getString("tool_version")).thenReturn("1.0.0");
    // The provider reported no tokens / cost — stored as SQL NULL, read back as unknown.
    when(rs.getObject("input_tokens")).thenReturn(null);
    when(rs.getObject("output_tokens")).thenReturn(null);
    when(rs.getObject("estimated_cost")).thenReturn(null);
    when(rs.getObject("provider_cost")).thenReturn(null);
    when(rs.getString("unit")).thenReturn(null);
    when(rs.getString("currency")).thenReturn(null);
    when(rs.getString("provider_request_id")).thenReturn(null);
    when(rs.getString("provider_metadata")).thenReturn(null);
    // S12-03: an unpriced row stores NULL for the versioning columns, read back as null.
    when(rs.getString("price_version")).thenReturn(null);
    when(rs.getString("pricing_revision")).thenReturn(null);

    UsageRecordDto mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.usageType()).isEqualTo(UsageType.TOOL);
    assertThat(mapped.inputTokens()).isNull();
    assertThat(mapped.outputTokens()).isNull();
    assertThat(mapped.estimatedCost()).isNull();
    assertThat(mapped.providerCost()).isNull();
    assertThat(mapped.unit()).isNull();
    assertThat(mapped.providerMetadata()).isNull();
  }

  /**
   * The S12-03 versioning columns are mapped: a non-null {@code price_version} / {@code
   * pricing_revision} on the row flows through to the record, so a stored estimate can be traced
   * back to the exact price version + catalogue revision it was computed against.
   */
  @Test
  void maps_price_version_and_revision() throws SQLException {
    UsageRowMapper mapper = new UsageRowMapper();
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    when(rs.getObject("id", UUID.class)).thenReturn(id);
    when(rs.getObject("tenant_id", UUID.class)).thenReturn(tenant);
    when(rs.getObject("run_id", UUID.class)).thenReturn(run);
    when(rs.getString("step_run_id")).thenReturn("step-42");
    when(rs.getInt("attempt")).thenReturn(1);
    when(rs.getString("usage_type")).thenReturn(UsageType.MODEL.name());
    when(rs.getString("measurement")).thenReturn(Measurement.ESTIMATED.name());
    when(rs.getString("provider")).thenReturn("openai");
    when(rs.getString("model")).thenReturn("gpt-4o");
    when(rs.getString("tool_name")).thenReturn(null);
    when(rs.getString("tool_version")).thenReturn(null);
    when(rs.getObject("input_tokens")).thenReturn(1000);
    when(rs.getObject("output_tokens")).thenReturn(200);
    when(rs.getObject("estimated_cost")).thenReturn(new BigDecimal("0.03"));
    when(rs.getObject("provider_cost")).thenReturn(null);
    when(rs.getString("unit")).thenReturn("1k-tokens");
    when(rs.getString("currency")).thenReturn("USD");
    when(rs.getString("provider_request_id")).thenReturn(null);
    when(rs.getString("provider_metadata")).thenReturn(null);
    when(rs.getString("price_version")).thenReturn("gpt-4o/2026-01-01");
    when(rs.getString("pricing_revision")).thenReturn("pricing-2026-05-01");

    UsageRecordDto mapped = mapper.mapRow(rs, 1);

    assertThat(mapped.priceVersion()).isEqualTo("gpt-4o/2026-01-01");
    assertThat(mapped.pricingRevision()).isEqualTo("pricing-2026-05-01");
  }
}
