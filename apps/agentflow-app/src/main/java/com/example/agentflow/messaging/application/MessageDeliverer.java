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
 *   <li>{@link DeliverResult#DELIVERED} &mdash; the broker <b>acknowledged</b> the envelope
 *       (producer confirmation); the publisher records {@code SENT} and only now stamps {@code
 *       published_at} (S06-08: published is marked only after broker ack).
 *   <li>{@link DeliverResult#TRANSIENT_FAILURE} &mdash; a retryable fault the broker explicitly
 *       rejected (broker unreachable); the publisher retries at a bounded-backoff {@code
 *       next_attempt_at}. The broker did <i>not</i> accept it, so a retry is a clean re-delivery
 *       (no duplicate).
 *   <li>{@link DeliverResult#AMBIGUOUS} &mdash; the producer call returned <b>no</b> confirmation
 *       (timeout / connection reset) so the outcome is <b>undetermined</b>: the broker may or may
 *       not have the envelope. The publisher must <b>never</b> mark it {@code SENT} or stamp {@code
 *       published_at} (S06-08), and instead re-publishes it at a bounded-backoff instant ({@code
 *       S06-11}). A re-publish of a row the broker already holds is a <b>duplicate</b>, not a loss
 *       &mdash; the consumer-side inbox dedups it (S06-10), honoring at-least-once (ADR-003). The
 *       same no-loss property holds for the crash-after-publish-before-DB-update window (S06-08): a
 *       row the publisher crashed on is re-fetched (stale {@code CLAIMED}) and re-published &mdash;
 *       duplicate, never lost.
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
    /**
     * An undetermined (ambiguous) outcome: the producer returned no confirmation (broker timeout /
     * connection reset). The broker may or may not have the envelope, so the publisher must not
     * mark it delivered &mdash; it re-publishes at a bounded-backoff instant (S06-08). A re-publish
     * can duplicate a delivered envelope (at-least-once, ADR-003); it never loses it.
     */
    AMBIGUOUS,
    /** A non-retryable fault; the row is held for the DLQ ({@code S06-11}) instead of retrying. */
    PERMANENT_FAILURE
  }
}
