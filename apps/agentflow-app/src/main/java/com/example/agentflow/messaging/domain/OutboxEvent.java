package com.example.agentflow.messaging.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A domain event queued for delivery but not yet acknowledged by its inbox consumers, owned by the
 * {@code messaging} module (S06-05 outbox write path, S06-07 publisher).
 *
 * <p>An {@code outbox_events} row is written in the same database transaction as the business
 * change it describes and is {@code PENDING} until the publisher (S06-07) claims it. The publisher
 * moves it to {@code CLAIMED} with an atomic conditional update (so two publishers can never claim
 * the same row), publishes it outside any database transaction, and then records the broker outcome
 * &mdash; {@code SENT} on delivery (awaiting the consumer's acknowledgement, S06-08), {@code
 * FAILED} on a transient or permanent failure with a bounded-retry {@code next_attempt_at}. A
 * consumer's acknowledgement is recorded separately in {@code inbox_messages} (S06-10). This value
 * mirrors the persisted columns one-to-one so the repository maps it without a translation layer.
 * The {@code tenant_id} is stored directly because {@code messaging} owns the table and scopes its
 * own reads by it, so a cross-tenant id is indistinguishable from a missing row (invariant 1).
 * Values are immutable; state transitions return new instances.
 */
public record OutboxEvent(
    UUID id,
    UUID eventId,
    UUID tenantId,
    String aggregateType,
    UUID aggregateId,
    String topic,
    String eventKey,
    String payloadJson,
    String headersJson,
    State status,
    int publishAttempts,
    OffsetDateTime nextAttemptAt,
    String lastError,
    OffsetDateTime createdAt,
    OffsetDateTime publishedAt) {

  /** Publish lifecycle of a queued event. */
  public enum State {
    /** Queued, not yet published to Kafka. */
    PENDING,
    /**
     * Claimed by the outbox publisher (S06-07): the row has been moved out of {@link #PENDING} by
     * the atomic claim and is being published to the broker; it is not yet delivered, so a crash
     * before the outcome update leaves it for a later recovery pass (S06-08 / S06-14).
     */
    CLAIMED,
    /** Delivered to Kafka but not yet acknowledged by a consumer. */
    SENT,
    /** Last publish attempt failed; retried at {@code nextAttemptAt}. */
    FAILED,
    /** Published and acknowledged; a terminal, ignored-by-publisher state. */
    PUBLISHED
  }

  public OutboxEvent {
    if (status == null) {
      status = State.PENDING;
    }
    if (publishAttempts < 0) {
      publishAttempts = 0;
    }
  }

  /**
   * @return a copy carrying the incremented publish attempt count and its error.
   */
  public OutboxEvent withAttempt(
      int publishAttempts, OffsetDateTime nextAttemptAt, String lastError) {
    return new OutboxEvent(
        id,
        eventId,
        tenantId,
        aggregateType,
        aggregateId,
        topic,
        eventKey,
        payloadJson,
        headersJson,
        status,
        publishAttempts,
        nextAttemptAt,
        lastError,
        createdAt,
        publishedAt);
  }

  /**
   * @return a copy marked delivered to the broker and awaiting consumer acknowledgement.
   */
  public OutboxEvent markPublished() {
    return new OutboxEvent(
        id,
        eventId,
        tenantId,
        aggregateType,
        aggregateId,
        topic,
        eventKey,
        payloadJson,
        headersJson,
        State.SENT,
        publishAttempts,
        nextAttemptAt,
        lastError,
        createdAt,
        OffsetDateTime.now());
  }

  /**
   * Build a fresh, {@code PENDING} event queued for delivery.
   *
   * @param id the row id (also the insert anchor)
   * @param eventId the domain event id, the idempotent publish key and the inbox dedup anchor
   *     (invariant 3)
   * @param eventType fully-qualified event name, e.g. {@code workflow.run.requested.v1}
   * @param topic the versioned Kafka topic the publisher sends the envelope to
   * @param aggregateType the aggregate family, e.g. {@code workflow_run}
   * @param aggregateId the aggregate (run) id the event describes
   * @param tenantId the owning tenant (invariant 1)
   * @param payloadJson the untrusted payload the envelope carries
   * @param headersJson redacted correlation metadata (correlation/causation/traceparent)
   * @return a new {@code PENDING} event, timestamped now with zero attempts
   */
  public static OutboxEvent pending(
      UUID id,
      UUID eventId,
      String eventType,
      String topic,
      String aggregateType,
      UUID aggregateId,
      UUID tenantId,
      String payloadJson,
      String headersJson) {
    return new OutboxEvent(
        id,
        eventId,
        tenantId,
        aggregateType,
        aggregateId,
        topic,
        tenantId + ":" + aggregateId,
        payloadJson,
        headersJson,
        State.PENDING,
        0,
        null,
        null,
        OffsetDateTime.now(),
        null);
  }
}
