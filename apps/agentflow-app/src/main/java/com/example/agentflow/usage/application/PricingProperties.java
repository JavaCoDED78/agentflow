package com.example.agentflow.usage.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The versioned price catalogue (S12-03), bound from {@code usage.pricing}.
 *
 * <p>It records, per model, the versioned price, the cost unit and the date the price took effect,
 * plus a catalogue-wide {@code revision} tag and {@code currency}. Bound with the project's
 * typed-configuration idiom ({@code @ConfigurationProperties} + {@code @Validated} +
 * {@code @Valid}): a blank {@code revision} / {@code currency}, a price entry without a unit, or an
 * unrecognised unit fails context startup as a {@code ConfigurationPropertiesBindException}, so an
 * incomplete declaration surfaces immediately rather than as a {@code NullPointerException} later.
 *
 * <p>The catalogue {@code revision} is what every {@link
 * com.example.agentflow.usage.domain.UsageRecordDto} row stores as its {@code pricing_revision}, so
 * a historical estimate is always explainable through the exact price-catalogue snapshot that
 * produced it. A model not listed here is resolved to {@code null} by the {@link PricingResolver} —
 * left unknown, never invented (S12-02).
 */
@ConfigurationProperties(prefix = "usage.pricing")
@Validated
public class PricingProperties {

  @NotBlank(message = "usage.pricing requires a non-blank revision")
  private String revision;

  @NotBlank(message = "usage.pricing requires a non-blank currency")
  private String currency;

  @Valid private List<PriceEntry> entries = new ArrayList<>();

  /**
   * @return the catalogue revision tag stored on every priced usage record (S12-03)
   */
  public String getRevision() {
    return revision;
  }

  /**
   * @return the currency used for every estimated cost in the catalogue
   */
  public String getCurrency() {
    return currency;
  }

  public void setRevision(String revision) {
    this.revision = revision;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  /**
   * @return the declared model prices, in declaration order; never {@code null} (empty means every
   *     model is unknown)
   */
  public List<PriceEntry> getEntries() {
    return entries;
  }

  /**
   * A single model price in the catalogue (S12-03).
   *
   * <p>{@code price} is the price per one {@code unit} (for example {@code "0.03"} per {@code
   * "1k-tokens"}); {@code effective} is the date the price took effect (the price-version
   * timestamp). A blank {@code model} / {@code version} or an unrecognised {@code unit} fails
   * configuration binding (the {@code @NotBlank} / {@code @Pattern} constraints).
   */
  public static class PriceEntry {

    @NotBlank(message = "usage.pricing entries require a non-blank model")
    private String model;

    @NotBlank(message = "usage.pricing entries require a non-blank version")
    private String version;

    @NotNull(message = "usage.pricing entries require a unit")
    @Pattern(
        regexp = "token|1k-tokens|1m-tokens",
        message = "usage.pricing entries require a unit of token, 1k-tokens or 1m-tokens")
    private String unit;

    @NotNull(message = "usage.pricing entries require a price")
    private BigDecimal price;

    @NotNull(message = "usage.pricing entries require an effective date")
    private LocalDate effective;

    /**
     * @return the model id this price applies to (for example {@code gpt-4o})
     */
    public String getModel() {
      return model;
    }

    /**
     * @param model the model id this price applies to
     */
    public void setModel(String model) {
      this.model = model;
    }

    /**
     * @return the price version the catalogue assigns to this model (for example {@code
     *     2026-01-01})
     */
    public String getVersion() {
      return version;
    }

    /**
     * @param version the price version the catalogue assigns to this model
     */
    public void setVersion(String version) {
      this.version = version;
    }

    /**
     * @return the cost unit the {@code price} is expressed per (one of {@code token}, {@code
     *     1k-tokens}, {@code 1m-tokens})
     */
    public String getUnit() {
      return unit;
    }

    /**
     * @param unit the cost unit the {@code price} is expressed per
     */
    public void setUnit(String unit) {
      this.unit = unit;
    }

    /**
     * @return the price per one {@link #getUnit() unit}
     */
    public BigDecimal getPrice() {
      return price;
    }

    /**
     * @param price the price per one {@link #getUnit() unit}
     */
    public void setPrice(BigDecimal price) {
      this.price = price;
    }

    /**
     * @return the date the price took effect (the price-version timestamp)
     */
    public LocalDate getEffective() {
      return effective;
    }

    /**
     * @param effective the date the price took effect
     */
    public void setEffective(LocalDate effective) {
      this.effective = effective;
    }
  }
}
