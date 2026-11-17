package com.example.agentflow.usage.persistence;

import com.example.agentflow.usage.domain.BudgetDto;
import com.example.agentflow.usage.domain.BudgetPeriod;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps one {@code tenant_budgets} row to the immutable {@link BudgetDto} (S12-04 / {@code
 * V3_3_26}). The read is the mirror of the repository's insert: the {@code period} enum restored
 * from its stored name and the {@code NUMERIC} {@code max_cost} read as a {@code BigDecimal} (money
 * is never a float - invariant 10), the boxed {@code BIGINT} limits read as {@code long}.
 */
class BudgetRowMapper implements RowMapper<BudgetDto> {

  @Override
  public BudgetDto mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new BudgetDto(
        rs.getObject("tenant_id", UUID.class),
        BudgetPeriod.valueOf(rs.getString("period")),
        rs.getLong("max_runs"),
        rs.getLong("max_tokens"),
        asDecimal(rs, "max_cost"));
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
