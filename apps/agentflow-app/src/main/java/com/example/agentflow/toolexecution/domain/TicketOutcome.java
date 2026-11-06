package com.example.agentflow.toolexecution.domain;

/**
 * The three outcomes a {@link TicketLookup#lookup(String, java.util.function.Supplier, int)} call
 * can return (S08-07).
 *
 * <p>They are exactly the outcomes the plan task asks the demo tool to model without any external
 * network:
 *
 * <ul>
 *   <li>{@link #NOT_FOUND} — the requested ticket id is not in the fixture dataset; the lookup
 *       answers deterministically, it does not flap for a record that does not exist;
 *   <li>{@link #TRANSIENT_FAILURE} — a known ticket's demo backend flapped on every attempt; a
 *       retry (bounded by the tool's {@link
 *       com.example.agentflow.toolexecution.domain.RetryPolicy}) may recover, so this is a
 *       <em>transient</em>, not a contract, failure;
 *   <li>{@link #SUCCESS} — the ticket was found and answered on one of the attempts.
 * </ul>
 *
 * <p>The enum is a closed vocabulary, so a lookup result is always classified into one of these and
 * the executor can switch on the outcome without string parsing (the same stable-code discipline as
 * {@link com.example.agentflow.toolexecution.domain.CalculationException#reason()} / {@link
 * com.example.agentflow.toolexecution.domain.SchemaValidationException#reason()}). It carries no
 * Spring, I/O or time/randomness dependency (invariant 10).
 */
public enum TicketOutcome {
  NOT_FOUND,
  TRANSIENT_FAILURE,
  SUCCESS
}
