package com.example.agentflow.messaging.application;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Polling outbox publisher (S06-07): drains {@code outbox_events} to the broker in bounded batches
 * without ever holding a database transaction across a network call.
 *
 * <p>One poll ({@link #pollOnce}) does four isolated steps:
 *
 * <ol>
 *   <li><b>Select</b> a bounded batch of due rows ({@code PENDING}, plus a {@code FAILED} row whose
 *       retry instant has arrived) oldest-first through {@link
 *       OutboxEventRepository#selectForClaim(int)};
 *   <li><b>Claim</b> them atomically with {@link OutboxEventRepository#claim(List)} &mdash; the
 *       {@code WHERE status = 'PENDING'} conditional update moves each row to {@code CLAIMED} and,
 *       because two publishers race on the same row and only the first transition matches, a row is
 *       claimed by exactly one publisher (S06-07 acceptance: two publishers never claim the same
 *       row and never lose one);
 *   <li><b>Publish</b> each claimed row through the {@link MessageDeliverer} port, OUTSIDE any
 *       database transaction, so a slow or hung broker never holds a row lock or a connection open;
 *   <li><b>Record</b> the outcome: {@code SENT} on delivery ({@link
 *       OutboxEventRepository#markSent}), or {@code FAILED} with a bounded-retry {@code
 *       next_attempt_at} on a transient fault and terminal on exhaustion / a permanent fault.
 * </ol>
 *
 * <p>The publisher holds no transaction of its own: the claim, the publish and the outcome update
 * each autocommit independently, so the durable outbox row and the broker delivery stay decoupled
 * (invariant 3). Retry is bounded (configurable batch size, max attempts and exponential backoff
 * capped at a max delay) so a stuck broker drains slowly instead of forever, and a {@code FAILED}
 * row that is terminal stays visible for the DLQ ({@code S06-11}) rather than being dropped.
 *
 * <p>The {@link MessageDeliverer} is optional (the Kafka producer is wired in {@code S06-09}); with
 * no deliverer the poll is a no-op and the rows stay {@code PENDING}, never lost. The repository is
 * also optional so the artifact boots without a database (S01-02); when a deliverer is present but
 * no database is configured the poll fails loudly with {@code 503}, mirroring the write path.
 */
@Service
public class OutboxPublisherService {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

  private final OutboxEventRepository repository;
  private final MessageDeliverer deliverer;
  private final Clock clock;
  private final int batchSize;
  private final int maxAttempts;
  private final long baseDelaySeconds;
  private final long maxDelaySeconds;

  /**
   * @param repository the outbox read/claim/write path, or {@code null} when no database is
   *     configured (S01-02)
   * @param deliverer the broker delivery adapter, or {@code null} until the Kafka producer is wired
   *     (S06-09)
   * @param clock the injectable clock for bounded-retry instants (invariant 10), or {@code
   *     Clock.systemUTC()} when none is available
   * @param properties the validated publisher tuning (batch size, retry bound, backoff)
   */
  @Autowired
  public OutboxPublisherService(
      @Autowired(required = false) OutboxEventRepository repository,
      @Autowired(required = false) MessageDeliverer deliverer,
      @Autowired(required = false) Clock clock,
      AgentFlowProperties properties) {
    this.repository = repository;
    this.deliverer = deliverer;
    this.clock = clock != null ? clock : Clock.systemUTC();
    this.batchSize = properties.getOutbox().getPublisher().getBatchSize();
    this.maxAttempts = properties.getOutbox().getPublisher().getMaxAttempts();
    this.baseDelaySeconds = properties.getOutbox().getPublisher().getBaseDelaySeconds();
    this.maxDelaySeconds = properties.getOutbox().getPublisher().getMaxDelaySeconds();
  }

  /**
   * Run one poll: claim a bounded batch of due rows, publish each outside a transaction, and record
   * the outcome. Idempotent per row because the claim is fenced by {@code status = 'PENDING'}.
   *
   * @return the number of rows delivered in this poll
   * @throws ResponseStatusException {@code 503} when a deliverer is present but no database is
   *     configured (inconsistent role configuration; the write path fails the same way, S01-02)
   */
  public int pollOnce() {
    if (deliverer == null) {
      // No broker adapter yet (S06-09): skip the poll. Rows stay PENDING and are not lost.
      log.info(
          "outbox publisher poll skipped: no deliverer wired (Kafka producer is wired in S06-09)");
      return 0;
    }
    if (repository == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "outbox publisher is unavailable: no database connection is configured");
    }

    List<OutboxEvent> candidates = repository.selectForClaim(batchSize);
    if (candidates.isEmpty()) {
      return 0;
    }
    List<OutboxEvent> claimed = repository.claim(candidates.stream().map(OutboxEvent::id).toList());
    if (claimed.isEmpty()) {
      // Every candidate was claimed by a concurrent publisher since the read; nothing to publish.
      return 0;
    }

    int published = 0;
    for (OutboxEvent event : claimed) {
      switch (deliverer.deliver(event)) {
        case DELIVERED -> repository.markSent(event.id());
        case TRANSIENT_FAILURE -> handleTransientFailure(event);
        case PERMANENT_FAILURE -> handlePermanentFailure(event);
      }
      published++;
    }
    log.info(
        "outbox publisher poll: selected {} row(s), claimed {} (published {})",
        candidates.size(),
        claimed.size(),
        published);
    return published;
  }

  /**
   * Record a transient (retryable) broker failure. The attempt count is already bumped by the
   * claim; within the bound the row is scheduled for a bounded-backoff retry, otherwise it is held
   * terminal for the DLQ ({@code S06-11}).
   */
  private void handleTransientFailure(OutboxEvent event) {
    int attempts = event.publishAttempts();
    String reason = "publish transiently failed after " + attempts + " attempt(s)";
    if (attempts >= maxAttempts) {
      repository.markFailed(event.id(), attempts, null, reason);
      log.warn(
          "outbox event {} exhausted {} attempts; held terminal for DLQ (S06-11): {}",
          event.eventId(),
          maxAttempts,
          reason);
      return;
    }
    OffsetDateTime next = nextAttemptAt(attempts);
    repository.markFailed(event.id(), attempts, next, reason);
    log.warn(
        "outbox event {} transient publish failure; retry at {} (attempt {})",
        event.eventId(),
        next,
        attempts);
  }

  /**
   * Record a permanent (non-retryable) broker failure: hold the row terminal for the DLQ ({@code
   * S06-11}) instead of retrying forever.
   */
  private void handlePermanentFailure(OutboxEvent event) {
    int attempts = event.publishAttempts();
    String reason = "publish failed permanently after " + attempts + " attempt(s)";
    repository.markFailed(event.id(), attempts, null, reason);
    log.warn(
        "outbox event {} permanent publish failure; held terminal for DLQ (S06-11): {}",
        event.eventId(),
        reason);
  }

  /**
   * Exponential backoff instant for the given attempt number, capped at the configured max delay.
   *
   * @param attempts the current attempt number (>= 1)
   * @return the earliest instant the next retry may run
   */
  private OffsetDateTime nextAttemptAt(int attempts) {
    long delay = Math.min(baseDelaySeconds * (1L << (attempts - 1)), maxDelaySeconds);
    return clock.instant().plusSeconds(delay).atOffset(ZoneOffset.UTC);
  }
}
