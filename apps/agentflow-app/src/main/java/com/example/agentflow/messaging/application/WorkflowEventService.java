package com.example.agentflow.messaging.application;

import com.example.agentflow.messaging.domain.WorkflowEvent;
import com.example.agentflow.messaging.persistence.WorkflowEventRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Application service for the append-only run/step transition history (glossary §6, {@code
 * workflow_events}).
 *
 * <p>Owned by the {@code messaging} module; the {@code workflowexecution} module appends a row for
 * every run/step transition through this service (module-map edge 13). It never writes the table
 * itself, which would violate {@code messaging}'s ownership and the module boundary. The append is
 * transactional with the state change: the caller runs inside a single Spring transaction (the
 * transition service, S05-11), so the event row and the state it describe commit together and roll
 * back together — the history can never diverge from the state it records (invariant 3 / {@code
 * ADR-004}). This service has no transaction of its own; it joins the caller's ambient transaction.
 *
 * <p>The bean is always registered by Spring; the {@link WorkflowEventRepository} it depends on is
 * only present with a {@code DataSource}, so the operations fail loudly (not a silent no-op) when
 * no database is configured. The caller already reports a {@code 503} for the missing database
 * before it reaches this path.
 */
@Service
public class WorkflowEventService {

  private final WorkflowEventRepository repository;

  public WorkflowEventService(@Autowired(required = false) WorkflowEventRepository repository) {
    this.repository = repository;
  }

  /**
   * Append one transition fact and return its id.
   *
   * <p>The {@code id} and {@code occurredAt} instant are assigned by {@link
   * WorkflowEvent#appended}; the caller names the scope (tenant, run, optional step) and the fact
   * (type + redacted metadata). The {@code tenantId} comes only from the verified security context,
   * so appending is scoped to the correct tenant (invariant 1).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param runId the run every event is scoped to
   * @param stepRunId the step the event belongs to, or {@code null} for a run-level event
   * @param eventType the transition / trigger name (reason + event type)
   * @param payloadRedacted redacted metadata (prior -&gt; new status, reason, actor)
   * @return the persisted event id
   * @throws IllegalStateException if no database is configured (the caller reports {@code 503}
   *     first)
   */
  public UUID append(
      UUID tenantId, UUID runId, UUID stepRunId, String eventType, String payloadRedacted) {
    if (repository == null) {
      throw new IllegalStateException(
          "workflow events unavailable: no database connection is configured");
    }
    return repository.insert(
        WorkflowEvent.appended(tenantId, runId, stepRunId, eventType, payloadRedacted));
  }

  /**
   * Replay a run's transition history, ordered by {@code occurred_at} (most-recent-first), so the
   * run's state can be reconstructed (S05-09).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param runId the run whose history to replay
   * @return the run's events ordered by {@code occurred_at} (never {@code null})
   * @throws IllegalStateException if no database is configured (the caller reports {@code 503}
   *     first)
   */
  public List<WorkflowEvent> historyByRun(UUID tenantId, UUID runId) {
    if (repository == null) {
      throw new IllegalStateException(
          "workflow events unavailable: no database connection is configured");
    }
    return repository.findByTenantAndRunId(tenantId, runId);
  }
}
