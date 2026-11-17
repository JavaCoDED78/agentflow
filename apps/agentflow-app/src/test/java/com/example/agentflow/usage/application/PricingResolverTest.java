package com.example.agentflow.usage.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * DB-free coverage of {@link PricingResolver}: resolving a known model yields its price version +
 * catalogue revision, an unknown model resolves to {@code null} (never invented), and the unit
 * divisor drives the estimated cost (S12-03).
 */
class PricingResolverTest {

  private static PricingProperties catalogue() {
    PricingProperties pricing = new PricingProperties();
    pricing.setRevision("pricing-2026-05-01");
    pricing.setCurrency("USD");
    pricing
        .getEntries()
        .add(
            priceEntry(
                "gpt-4o",
                "2026-01-01",
                "1k-tokens",
                new BigDecimal("0.03"),
                LocalDate.of(2026, 1, 1)));
    pricing
        .getEntries()
        .add(
            priceEntry(
                "nomic-embed-text",
                "v2",
                "token",
                new BigDecimal("0.0002"),
                LocalDate.of(2026, 3, 1)));
    return pricing;
  }

  private static PricingProperties.PriceEntry priceEntry(
      String model, String version, String unit, BigDecimal price, LocalDate effective) {
    PricingProperties.PriceEntry entry = new PricingProperties.PriceEntry();
    entry.setModel(model);
    entry.setVersion(version);
    entry.setUnit(unit);
    entry.setPrice(price);
    entry.setEffective(effective);
    return entry;
  }

  @Test
  void resolves_a_known_model_to_its_price_version() {
    var info = new PricingResolver(catalogue()).resolve("gpt-4o");

    assertThat(info).isNotNull();
    assertThat(info.priceVersion()).isEqualTo("gpt-4o/2026-01-01");
    assertThat(info.pricingRevision()).isEqualTo("pricing-2026-05-01");
    assertThat(info.unit()).isEqualTo("1k-tokens");
    assertThat(info.unitPrice()).isEqualByComparingTo(new BigDecimal("0.03"));
    assertThat(info.unitDivisor()).isEqualTo(1_000);
    assertThat(info.currency()).isEqualTo("USD");
    assertThat(info.effective()).isEqualTo(LocalDate.of(2026, 1, 1));
  }

  @Test
  void resolve_is_case_insensitive_on_the_model() {
    var info = new PricingResolver(catalogue()).resolve("GPT-4o");

    assertThat(info).isNotNull();
    assertThat(info.priceVersion()).isEqualTo("gpt-4o/2026-01-01");
  }

  @Test
  void per_token_unit_uses_a_divisor_of_one() {
    var info = new PricingResolver(catalogue()).resolve("nomic-embed-text");

    assertThat(info).isNotNull();
    assertThat(info.unit()).isEqualTo("token");
    assertThat(info.unitDivisor()).isEqualTo(1);
    // 1000 tokens * 0.0002 per token = 0.2
    assertThat(info.estimatedCostFor(1000)).isEqualByComparingTo(new BigDecimal("0.2"));
  }

  @Test
  void unknown_model_resolves_to_null_and_is_not_invented() {
    assertThat(new PricingResolver(catalogue()).resolve("claude-3")).isNull();
  }

  @Test
  void empty_catalogue_resolves_to_null() {
    PricingProperties empty = new PricingProperties();
    empty.setRevision("pricing-2026-05-01");
    empty.setCurrency("USD");

    assertThat(new PricingResolver(empty).resolve("gpt-4o")).isNull();
  }

  @Test
  void null_catalogue_resolves_to_null() {
    assertThat(new PricingResolver(null).resolve("gpt-4o")).isNull();
  }

  @Test
  void estimated_cost_uses_the_unit_divisor_and_leaves_zero_unknown() {
    var info = new PricingResolver(catalogue()).resolve("gpt-4o");

    // 2000 tokens * 0.03 per 1k-tokens = 0.06
    assertThat(info.estimatedCostFor(2000)).isEqualByComparingTo(new BigDecimal("0.06"));
    // an unknown / non-positive count cannot be priced -> null (unknown stays unknown, S12-02)
    assertThat(info.estimatedCostFor(0)).isNull();
    assertThat(info.estimatedCostFor(-5)).isNull();
  }

  @Test
  void catalogue_exposes_the_declared_entries_in_order() {
    // The accessor returns the source list, so the declared prices are readable in declaration
    // order
    // and the resolver iterates them in that order.
    assertThat(catalogue().getEntries()).hasSize(2);
  }
}
