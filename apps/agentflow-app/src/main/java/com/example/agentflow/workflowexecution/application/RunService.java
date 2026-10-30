package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionDto;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.persistence.IdempotentRunView;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Create a workflow run (S06-05) with an idempotent create-run contract (S06-06).
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
 * <p>Because both writes run inside one {@link Transactional} method with the default {@code
 * REQUIRED} propagation, the run and its event commit atomically — a run is never committed without
 * its outbox event and an event is never committed without durable state (invariant 3). The run id
 * is generated up front (stable across a retry) and returned only after the commit.
 *
 * <p><b>Idempotent under {@code Idempotency-Key} (S06-06).</b> The idempotency decision is owned by
 * {@link IdempotencyKeyService}, to which {@code createRun} delegates: on a key it asks the service
 * to look the run up by {@code (tenant_id, idempotency_key)} and to answer the replay. The service
 * replays a stored run with the <b>same payload fingerprint</b> (the original {@link RunDto} is
 * returned and neither a second run nor a second event is produced, so a client that retries a lost
 * response succeeds cleanly), rejects a stored run with a <b>different payload fingerprint</b>
 * under the same key with a permanent {@code 409} conflict (a reused key claims the payload too),
 * and answers {@link Optional#empty()} when no run exists yet so {@code createRun} falls through to
 * the normal create. The insert is the sole creation path, so a concurrent duplicate create
 * collides on the {@code UNIQUE (tenant_id, idempotency_key)} constraint; that collision is
 * resolved by asking the service again for the winner (replay on match, {@code 409} on mismatch)
 * instead of surfacing a raw database error.
 *
 * <p>The {@code tenantId} comes from the verified security context; it is never taken from the
 * request body or headers (invariant 1). The payload fingerprint is a SHA-256 of the request
 * payload &mdash; an opaque, tenant-scoped digest, never a secret, a full prompt or a full document
 * (invariant 8). The stored fingerprint and the {@code Idempotency-Key} are never used as an
 * authorization source.
 */
@Service
public class RunService {

  private final WorkflowRunRepository runRepository;
  private final IdempotencyKeyService idempotencyKeyService;
  private final OutboxService outboxService;
  private final WorkflowVersionService versionService;

  @Autowired
  public RunService(
      @Autowired(required = false) WorkflowRunRepository runRepository,
      IdempotencyKeyService idempotencyKeyService,
      OutboxService outboxService,
      WorkflowVersionService versionService) {
    this.runRepository = runRepository;
    this.idempotencyKeyService = idempotencyKeyService;
    this.outboxService = outboxService;
    this.versionService = versionService;
  }

  /**
   * Start a new run pinned to the tenant's active workflow version, atomically with its outbox
   * event (S06-05), idempotently under the request {@code idempotencyKey} (S06-06).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param createdBy the actor, e.g. an email (never a token); recorded on the run row
   * @param request the run request ({@code workflowId} plus an optional {@code idempotencyKey})
   * @param payloadFingerprint the lowercase-hex SHA-256 of the request payload, or {@code null}
   *     when the request carried no idempotency key
   * @return the created run, with its id and initial {@code QUEUED / attempt 1} state; on an
   *     idempotent replay the original run is returned, never a duplicate
   * @throws org.springframework.web.server.ResponseStatusException {@code 503} if no database,
   *     {@code 404} if the workflow has no active version owned by the tenant, {@code 409} if a
   *     reused {@code idempotencyKey} carries a different payload
   */
  @Transactional
  public RunDto createRun(
      UUID tenantId, String createdBy, CreateRunRequest request, String payloadFingerprint) {
    requireRepository(runRepository);
    UUID idempotencyKey = request.idempotencyKey();
    if (idempotencyKey == null) {
      return createAndPin(tenantId, createdBy, request, null);
    }
    // Keyed create: the idempotency decision (lookup + replay/mismatch) is owned by
    // IdempotencyKeyService, to which this delegates.
    Optional<IdempotentRunView> existing =
        idempotencyKeyService.findByIdempotencyKey(tenantId, idempotencyKey);
    if (existing.isPresent()) {
      return idempotencyKeyService.answerReplay(existing.get(), payloadFingerprint, idempotencyKey);
    }
    try {
      return createAndPin(tenantId, createdBy, request, payloadFingerprint);
    } catch (DataIntegrityViolationException duplicate) {
      // A concurrent keyed create already committed the row: resolve the winner instead of
      // surfacing the raw constraint error (at-least-once, never a duplicate run).
      Optional<IdempotentRunView> concurrent =
          idempotencyKeyService.findByIdempotencyKey(tenantId, idempotencyKey);
      if (concurrent.isPresent()) {
        return idempotencyKeyService.answerReplay(
            concurrent.get(), payloadFingerprint, idempotencyKey);
      }
      throw duplicate;
    }
  }

  /**
   * Create the run against the pinned active version and append its outbox event in one transaction
   * (S06-05), storing the idempotency key and payload fingerprint alongside the row.
   */
  private RunDto createAndPin(
      UUID tenantId, String createdBy, CreateRunRequest request, String payloadFingerprint) {
    // Pin to the single active (immutable) version of the workflow (invariant 13). A draft or a
    // foreign version is a 404, so a run can never start against anything but the locked contract.
    WorkflowVersionDto version = versionService.getActiveVersion(tenantId, request.workflowId());
    // Stable id across a retry; the run row and the event carry it.
    UUID runId = UUID.randomUUID();
    runRepository.create(
        runId, tenantId, version.id(), request.idempotencyKey(), payloadFingerprint, createdBy);
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
