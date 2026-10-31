package com.example.agentflow.messaging.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A consumer-side inbox dedup record (glossary §6, {@code inbox_messages}), owned by the {@code
 * messaging} module (S06-10).
 *
 * <p>One row per {@code (consumer_name, event_id)} pair a consumer processes. Kafka delivers
 * at-least-once ({@link com.example.agentflow.messaging.domain.OutboxEvent "ADR-003"}), so the same
 * envelope can arrive many times; the composite {@code PRIMARY KEY (consumer_name, event_id)} makes
 * a second insert of the same pair fail the unique constraint, so a duplicate delivery re-runs
 * neither the business state change nor its side effect &mdash; only the first claim proceeds. This
 * is the {@code S06-10} idempotency primitive: the inbox consumer claims an event by inserting its
 * {@code (consumer_name, event_id)} row inside the <i>same</i> PostgreSQL transaction as the
 * business change it applies, so the claim and the change commit or roll back together.
 *
 * <p>The value mirrors the persisted columns one-to-one so the repository maps it without a
 * translation layer. Values are immutable; the application-managed {@link #status} lifecycle
 * ({@link Status#PENDING}, {@link Status#PROCESSED}, {@link Status#FAILED}) is set only by the
 * inbox dedup helper ({@code InboxDedupService}), never by a controller or listener. The {@code
 * event_id} is the SAME id as {@link OutboxEvent#eventId()} / the envelope {@code eventId}: the
 * anchor a consumer re-checks against on every (re)delivery.
 */
public record InboxMessage(
    String consumerName,
    UUID eventId,
    Status status,
    int attempts,
    OffsetDateTime nextAttemptAt,
    String lastError,
    String outcome,
    OffsetDateTime processedAt,
    OffsetDateTime createdAt) {

  /** Processing lifecycle of an inbox record, application-managed by the inbox dedup helper. */
  public enum Status {
    /**
     * Claimed (the {@code (consumer_name, event_id)} row was inserted) but the business state
     * change has neither committed ({@link Status#PROCESSED}) nor been permanently given up on yet.
     * A transient failure leaves the row {@code PENDING} with {@code processed_at} NULL so it stays
     * retryable (S06-10: a transient failure is never marked {@link Status#PROCESSED}).
     */
    PENDING,
    /**
     * The business state change durably committed; the row is complete and ignored on re-delivery.
     */
    PROCESSED,
    /**
     * The bounded local retry was exhausted (S06-10) without a success. {@code next_attempt_at} is
     * NULL (terminal for the local retry) so the row is held for the consumer retry / DLQ policy
     * (S06-11); it is still never {@link Status#PROCESSED}.
     */
    FAILED
  }

  /**
   * Build a fresh claim for a consumer+event pair, the anchor the inbox dedup helper inserts before
   * running the business state change.
   *
   * @param consumerName the inbox consumer identity (e.g. the worker step-handler name)
   * @param eventId the domain event id, the SAME id as the outbox event id / envelope eventId
   * @return a {@link Status#PENDING} claim, timestamped now with zero attempts
   */
  public static InboxMessage claiming(String consumerName, UUID eventId) {
    return new InboxMessage(
        consumerName, eventId, Status.PENDING, 0, null, null, null, null, OffsetDateTime.now());
  }

  /**
   * Build a claim row as read back from {@code inbox_messages}.
   *
   * @param consumerName the consumer half of the dedup key
   * @param eventId the event half of the dedup key
   * @param status the persisted processing status
   * @param attempts the attempt count
   * @param nextAttemptAt the earliest next retry instant, or {@code null}
   * @param lastError the safe, redacted last error
   * @param outcome the recorded business outcome, or {@code null}
   * @param processedAt the instant the row reached {@link Status#PROCESSED}, or {@code null}
   * @param createdAt the immutable creation instant
   * @return an inbox record mapping one {@code inbox_messages} row
   */
  public static InboxMessage claimed(
      String consumerName,
      UUID eventId,
      Status status,
      int attempts,
      OffsetDateTime nextAttemptAt,
      String lastError,
      String outcome,
      OffsetDateTime processedAt,
      OffsetDateTime createdAt) {
    return new InboxMessage(
        consumerName,
        eventId,
        status,
        attempts,
        nextAttemptAt,
        lastError,
        outcome,
        processedAt,
        createdAt);
  }
}
