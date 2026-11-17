package com.example.agentflow.usage.application;

import com.example.agentflow.usage.domain.PricingInfo;
import com.example.agentflow.usage.domain.UnitDivisor;

/**
 * Resolves a model name to a {@link PricingInfo} from the {@link PricingProperties} catalogue
 * (S12-03).
 *
 * <p>Look-up is case-insensitive on the model id. A model the catalogue does not know — or no
 * catalogue at all — resolves to {@code null}. The resolver never invents a price: an unknown model
 * is left unknown, exactly as an unreported token count is stored as {@code null} rather than
 * coerced to zero (S12-02). Callers pass the {@code null} straight through to the {@code
 * UsageRecordDto} factory, which stores {@code null} for {@code price_version} / {@code
 * pricing_revision}.
 */
public final class PricingResolver {

  private final PricingProperties pricing;

  /**
   * @param pricing the price catalogue to resolve against; may be {@code null} when the application
   *     has no catalogue wired (for example a lightweight bootstrap context)
   */
  public PricingResolver(PricingProperties pricing) {
    this.pricing = pricing;
  }

  /**
   * Resolve a model to its price version.
   *
   * @param model the model id that produced the completion; may be {@code null}
   * @return the resolved {@link PricingInfo}, or {@code null} when the catalogue has no entry for
   *     the model
   */
  public PricingInfo resolve(String model) {
    if (pricing == null || model == null || pricing.getEntries().isEmpty()) {
      return null;
    }
    for (PricingProperties.PriceEntry entry : pricing.getEntries()) {
      if (entry.getModel() != null && entry.getModel().equalsIgnoreCase(model)) {
        return new PricingInfo(
            entry.getModel() + "/" + entry.getVersion(),
            pricing.getRevision(),
            entry.getUnit(),
            entry.getPrice(),
            UnitDivisor.of(entry.getUnit()),
            pricing.getCurrency(),
            entry.getEffective());
      }
    }
    return null;
  }
}
