package com.example.agentflow.messaging.application;

import com.example.agentflow.messaging.domain.OutboxEvent;

/**
 * Outbound delivery port for the outbox publisher (S06-07).
 *
 * <p>The publisher is transport-agnostic: it claims a row, asks a {@link MessageDeliverer} to hand
 * the envelope to the broker, and then records the outcome in the outbox. The real Kafka-backed
 * adapter is wired by the producer-reliability task ({@code S06-09}); until then no deliverer bean
 * exists and the publisher polls a no-op (rows stay {@code PENDING} and are not lost).
 *
 * <p>The outcome classifies the external (broker) call so the publisher can apply bounded retry
 * (invariant 6: an external call is not exactly-once, so an ambiguous outcome is recorded, never
 * treated as delivered):
 *
 * <ul>
 *   <li>{@link DeliverResult#DELIVERED} &mdash; the broker accepted the envelope;
 *   <li>{@link DeliverResult#TRANSIENT_FAILURE} &mdash; a retryable fault (broker unreachable,
 *       timeout); the publisher retries at a bounded-backoff {@code next_attempt_at};
 *   <li>{@link DeliverResult#PERMANENT_FAILURE} &mdash; a non-retryable fault (malformed envelope);
 *       the row is held for the DLQ ({@code S06-11}) instead of retrying forever.
 * </ul>
 */
@FunctionalInterface
public interface MessageDeliverer {

  /**
   * Deliver one claimed envelope to the broker, outside any database transaction (S06-07: the
   * publisher never holds a transaction across a network call).
   *
   * @param event the claimed outbox row to deliver
   * @return the broker-outcome classification the publisher uses to update the outbox
   */
  DeliverResult deliver(OutboxEvent event);

  /** The classified outcome of a single broker delivery attempt (invariant 6). */
  enum DeliverResult {
    /** The broker accepted the envelope; the publisher records {@code SENT}. */
    DELIVERED,
    /** A retryable fault; the publisher retries at a bounded-backoff instant. */
    TRANSIENT_FAILURE,
    /** A non-retryable fault; the row is held for the DLQ ({@code S06-11}) instead of retrying. */
    PERMANENT_FAILURE
  }
}
