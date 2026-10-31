package com.example.agentflow.messaging.application;

/**
 * The disposition of an inbox message after {@link InboxDedupService#consume} runs (S06-11).
 * Returned to the consumer so it knows whether to acknowledge, redeliver or dead-letter the
 * envelope. It extends the S06-10 result ({@link InboxConsumeResult#OTHER_CLAIMED}, {@link
 * InboxConsumeResult#PROCESSED}, {@link InboxConsumeResult#RETRY}) with the two terminal
 * dispositions a consumer error policy needs once a business change has failed:
 *
 * <ul>
 *   <li>{@link #RETRY} &mdash; a <b>transient</b> fault exhausted the bounded immediate retry; the
 *       message is held for the DLQ and classified {@link InboxErrorClassification#TRANSIENT}.
 *   <li>{@link #DEAD_LETTER} &mdash; a <b>permanent</b> / malformed event; the consumer records it
 *       to the DLQ and moves on (it is <b>never</b> retried, so a malformed event cannot cycle the
 *       consumer forever, S06-11 acceptance criterion).
 * </ul>
 *
 * <p>{@link #PROCESSED} and {@link #OTHER_CLAIMED} are the only dispositions the consumer
 * acknowledges (the business change committed durably, or a duplicate delivery was skipped); the
 * consumer commits the Kafka offset only after such a durable outcome (invariant 3).
 */
public enum InboxConsumeResult {

  /**
   * A different delivery already claimed (or completed) this {@code (consumer_name, event_id)}
   * pair, so this call skipped the business state change without re-running its side effect. A
   * duplicate Kafka delivery never produces a second domain transition.
   */
  OTHER_CLAIMED,

  /** The business state change ran and durably committed exactly once this delivery. */
  PROCESSED,

  /**
   * A transient fault exhausted the bounded immediate retry (S06-10) without a success; the row was
   * recorded {@code FAILED} (never {@code PROCESSED}). Classified {@link
   * InboxErrorClassification#TRANSIENT}; held for the DLQ, never retried unboundedly (S06-11).
   */
  RETRY,

  /**
   * A permanent / malformed event could not be processed and short-circuited the bounded retry
   * (S06-11). The row was recorded {@code FAILED} (never {@code PROCESSED}); classified {@link
   * InboxErrorClassification#PERMANENT}; held for the DLQ, never retried (so the consumer is not
   * cycled on it).
   */
  DEAD_LETTER
}
