package com.example.agentflow.messaging.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only run/step transition fact (glossary §6, {@code workflow_events}).
 *
 * <p>One row per transition appended when a run or step changes state. The record ties the fact to
 * the {@code tenantId} it belongs to and the {@code runId} (and optionally the {@code stepRunId})
 * it describes; {@code eventType} names the transition / trigger and {@code payloadRedacted}
 * carries the redacted free-text metadata (prior -&gt; new status, reason, actor) without any
 * secret, full prompt or full document (invariant 8). The {@code id} and {@code occurredAt} instant
 * are assigned by {@link #appended}; {@code stepRunId} stays {@code null} for a run-level event and
 * is set only for a step-level event.
 *
 * <p>Owned by the {@code messaging} module; {@link
 * com.example.agentflow.messaging.application.WorkflowEventService} is the single appender and
 * reader, and the repository maps this record to one {@code workflow_events} row. It is a public
 * value type: the {@code workflowexecution} module appends through the service (module-map edge 13)
 * and crosses the module boundary, so it lives in the public {@code domain} package.
 */
public record WorkflowEvent(
    UUID id,
    UUID tenantId,
    UUID runId,
    UUID stepRunId,
    String eventType,
    String payloadRedacted,
    Instant occurredAt) {

  /**
   * Build an append-only transition fact. The {@code id} and {@code occurredAt} instant are
   * assigned here; the caller names the scope (tenant, run, optional step) and the fact (type +
   * redacted metadata).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param runId the run every event is scoped to
   * @param stepRunId the step the event belongs to, or {@code null} for a run-level event
   * @param eventType the transition / trigger name (reason + event type)
   * @param payloadRedacted redacted metadata (prior -&gt; new status, reason, actor), never a
   *     secret
   * @return the transition fact ready to persist
   */
  public static WorkflowEvent appended(
      UUID tenantId, UUID runId, UUID stepRunId, String eventType, String payloadRedacted) {
    return new WorkflowEvent(
        UUID.randomUUID(), tenantId, runId, stepRunId, eventType, payloadRedacted, Instant.now());
  }
}
