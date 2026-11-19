package com.example.agentflow.messaging.application;

import com.example.agentflow.messaging.domain.DlqMessage;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The full inspection view of one dead-letter message (S12-11): the overview {@link DlqMessageDto}
 * plus the safe, redacted {@code lastError} text. Returned by the operator-only, audited detail
 * endpoint ({@link DlqInspectionService#inspectDlqMessage}); the list endpoint never carries it
 * (the overview withholds the failure detail — the operator drills in for it, which is what is
 * audited).
 *
 * <p>{@code lastError} is already redacted through {@link
 * com.example.agentflow.common.SecretRedactor} at write time (S12-09): a secret, a full prompt or a
 * full document never reaches the durable DLQ row, so it never reaches this view either.
 */
public record DlqMessageDetailDto(
    UUID id,
    UUID eventId,
    String topic,
    String consumerName,
    DlqMessage.Status status,
    DlqMessage.ErrorClass errorClass,
    int attempts,
    String lastError,
    OffsetDateTime recordedAt) {

  /**
   * @param message the dead-letter row to surface in full
   * @return the full inspection view (metadata plus the redacted failure detail)
   */
  public static DlqMessageDetailDto from(DlqMessage message) {
    return new DlqMessageDetailDto(
        message.id(),
        message.eventId(),
        message.topic(),
        message.consumerName(),
        message.status(),
        message.errorClass(),
        message.attempts(),
        message.lastError(),
        message.recordedAt());
  }
}
