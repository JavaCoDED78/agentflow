package com.example.agentflow.messaging.application;

import com.example.agentflow.messaging.domain.DlqMessage;
import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

/**
 * The consumer-facing "consumer error handling" boundary (S06-11): it runs a claimed inbox event
 * through the idempotency helper ({@link InboxDedupService}) and, once the business change has
 * failed, records the outcome to the dead-letter store ({@link DlqService}) so a failed message is
 * <b>held for investigation</b> rather than retried unboundedly or silently dropped.
 *
 * <p>It is the consumer counterpart of {@link OutboxPublisherService} (the publisher boundary): a
 * thin orchestration a worker holds and calls, so the whole path &mdash; classify &rarr; bounded
 * immediate retry &rarr; RETRY / DEAD_LETTER &rarr; DLQ record &mdash; is one testable method. It
 * is <b>not</b> a Spring bean &mdash; it is constructed at the worker layer from its dependencies,
 * exactly like {@link InboxDedupService} &mdash; so it adds nothing to the boot path and still
 * works without Kafka (it only needs the {@link InboxDedupService}).
 *
 * <p><b>Ack only after a durable outcome (invariant 3).</b> The returned {@link InboxConsumeResult}
 * is the consumer's decision:
 *
 * <ul>
 *   <li>{@link InboxConsumeResult#PROCESSED} / {@link InboxConsumeResult#OTHER_CLAIMED} &mdash; the
 *       outcome is durable (committed once, or a duplicate was skipped), so the consumer commits
 *       the Kafka offset (acknowledges) and moves on.
 *   <li>{@link InboxConsumeResult#RETRY} / {@link InboxConsumeResult#DEAD_LETTER} &mdash; the
 *       business change did <i>not</i> commit; this helper has already recorded the outcome to the
 *       DLQ, and the consumer keeps the offset uncommitted for investigation rather than re-reading
 *       the same failing message. A permanent / malformed event is never retried, so it cannot
 *       cycle the consumer forever (S06-11 acceptance criterion).
 * </ul>
 */
public class InboxConsumerService {

  /** The consumer-processing-failure counter (S13-12): one increment per failed business change. */
  static final String PROCESSING_FAILURES_METRIC =
      "agentflow.messaging.consumer.processing.failures.total";

  private static final Logger log = LoggerFactory.getLogger(InboxConsumerService.class);

  private final InboxDedupService inboxDedup;
  private final DlqService dlq;
  private final InboxMessageRepository inbox;
  private final MeterRegistry meterRegistry;

  /**
   * @param inboxDedup the idempotency helper (never {@code null}); applies the business change with
   *     its bounded immediate retry and classification (S06-10 / S06-11)
   * @param dlq the dead-letter store, or {@code null} when no database is configured (S01-02); a
   *     {@code null} DLQ skips the record (logged)
   * @param inbox the inbox read path to look up the recorded attempt count / last error for the DLQ
   *     record, or {@code null} when not available (the record is still appended with zero
   *     attempts)
   */
  public InboxConsumerService(
      InboxDedupService inboxDedup, DlqService dlq, InboxMessageRepository inbox) {
    this(inboxDedup, dlq, inbox, null);
  }

  /**
   * @param meterRegistry the registry the S13-12 failure counter registers on; may be {@code null}
   *     (a throwaway registry — no observability surface, S13-11 idiom)
   */
  public InboxConsumerService(
      InboxDedupService inboxDedup,
      DlqService dlq,
      InboxMessageRepository inbox,
      MeterRegistry meterRegistry) {
    Assert.notNull(inboxDedup, "inboxDedup must not be null");
    this.inboxDedup = inboxDedup;
    this.dlq = dlq;
    this.inbox = inbox;
    this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
  }

  /**
   * Apply the business change for the claimed event and, on failure, record the outcome to the DLQ.
   *
   * @param topic the versioned topic the envelope arrived on (for the DLQ record)
   * @param claim the fresh {@link InboxMessage.Status#PENDING} claim to apply
   * @param action the business state change to run atomically with the claim
   * @return {@link InboxConsumeResult} &mdash; {@link InboxConsumeResult#PROCESSED} / {@link
   *     InboxConsumeResult#OTHER_CLAIMED} to acknowledge, {@link InboxConsumeResult#RETRY} / {@link
   *     InboxConsumeResult#DEAD_LETTER} (already DLQ-recorded) to hold for investigation
   */
  public InboxConsumeResult apply(String topic, InboxMessage claim, InboxConsumerAction action) {
    InboxConsumeResult result = inboxDedup.consume(claim.consumerName(), claim, action);
    if (result == InboxConsumeResult.RETRY || result == InboxConsumeResult.DEAD_LETTER) {
      recordFailureMetric(result);
      recordDlq(topic, claim, result);
    }
    return result;
  }

  /**
   * Record the S13-12 consumer-processing-failure counter: one increment per failed business
   * change, tagged with the disposition ({@code retry} / {@code dead_letter}) — no identifier.
   * Metering never fails a consume.
   */
  private void recordFailureMetric(InboxConsumeResult result) {
    try {
      Counter.builder(PROCESSING_FAILURES_METRIC)
          .tag("outcome", result.name().toLowerCase(Locale.ROOT))
          .description(
              "Consumer-side processing failures by disposition (S13-12); bounded outcome label, never an id")
          .register(meterRegistry)
          .increment();
    } catch (RuntimeException e) {
      log.debug("consumer processing-failure metric failed: {}", e.getMessage());
    }
  }

  /**
   * Record a failed outcome to the DLQ (idempotent by {@code (consumer_name, event_id)}). The
   * attempt count and last error are read from the inbox row (the source of truth, invariant 2).
   */
  private void recordDlq(String topic, InboxMessage claim, InboxConsumeResult result) {
    if (dlq == null) {
      log.debug(
          "DLQ not available (no database configured); holding event {} for consumer {} as {} "
              + "without a dead-letter record (S06-11)",
          claim.eventId(),
          claim.consumerName(),
          result);
      return;
    }
    int attempts = 0;
    String lastError = null;
    if (inbox != null) {
      Optional<InboxMessage> row =
          inbox.findByConsumerAndEvent(claim.consumerName(), claim.eventId());
      if (row.isPresent()) {
        InboxMessage r = row.get();
        attempts = r.attempts();
        lastError = r.lastError();
      }
    }
    DlqMessage.Status status =
        result == InboxConsumeResult.RETRY
            ? DlqMessage.Status.RETRY
            : DlqMessage.Status.DEAD_LETTER;
    // The InboxDedupService classifies PERMANENT -> DEAD_LETTER and TRANSIENT -> RETRY, one-to-one,
    // so the DLQ error_class mirrors the disposition recorded here (S12-10).
    DlqMessage.ErrorClass errorClass =
        result == InboxConsumeResult.RETRY
            ? DlqMessage.ErrorClass.TRANSIENT
            : DlqMessage.ErrorClass.PERMANENT;
    dlq.record(
        topic, claim.eventId(), claim.consumerName(), status, errorClass, attempts, lastError);
  }
}
