package com.example.agentflow.messaging.application;

import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Outbox boundary for the {@code messaging} module (S06-05): the only application-facing way a
 * domain module (currently {@code workflowexecution}) appends a queued event to the transactional
 * outbox.
 *
 * <p>{@link #enqueue} is {@link Transactional} with the default {@code REQUIRED} propagation, so it
 * runs inside the caller's transaction and commits atomically with it &mdash; a run and its {@code
 * workflow.run.requested.v1} event therefore commit together or not at all (S06-05 acceptance). It
 * fails loudly with {@code 503} when no database is configured, exactly as {@link
 * com.example.agentflow.workflowexecution.application.RunStateMachineService} does for its history,
 * rather than silently dropping the event (S01-02, invariant 3).
 */
@Service
public class OutboxService {

  private final OutboxEventRepository outboxEventRepository;

  /**
   * @param outboxEventRepository the outbox appender, or {@code null} when no database is
   *     configured (S01-02)
   */
  public OutboxService(@Autowired(required = false) OutboxEventRepository outboxEventRepository) {
    this.outboxEventRepository = outboxEventRepository;
  }

  /**
   * Append one {@code PENDING} event to the outbox. The insert runs in the caller's transaction and
   * only commits once the caller's business change commits, so the event is durable for as long as
   * the run row is (invariant 3).
   *
   * @param event the event to queue for delivery
   * @throws ResponseStatusException {@code 503} when no database connection is configured (S01-02)
   */
  @Transactional
  public void enqueue(OutboxEvent event) {
    requireRepository(outboxEventRepository);
    outboxEventRepository.insert(threadTraceContext(event));
  }

  /**
   * Thread the active span into the event's {@code headersJson} when the producer did not supply
   * one (S13-10).
   *
   * <p>The HTTP create-run path threads the request traceparent explicitly ({@code RunService});
   * every other enqueue (the ingestion worker's {@code indexed} event, any event queued from a
   * traced request without an explicit parent) would otherwise strand the publish/consume legs of
   * the trace as a fresh root. Threading the current span's W3C traceparent lets the publish span
   * ({@code KafkaMessageDeliverer#deliver}) chain onto the request &rarr; outbox &rarr; worker
   * trace. The caller's own traceparent always wins; correlation/causation ids are preserved
   * verbatim; a corrupt producer payload is left untouched (the deliverer warns and drops it, never
   * the silent replacement).
   *
   * @param event the event to queue
   * @return the event with the current traceparent folded into its headers, or the event unchanged
   */
  private static OutboxEvent threadTraceContext(OutboxEvent event) {
    String json = event.headersJson();
    Optional<CorrelationHeaders> parsed = CorrelationHeaders.parse(json);
    if (parsed.isEmpty() && json != null && !json.isBlank()) {
      return event; // corrupt producer payload — keep it; the deliverer warns and drops it
    }
    CorrelationHeaders headers = parsed.orElse(CorrelationHeaders.of(null, null, null));
    if (headers.traceparent() != null && !headers.traceparent().isBlank()) {
      return event; // the caller already threaded a trace context
    }
    String current = MessagingTracing.currentTraceparent();
    if (current == null) {
      return event; // no active span (background / non-traced context)
    }
    return event.withHeadersJson(
        CorrelationHeaders.of(headers.correlationId(), headers.causationId(), current).toJson());
  }

  /**
   * @param repository the outbox appender, or {@code null} when no database is configured
   * @return the repository, or a {@code 503} when no database connection is configured (S01-02)
   */
  private <T> T requireRepository(T repository) {
    if (repository == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "outbox enqueue is unavailable: no database connection is configured");
    }
    return repository;
  }
}
