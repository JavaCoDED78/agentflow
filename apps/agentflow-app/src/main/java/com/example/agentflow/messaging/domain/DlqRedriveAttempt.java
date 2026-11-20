package com.example.agentflow.messaging.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An operator-controlled redrive of one dead-lettered message (S12-12), owned by the {@code
 * messaging} module. One row per {@code dlq_messages} record ({@link #dlqId} is the primary key),
 * so the redrive command is <b>idempotent</b>: a second redrive of the same DLQ record is refused
 * rather than creating a second re-published envelope.
 *
 * <p>The redrive re-publishes the <b>original</b> envelope &mdash; its payload lives only in {@code
 * outbox_events} (the DLQ record is a pointer, per the S12-10 decision) &mdash; under a <b>new</b>
 * event id ({@link #requeuedEventId}). The inbox dedup is keyed by {@code (consumer, event)}, so a
 * new event id makes the re-published envelope a fresh claim and the business change re-runs once
 * (never a same-event id re-run, which the composite primary key would block).
 *
 * <p>Values are immutable; the record is written by the messaging {@code DlqRedriveService}.
 */
public record DlqRedriveAttempt(
    UUID dlqId,
    UUID eventId,
    UUID requeuedEventId,
    String reason,
    String operator,
    UUID tenantId,
    OffsetDateTime createdAt) {

  /**
   * Build a redrive attempt as written by the redrive service.
   *
   * @param dlqId the DLQ record being redriven
   * @param eventId the original envelope id being redriven
   * @param requeuedEventId the new envelope id the redrive re-published
   * @param reason the operator-supplied reason
   * @param operator the operator subject that performed the redrive
   * @param tenantId the operator's tenant (operational visibility only; the DLQ is tenant-wide)
   * @param createdAt the instant the attempt was recorded
   * @return a new {@link DlqRedriveAttempt}
   */
  public static DlqRedriveAttempt created(
      UUID dlqId,
      UUID eventId,
      UUID requeuedEventId,
      String reason,
      String operator,
      UUID tenantId,
      OffsetDateTime createdAt) {
    return new DlqRedriveAttempt(
        dlqId, eventId, requeuedEventId, reason, operator, tenantId, createdAt);
  }
}
