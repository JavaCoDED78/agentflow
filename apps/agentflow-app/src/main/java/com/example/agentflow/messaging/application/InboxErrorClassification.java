package com.example.agentflow.messaging.application;

/**
 * The consumer-side retry disposition of an inbox message after the business change fails (S06-11).
 * This is the messaging counterpart of the outbox publisher's {@link
 * com.example.agentflow.messaging.worker.KafkaMessageDeliverer} delivery classification: a single,
 * domain-neutral decision the consumer applies so it neither retries a permanent fault forever nor
 * drops a transient one.
 *
 * <ul>
 *   <li><b>{@link #PERMANENT}</b> &mdash; the failure cannot succeed on a re-delivery of the same
 *       envelope (a malformed event, an invalid payload, a classification error). Retrying would
 *       only cycle the consumer, so the message is held for the dead-letter store (the {@code
 *       S06-11} DLQ path) instead of being retried.
 *   <li><b>{@link #TRANSIENT}</b> &mdash; the failure is an infrastructure / external fault that
 *       may succeed on a later attempt (a briefly unreachable dependency, a provider timeout, a
 *       connection reset). It is retried with a <i>bounded</i> number of immediate attempts; only
 *       on exhaustion is it held for the DLQ.
 * </ul>
 *
 * <p>This is a pure classification value (like {@code com.example.agentflow.common.ErrorClass}): it
 * carries no business state and no dependency on the HTTP layer. The {@link InboxErrorPolicy}
 * derives it from the thrown {@link Throwable}; the {@link InboxDedupService} maps {@code
 * PERMANENT} to the {@code InboxConsumeResult#DEAD_LETTER} disposition and {@code TRANSIENT} to
 * {@link InboxConsumeResult#RETRY}.
 */
public enum InboxErrorClassification {

  /** A permanent, non-retryable failure (malformed event / classification error). */
  PERMANENT,

  /** A transient, retryable failure (infrastructure / external fault). */
  TRANSIENT
}
