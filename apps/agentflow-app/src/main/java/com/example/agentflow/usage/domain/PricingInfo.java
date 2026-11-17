package com.example.agentflow.usage.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * A resolved price for one model version, looked up in the {@code usage.pricing} catalogue
 * (S12-03).
 *
 * <p>It is the immutable value that ties a {@link UsageRecordDto} measurement to the exact price
 * that produced it: {@link #priceVersion()} is the model-plus-version key the estimate was computed
 * against and {@link #pricingRevision()} is the catalogue revision that produced that key, so a
 * {@code usage_records} row can always be explained ("this estimate used price version X from
 * catalogue revision Y, unit Z, price W"). See the {@code S12-03} section of {@code
 * docs/devlog/sprint-12-usage-metering-cost.md}.
 *
 * <p>Immutable: the same model can map to different prices over time, and each {@code PricingInfo}
 * captures one resolved snapshot. A model the catalogue does not know is left unknown by the {@link
 * com.example.agentflow.usage.application.PricingResolver} (never invented, S12-02), so a {@code
 * PricingInfo} here always represents a real, known price version.
 */
public record PricingInfo(
    String priceVersion,
    String pricingRevision,
    String unit,
    BigDecimal unitPrice,
    int unitDivisor,
    String currency,
    LocalDate effective) {

  /**
   * The estimated cost for a token total, computed against this price version: {@code unitPrice *
   * tokens / unitDivisor}, rounded to six decimal places with half-up.
   *
   * <p>A non-positive token total cannot be priced, so {@code null} is returned rather than a zero
   * cost — an unknown count stays unknown, exactly as an unreported token count is stored as {@code
   * null} rather than coerced to zero (S12-02).
   *
   * @param tokens the input plus output token count measured for the call; must be the real total,
   *     not a guessed one
   * @return the estimated cost for {@code tokens}, or {@code null} when {@code tokens <= 0}
   */
  public BigDecimal estimatedCostFor(int tokens) {
    if (tokens <= 0) {
      return null;
    }
    return unitPrice
        .multiply(BigDecimal.valueOf(tokens))
        .divide(BigDecimal.valueOf(unitDivisor), 6, RoundingMode.HALF_UP);
  }
}
