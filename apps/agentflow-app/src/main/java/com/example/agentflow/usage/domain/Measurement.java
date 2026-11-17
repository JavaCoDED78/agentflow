package com.example.agentflow.usage.domain;

/**
 * Whether a {@code usage_records} measurement was computed locally or read from the provider
 * (S12-01 / {@code V3_3_24}). Bounded by the {@code usage_records_measurement_check} CHECK at the
 * storage layer — the storage-level expression of the "distinguish estimated from provider-reported
 * usage" invariant.
 *
 * <ul>
 *   <li>{@link #ESTIMATED} — computed locally from the price catalogue (the pre-call reservation).
 *       The {@link com.example.agentflow.usage.domain.UsageRecordDto#estimatedCost()} carries the
 *       value. A record written before a provider reply is {@link #ESTIMATED} by default, so it is
 *       truthful even with no reported values yet (the migration {@code DEFAULT}).
 *   <li>{@link #PROVIDER_REPORTED} — the actual token counts / cost the provider reported back. The
 *       {@link com.example.agentflow.usage.domain.UsageRecordDto#providerCost()} carries the value.
 * </ul>
 *
 * <p>The two coexist on different rows for the same attempt: the estimated row reserves the budget
 * before the call (S12-04), the provider-reported row settles it after (S12-06).
 */
public enum Measurement {

  /** A locally computed value (the reservation / estimate). */
  ESTIMATED,

  /** The value the model provider actually reported back. */
  PROVIDER_REPORTED
}
