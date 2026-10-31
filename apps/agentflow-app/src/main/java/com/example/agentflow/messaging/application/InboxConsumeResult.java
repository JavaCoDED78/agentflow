package com.example.agentflow.messaging.application;

/**
 * Outcome of an inbox dedup {@code InboxDedupService#consume} call (S06-10). Returned to the
 * consumer so it can decide whether to acknowledge, redeliver or dead-letter the envelope.
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
   * The bounded local retry was exhausted and the row was recorded as {@code FAILED} (never {@code
   * PROCESSED}); the consumer retry / DLQ policy (S06-11) owns the next step.
   */
  FAILED
}
