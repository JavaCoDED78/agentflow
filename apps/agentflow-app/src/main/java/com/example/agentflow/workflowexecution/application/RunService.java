package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionDto;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Create a workflow run (S06-05): the application use case behind starting execution.
 *
 * <p>The single entry point to create a run. Each call:
 *
 * <ol>
 *   <li>requires a database ({@code 503} if none, S01-02);
 *   <li>pins the run to the tenant's single <b>active</b> workflow version through {@link
 *       WorkflowVersionService#getActiveVersion} ({@code 404} if none — a run is never created
 *       against a draft or a foreign version, invariant 13);
 *   <li>inserts the run in status {@code QUEUED} with {@code attempt = 1} through {@link
 *       WorkflowRunRepository#create}; and
 *   <li>appends the {@code workflow.run.requested.v1} event to the transactional outbox through
 *       {@link OutboxService#enqueue(OutboxEvent)}.
 * </ol>
 *
 * Because both writes run inside one {@link Transactional} method with the default {@code REQUIRED}
 * propagation, the run and its event commit atomically — a run is never committed without its
 * outbox event and an event is never committed without durable state (invariant 3, the S06-05
 * acceptance criterion). The run id is generated up front (stable across a retry) and returned only
 * after the commit.
 *
 * <p>No idempotency key yet (S06-06). The tenant id comes from the verified security context; it is
 * never taken from the request body or headers (invariant 1). The payload and headers handed to the
 * envelope are redacted correlation metadata — untrusted, never a secret or a full prompt
 * (invariant 8).
 */
@Service
public class RunService {

  private final WorkflowRunRepository runRepository;
  private final OutboxService outboxService;
  private final WorkflowVersionService versionService;

  @Autowired
  public RunService(
      @Autowired(required = false) WorkflowRunRepository runRepository,
      OutboxService outboxService,
      WorkflowVersionService versionService) {
    this.runRepository = runRepository;
    this.outboxService = outboxService;
    this.versionService = versionService;
  }

  /**
   * Start a new run pinned to the tenant's active workflow version, atomically with its outbox
   * event (S06-05).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param workflowId the workflow (definition) the run belongs to
   * @param createdBy the actor, e.g. an email (never a token)
   * @return the created run, with its id and initial {@code QUEUED / attempt 1} state; the id is
   *     returned only after the run row and the outbox event have both committed
   * @throws org.springframework.web.server.ResponseStatusException {@code 503} if no database,
   *     {@code 404} if the workflow has no active version owned by the tenant
   */
  @Transactional
  public RunDto createRun(UUID tenantId, UUID workflowId, String createdBy) {
    requireRepository(runRepository);
    // Pin to the single active (immutable) version of the workflow (invariant 13). A draft or a
    // foreign version is a 404, so a run can never start against anything but the locked contract.
    WorkflowVersionDto version = versionService.getActiveVersion(tenantId, workflowId);
    // Stable id across a retry; the run row and the event carry it.
    UUID runId = UUID.randomUUID();
    runRepository.create(runId, tenantId, version.id());
    // Append the request event in the SAME transaction as the run: neither commits without the
    // other (invariant 3). The payload is redacted correlation metadata, never a secret/prompt.
    OutboxEvent event =
        OutboxEvent.pending(
            runId,
            runId,
            "workflow.run.requested.v1",
            "agentflow.workflow.run-requested.v1",
            "workflow_run",
            runId,
            tenantId,
            payloadJson(runId, version.id()),
            null);
    outboxService.enqueue(event);
    return new RunDto(runId, version.id(), WorkflowRunState.QUEUED.name(), 1, OffsetDateTime.now());
  }

  /**
   * @param repository the run repository, or {@code null} when no database is configured
   * @return the repository, or a {@code 503} when no database connection is configured (S01-02)
   */
  private <T> T requireRepository(T repository) {
    if (repository == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "creating a workflow run is unavailable: no database connection is configured");
    }
    return repository;
  }

  /**
   * The envelope payload for a run request: redacted correlation metadata, never a secret, a full
   * prompt or a full document (invariant 8). The values are {@code UUID} ids only, so a plain
   * string build is valid JSON and needs no serializer.
   *
   * @param runId the run id the event describes
   * @param versionId the pinned active version the run executes
   * @return the JSON envelope payload
   */
  private static String payloadJson(UUID runId, UUID versionId) {
    return "{\"runId\":\"" + runId + "\",\"workflowVersionId\":\"" + versionId + "\"}";
  }
}
