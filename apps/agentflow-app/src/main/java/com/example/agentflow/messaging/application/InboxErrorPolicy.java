package com.example.agentflow.messaging.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies an inbox consumer failure as {@link InboxErrorClassification#PERMANENT} or {@link
 * InboxErrorClassification#TRANSIENT} (S06-11), so the consumer retries a transient fault with a
 * bounded number of attempts and dead-letters a permanent one instead of cycling on it forever.
 *
 * <p>The convention mirrors the outbound producer classification ({@link
 * com.example.agentflow.messaging.worker.KafkaMessageDeliverer}): a fault that means the envelope
 * can never be processed &mdash; {@link IllegalArgumentException}, {@link IllegalStateException} or
 * {@link NullPointerException} &mdash; is <b>permanent</b> (a malformed event, an invalid payload,
 * a bad argument), and everything else &mdash; a connection reset, a provider timeout, a throttling
 * or any other infrastructure / external fault &mdash; is <b>transient</b> and retryable.
 *
 * <p>The policy is a pure function of the thrown {@link Throwable} and carries no state; a single
 * stateless instance ({@link #defaults()}) is shared. A {@code null} cause cannot be classified and
 * is treated as transient, so an undetermined fault is retried (never silently dropped) until its
 * bounded attempt limit is exhausted and it is held for the DLQ.
 */
public class InboxErrorPolicy {

  private static final Logger log = LoggerFactory.getLogger(InboxErrorPolicy.class);

  private static final InboxErrorPolicy DEFAULT = new InboxErrorPolicy();

  /**
   * @return a shared, stateless default policy (the stateless {@link #classify} has no instance
   *     state to share)
   */
  public static InboxErrorPolicy defaults() {
    return DEFAULT;
  }

  /**
   * Classify a failure thrown while applying an inbox business change.
   *
   * @param failure the business change failure (may be {@code null} if the cause is undetermined)
   * @return {@link InboxErrorClassification#PERMANENT} for a malformed / invalid event, {@link
   *     InboxErrorClassification#TRANSIENT} for a retryable infrastructure or external fault
   */
  public InboxErrorClassification classify(Throwable failure) {
    if (failure == null) {
      // Undetermined: retry rather than silently drop (bounded by the consumer policy, S06-11).
      return InboxErrorClassification.TRANSIENT;
    }
    if (isMalformed(failure)) {
      log.debug(
          "inbox message classified as PERMANENT (malformed event): {}",
          failure.getClass().getSimpleName());
      return InboxErrorClassification.PERMANENT;
    }
    // Any other fault (connection reset, provider timeout, throttling, ...) is retryable.
    return InboxErrorClassification.TRANSIENT;
  }

  /**
   * A fault is permanent when the envelope itself is malformed and can never be processed, no
   * matter how many times it is redelivered (the same predicate the outbound producer uses to mark
   * a record {@code PERMANENT_FAILURE}, S06-09 / S06-11).
   *
   * @param failure the failure to test
   * @return {@code true} for a malformed / invalid event
   */
  private static boolean isMalformed(Throwable failure) {
    return failure instanceof IllegalArgumentException
        || failure instanceof IllegalStateException
        || failure instanceof NullPointerException;
  }
}
