package com.example.agentflow.messaging.application;

import com.example.agentflow.messaging.domain.DlqMessage;
import com.example.agentflow.messaging.persistence.DlqMessageRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Appends a consumer's failed outcome to the {@code dlq_messages} dead-letter store (S06-11), so a
 * {@link InboxConsumeResult#RETRY} / {@link InboxConsumeResult#DEAD_LETTER} disposition is held for
 * investigation instead of being retried unboundedly or silently dropped.
 *
 * <p>The append is idempotent by {@code (consumer_name, event_id)} (the DLQ mirrors the inbox dedup
 * key, at-least-once delivery, ADR-003): a duplicate Kafka delivery that would produce a second
 * dead-letter is collapsed to one record. The repository is optional, so the artifact boots without
 * a database (S01-02): when no database is configured {@link #record} logs and returns, never
 * throwing, so the skeleton still starts.
 *
 * <p>PostgreSQL is the source of truth (invariant 2): the consumer's authoritative disposition
 * stays in the {@code inbox_messages} row (set through the inbox dedup helper); this service only
 * appends the investigation view from it. It writes nothing the inbox dedup helper did not already
 * make durable, and it never runs arbitrary or model-generated code (invariant 7).
 *
 * <p>The bean is constructed at the `worker` layer (like {@code InboxDedupService} and {@code
 * OutboxPublisherService}), so the skeleton still boots without a database.
 */
@Service
public class DlqService {

  private static final Logger log = LoggerFactory.getLogger(DlqService.class);

  private final DlqMessageRepository repository;

  /**
   * @param repository the DLQ write path, or {@code null} when no database is configured (S01-02);
   *     a {@code null} repository makes {@link #record} a no-op log, so the skeleton boots without
   *     Kafka and a database
   */
  @Autowired
  public DlqService(@Autowired(required = false) DlqMessageRepository repository) {
    this.repository = repository;
  }

  /**
   * Append a dead-letter record for a consumer's failed outcome. No-op (logged) when no database is
   * configured.
   *
   * @param topic the versioned topic the envelope arrived on
   * @param eventId the domain event id (the SAME id as the outbox event id / envelope eventId)
   * @param consumerName the inbox consumer identity whose processing failed
   * @param status the consumer error policy disposition ({@link DlqMessage.Status#RETRY} / {@link
   *     DlqMessage.Status#DEAD_LETTER})
   * @param attempts the attempt count after the failed attempts (already the exhausted total)
   * @param lastError the safe, redacted last error (never a secret, full prompt or document)
   * @return {@code true} if a new DLQ record was inserted, {@code false} when the pair already
   *     existed, or {@code false} when no database is configured (no-op)
   */
  public boolean record(
      String topic,
      UUID eventId,
      String consumerName,
      DlqMessage.Status status,
      int attempts,
      String lastError) {
    if (repository == null) {
      // No database configured (S01-02): nothing to hold the dead letter; do not throw.
      log.debug(
          "DLQ not available (no database configured); skipping dead-letter of event {} for "
              + "consumer {} (S06-11)",
          eventId,
          consumerName);
      return false;
    }
    boolean inserted =
        repository.record(
            DlqMessage.deadLetter(eventId, topic, consumerName, status, attempts, lastError));
    log.debug(
        "recorded {} dead-letter of event {} for consumer {} (attempts {})",
        inserted ? "new" : "duplicate",
        eventId,
        consumerName,
        attempts);
    return inserted;
  }
}
