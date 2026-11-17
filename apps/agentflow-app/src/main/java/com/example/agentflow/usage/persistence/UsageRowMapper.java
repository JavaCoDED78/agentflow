package com.example.agentflow.usage.persistence;

import com.example.agentflow.usage.domain.Measurement;
import com.example.agentflow.usage.domain.UsageRecordDto;
import com.example.agentflow.usage.domain.UsageType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps one {@code usage_records} row to the immutable {@link UsageRecordDto} (S12-01 / {@code
 * V3_3_24}). The read is the mirror of the repository's insert: same column order, same nullable
 * handling, the enums restored from their stored names, the nullable {@code BIGINT} / {@code
 * NUMERIC} token / cost columns read as {@code null} when the row stored SQL NULL (an unknown is
 * read back as unknown, never a coerced zero — S12-02).
 */
class UsageRowMapper implements RowMapper<UsageRecordDto> {

  @Override
  public UsageRecordDto mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new UsageRecordDto(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getString("step_run_id"),
        rs.getInt("attempt"),
        UsageType.valueOf(rs.getString("usage_type")),
        Measurement.valueOf(rs.getString("measurement")),
        rs.getString("provider"),
        rs.getString("model"),
        rs.getString("tool_name"),
        rs.getString("tool_version"),
        asLong(rs, "input_tokens"),
        asLong(rs, "output_tokens"),
        asDecimal(rs, "estimated_cost"),
        asDecimal(rs, "provider_cost"),
        rs.getString("unit"),
        rs.getString("currency"),
        rs.getString("provider_request_id"),
        rs.getString("provider_metadata"));
  }

  /**
   * Read a nullable {@code BIGINT} column as a boxed {@code Long}: {@code null} when the row stored
   * SQL NULL (an unknown token count), the value otherwise.
   */
  private static Long asLong(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : ((Number) value).longValue();
  }

  /**
   * Read a nullable {@code NUMERIC} column as a {@code BigDecimal}: {@code null} when the row
   * stored SQL NULL, the value otherwise. {@code NUMERIC} round-trips exactly (money is never a
   * float).
   */
  private static BigDecimal asDecimal(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : new BigDecimal(value.toString());
  }
}
