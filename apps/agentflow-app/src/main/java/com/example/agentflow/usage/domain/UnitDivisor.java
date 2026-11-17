package com.example.agentflow.usage.domain;

/**
 * The token-count divisor of a price {@code unit}: how many tokens one {@code unitPrice} covers.
 *
 * <p>The catalogue prices are expressed per unit ({@code "token"}, {@code "1k-tokens"} or {@code
 * "1m-tokens"} — the only spellings the {@code usage.pricing} configuration accepts). A total token
 * count is priced with {@code unitPrice * tokens / divisor} (see {@link
 * PricingInfo#estimatedCostFor}), so {@code "1k-tokens"} divides the raw count by 1000 before
 * multiplying by the per-1k price.
 *
 * <p>This is a pure value used only inside the {@code usage} module; the unit vocabulary itself is
 * fixed by the {@code PriceEntry.unit} pattern so an unknown spelling fails configuration binding.
 */
public final class UnitDivisor {

  private UnitDivisor() {}

  /**
   * The divisor for a price unit.
   *
   * @param unit the catalogue unit; one of {@code "token"}, {@code "1k-tokens"}, {@code
   *     "1m-tokens"}
   * @return 1 for {@code token}, 1000 for {@code 1k-tokens}, 1_000_000 for {@code 1m-tokens}
   */
  public static int of(String unit) {
    return switch (unit) {
      case "token" -> 1;
      case "1k-tokens" -> 1_000;
      case "1m-tokens" -> 1_000_000;
      default -> 1; // defensive: the catalogue pattern already rejects other spellings
    };
  }
}
