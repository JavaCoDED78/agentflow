package com.example.agentflow.messaging.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A dead-lettered inbox message (S06-11, glossary §6, {@code dlq_messages}), owned by the {@code
 * messaging} module. One row per {@code (consumer_name, event_id)} pair a consumer could not apply
 * &mdash; either a <b>transient</b> fault that exhausted its bounded immediate retry ({@link
 * Status#RETRY}) or a <b>permanent</b> / malformed event ({@link Status#DEAD_LETTER}).
 *
 * <p>It is the durable, held-for-investigation copy of a failed consumer outcome: PostgreSQL is the
 * source of truth (invariant 2), so the {@code inbox_messages} row stays the authoritative record
 * of the disposition and this table is the investigation view a consumer (via {@code
 * com.example.agentflow.messaging.application.DlqService}) appends to when a {@link
 * com.example.agentflow.messaging.application.InboxConsumeResult#RETRY} / {@link
 * com.example.agentflow.messaging.application.InboxConsumeResult#DEAD_LETTER} outcome is recorded.
 * Kafka delivers at-least-once ({@link com.example.agentflow.messaging.domain.OutboxEvent
 * "ADR-003"}), so the same envelope can be dead-lettered more than once; the unique {@code
 * (consumer_name, event_id)} makes a second append a no-op (the DLQ is idempotent by the same dedup
 * key as the inbox). The {@code event_id} is the SAME id as the outbox event id / envelope {@code
 * eventId}, the anchor a consumer re-checks against.
 *
 * <p>Values are immutable; the application-managed {@link #status} and {@link #errorClass} are set
 * only by the {@code DlqService}. The {@code status} here is the consumer error-policy disposition
 * (S06-11), distinct from the {@code inbox_messages.status} lifecycle set by the inbox dedup helper
 * (S06-10); the {@link #errorClass} is the consumer error classification of the failure (S12-10),
 * distinct from {@code status} (the disposition &mdash; what to do with the message &mdash;) and
 * records why it failed.
 */
public record DlqMessage(
    UUID id,
    UUID eventId,
    String topic,
    String consumerName,
    Status status,
    ErrorClass errorClass,
    int attempts,
    String lastError,
    OffsetDateTime recordedAt) {

  /** Consumer error policy disposition of a dead-lettered message (S06-11). */
  public enum Status {

    /**
     * A transient fault exhausted the bounded immediate retry (recorded, held for investigation).
     */
    RETRY,

    /** A permanent / malformed event short-circuited the retry (recorded, never retried). */
    DEAD_LETTER
  }

  /**
   * The consumer error classification of a failed inbox outcome (S12-10), the <i>why it failed</i>
   * half of a DLQ record &mdash; distinct from the {@link #status} disposition, the <i>what to do
   * with it</i>. It mirrors {@link
   * com.example.agentflow.messaging.application.InboxErrorClassification} (the classifier the
   * {@code messaging} consumer applies), expressed in {@code domain} so the record stays free of an
   * {@code application} dependency, exactly as {@link #status} mirrors {@link
   * com.example.agentflow.messaging.application.InboxConsumeResult}.
   *
   * <ul>
   *   <li><b>{@link #PERMANENT}</b> &mdash; the envelope can never be processed on a redelivery (a
   *       malformed / invalid event): the consumer dead-letters it, so this is paired with {@link
   *       Status#DEAD_LETTER}.
   *   <li><b>{@link #TRANSIENT}</b> &mdash; a retryable infrastructure / external fault: the
   *       consumer runs the bounded immediate retry, so this is paired with {@link Status#RETRY}.
   * </ul>
   *
   * <p>It is stored as TEXT and its vocabulary is enforced at the storage layer by the {@code
   * dlq_messages_error_class_check} CHECK (S12-10), so an unknown class cannot reach the
   * investigation view.
   */
  public enum ErrorClass {

    /**
     * A permanent, non-retryable failure (malformed / invalid event); paired with {@link
     * Status#DEAD_LETTER}.
     */
    PERMANENT,

    /**
     * A transient, retryable failure (infrastructure / external fault); paired with {@link
     * Status#RETRY}.
     */
    TRANSIENT
  }

  /**
   * Build a fresh dead-letter record for a consumer + event pair.
   *
   * @param eventId the domain event id, the SAME id as the outbox event id / envelope eventId
   * @param topic the versioned topic the envelope arrived on
   * @param consumerName the inbox consumer identity whose processing failed
   * @param status the consumer error-policy disposition ({@link Status#RETRY} / {@link
   *     Status#DEAD_LETTER})
   * @param errorClass the consumer error classification of the failure ({@link
   *     ErrorClass#PERMANENT} / {@link ErrorClass#TRANSIENT})
   * @param attempts the attempt count after the failed attempts (already the exhausted total)
   * @param lastError the safe, redacted last error
   * @return a new {@link DlqMessage} timestamped now (S06-11 / S12-10)
   */
  public static DlqMessage deadLetter(
      UUID eventId,
      String topic,
      String consumerName,
      Status status,
      ErrorClass errorClass,
      int attempts,
      String lastError) {
    UUID id = UUID.randomUUID();
    return new DlqMessage(
        id,
        eventId,
        topic,
        consumerName,
        status,
        errorClass,
        attempts,
        lastError,
        OffsetDateTime.now());
  }

  /**
   * Build a dead-letter record as read back from {@code dlq_messages}.
   *
   * @param id the row id
   * @param eventId the event half of the dedup key
   * @param topic the versioned topic the envelope arrived on
   * @param consumerName the consumer half of the dedup key
   * @param status the persisted disposition
   * @param errorClass the persisted error classification (PERMANENT / TRANSIENT)
   * @param attempts the attempt count
   * @param lastError the safe, redacted last error
   * @param recordedAt the instant the record was appended
   * @return a {@code dlq_messages} row
   */
  public static DlqMessage read(
      UUID id,
      UUID eventId,
      String topic,
      String consumerName,
      Status status,
      ErrorClass errorClass,
      int attempts,
      String lastError,
      OffsetDateTime recordedAt) {
    return new DlqMessage(
        id, eventId, topic, consumerName, status, errorClass, attempts, lastError, recordedAt);
  }
}
