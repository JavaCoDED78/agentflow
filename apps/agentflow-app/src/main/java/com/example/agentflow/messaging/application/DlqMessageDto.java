package com.example.agentflow.messaging.application;

import com.example.agentflow.messaging.domain.DlqMessage;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The inspection overview of one dead-letter message (S12-11): the structural identity of a {@link
 * DlqMessage} without the free-text failure detail. The list endpoint returns one of these per row;
 * the full record (including the redacted {@code lastError}) is the operator-only, audited detail
 * view ({@link DlqInspectionService#inspectDlqMessage}).
 *
 * <p>The DLQ stores only a pointer to the failed event ({@code eventId}) — never the original
 * message payload inline — so this DTO (and the detail view) never expose a raw payload: the
 * inspection is the investigation view, not a copy of the dead-lettered envelope.
 */
public record DlqMessageDto(
    UUID id,
    UUID eventId,
    String topic,
    String consumerName,
    DlqMessage.Status status,
    DlqMessage.ErrorClass errorClass,
    int attempts,
    OffsetDateTime recordedAt) {

  /**
   * @param message the dead-letter row to surface as an overview
   * @return the overview (metadata only; {@code lastError} is intentionally omitted)
   */
  public static DlqMessageDto from(DlqMessage message) {
    return new DlqMessageDto(
        message.id(),
        message.eventId(),
        message.topic(),
        message.consumerName(),
        message.status(),
        message.errorClass(),
        message.attempts(),
        message.recordedAt());
  }
}
