package com.example.agentflow.messaging.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The result of a controlled redrive (S12-12): the DLQ record acted on, the original envelope it
 * redrives, the fresh envelope id the redrive re-published, and the operator context.
 *
 * <p>Sentinel values are {@code null} where a step does not apply: {@link #requeuedEventId()} is
 * {@code null} when the redrive was refused (the guard ran before the re-publish); {@link
 * #reason()} / {@link #operator()} are {@code null} only if the caller did not supply them.
 */
public record DlqRedriveResult(
    UUID dlqId,
    UUID originalEventId,
    UUID requeuedEventId,
    String topic,
    String consumerName,
    String reason,
    String operator,
    OffsetDateTime requeuedAt) {

  /**
   * @param dlqId the DLQ record redriven
   * @param originalEventId the original envelope id the DLQ pointed to
   * @param requeuedEventId the new envelope id the redrive re-published (or {@code null} if
   *     refused)
   * @param topic the versioned topic the envelope arrived on
   * @param consumerName the inbox consumer whose processing failed
   * @param reason the operator-supplied reason
   * @param operator the operator subject that performed the redrive
   * @param requeuedAt the instant the redrive re-published (or {@code null} if refused)
   * @return the redrive result
   */
  public static DlqRedriveResult redriven(
      UUID dlqId,
      UUID originalEventId,
      UUID requeuedEventId,
      String topic,
      String consumerName,
      String reason,
      String operator,
      OffsetDateTime requeuedAt) {
    return new DlqRedriveResult(
        dlqId, originalEventId, requeuedEventId, topic, consumerName, reason, operator, requeuedAt);
  }
}
