package com.example.agentflow.usage.application;

import com.example.agentflow.usage.domain.UsageRecordDto;
import com.example.agentflow.usage.persistence.UsageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The {@code usage} module's recording port (S12-02): the single, uniform entry point that writes a
 * token / cost measurement to {@code usage_records}. Every caller — the model-call settle (S12-04),
 * the tool-history append, the embedding metering — records through here, so the schema the {@code
 * usage} module owns is written in exactly one way.
 *
 * <p>It delegates to {@link UsageRepository}; the recording itself is the repository's guarded
 * insert. Unknown values (a missing provider token count) are already {@code null} on the input
 * {@link UsageRecordDto}, so nothing here invents a zero — {@link UsageRecordDto#fromChatResponse}
 * carries a provider refusal's absent tokens through untouched, and the row stores them as {@code
 * NULL} (unknown, never invented, S12-02).
 *
 * <p>The bean is always registered; the {@link UsageRepository} it persists through is only present
 * with a {@link javax.sql.DataSource}, so it fails loudly (like {@code
 * com.example.agentflow.toolexecution.application.ToolInvocationHistoryService}) when no database
 * is configured — the caller treats a metadata write as non-fatal observability, not business
 * state.
 */
@Service
public class UsageMeteringService {

  /** Estimated-cost counter (S13-13): sum of the {@code estimated_cost} a record carries. */
  static final String COST_METRIC = "agentflow.usage.estimated.cost.usd";

  private final UsageRepository repository;
  private final MeterRegistry meterRegistry;

  /**
   * Back-compat constructor without metrics.
   *
   * @param repository the tenant-scoped {@code usage_records} writer; {@code null} when no database
   *     is configured (the S01-02 default-boot contract)
   */
  public UsageMeteringService(@Autowired(required = false) UsageRepository repository) {
    this(repository, null);
  }

  /**
   * @param repository the tenant-scoped {@code usage_records} writer; {@code null} when no database
   *     is configured (the S01-02 default-boot contract)
   * @param meterRegistry the registry the S13-13 estimated-cost meter registers on; may be {@code
   *     null} (the metering is skipped entirely)
   */
  @Autowired
  public UsageMeteringService(
      @Autowired(required = false) UsageRepository repository,
      @Autowired(required = false) MeterRegistry meterRegistry) {
    this.repository = repository;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Record one measurement. The {@link UsageRecordDto} is already validated by its canonical
   * constructor, so this guards only the repository availability and delegates the write.
   *
   * @param record the measurement to persist; never {@code null}
   * @return the recorded measurement (the same instance the caller handed in, carrying its id), so
   *     a caller can assert exactly what was persisted — usage is reproducible in tests
   * @throws IllegalArgumentException if {@code record} is {@code null}
   * @throws IllegalStateException if no database is configured
   */
  public UsageRecordDto record(UsageRecordDto record) {
    if (record == null) {
      throw new IllegalArgumentException("usage record must not be null");
    }
    if (repository == null) {
      throw new IllegalStateException(
          "usage metering unavailable: no database connection is configured");
    }
    repository.insert(record);
    recordEstimatedCost(record);
    return record;
  }

  /**
   * Record the estimated cost the record carries (S13-13): one increment of the total estimated USD
   * per record, only when the record actually carries a value (never an invented one). Metering
   * never fails a recording (observability, not business state).
   */
  private void recordEstimatedCost(UsageRecordDto record) {
    if (meterRegistry == null || record.estimatedCost() == null) {
      return;
    }
    try {
      double amount = record.estimatedCost().doubleValue();
      if (amount <= 0.0) {
        return;
      }
      Counter.builder(COST_METRIC)
          .description("Estimated cost recorded through the usage seam (S13-13), in USD")
          .register(meterRegistry)
          .increment(amount);
    } catch (RuntimeException | Error ignored) {
      // Metering must never fail a recording.
    }
  }
}
