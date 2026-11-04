package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.messaging.application.CorrelationHeaders;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionDto;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.persistence.IdempotentRunView;
import com.example.agentflow.workflowexecution.persistence.RunView;
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
  private final RunStateMachineService transitionService;

  @Autowired
  public RunService(
      @Autowired(required = false) WorkflowRunRepository runRepository,
      IdempotencyKeyService idempotencyKeyService,
      OutboxService outboxService,
      WorkflowVersionService versionService,
      @Autowired(required = false) RunStateMachineService transitionService) {
    this.runRepository = runRepository;
    this.idempotencyKeyService = idempotencyKeyService;
    this.outboxService = outboxService;
    this.versionService = versionService;
    this.transitionService = transitionService;
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
    // No incoming trace context: the request event still carries its correlation chain id and its
    // (null) causation, only the optional traceparent is absent (S06-12).
    return createRun(tenantId, createdBy, request, payloadFingerprint, null);
  }

  /**
   * Start a new run pinned to the tenant's active workflow version, atomically with its outbox
   * event (S06-05), idempotently under the request {@code idempotencyKey} (S06-06), and tagged with
   * the request's W3C trace parent (S06-12).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param createdBy the actor, e.g. an email (never a token); recorded on the run row
   * @param request the run request ({@code workflowId} plus an optional {@code idempotencyKey})
   * @param payloadFingerprint the lowercase-hex SHA-256 of the request payload, or {@code null}
   *     when the request carried no idempotency key
   * @param traceparent the W3C trace-parent header of the request, or {@code null} when absent;
   *     threaded into the event's correlation headers so tracing survives, never as an
   *     authorization input (invariant 1)
   * @return the created run, with its id and initial {@code QUEUED / attempt 1} state; on an
   *     idempotent replay the original run is returned, never a duplicate
   * @throws org.springframework.web.server.ResponseStatusException {@code 503} if no database,
   *     {@code 404} if the workflow has no active version owned by the tenant, {@code 409} if a
   *     reused {@code idempotencyKey} carries a different payload
   */
  @Transactional
  public RunDto createRun(
      UUID tenantId,
      String createdBy,
      CreateRunRequest request,
      String payloadFingerprint,
      String traceparent) {
    requireRepository(runRepository);
    UUID idempotencyKey = request.idempotencyKey();
    if (idempotencyKey == null) {
      return createAndPin(tenantId, createdBy, request, null, traceparent);
    }
    // Keyed create: the idempotency decision (lookup + replay/mismatch) is owned by
    // IdempotencyKeyService, to which this delegates.
    Optional<IdempotentRunView> existing =
        idempotencyKeyService.findByIdempotencyKey(tenantId, idempotencyKey);
    if (existing.isPresent()) {
      return idempotencyKeyService.answerReplay(existing.get(), payloadFingerprint, idempotencyKey);
    }
    try {
      return createAndPin(tenantId, createdBy, request, payloadFingerprint, traceparent);
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
   * Read a tenant's run status — the {@code GET /runs/{runId}} API (get run, S07-08).
   *
   * <p>Returns the run's persisted status snapshot as a {@link RunDto}: id, the pinned version, the
   * current {@code status}, the execution {@code attempt} and the {@code created_at} time. The run
   * is loaded through the tenant-scoped {@link WorkflowRunRepository#findRun} lookup (invariant 1):
   * a run id of another tenant is indistinguishable from a missing run, so it is a {@code 404},
   * never a leak.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param runId the run to read
   * @return the run status snapshot
   * @throws org.springframework.web.server.ResponseStatusException {@code 503} if no database,
   *     {@code 404} when no run matches {@code (tenant_id, runId)}
   */
  public RunDto getRun(UUID tenantId, UUID runId) {
    requireRepository(runRepository);
    RunView run =
        runRepository
            .findRun(tenantId, runId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no run with id " + runId + " for this tenant"));
    return runDto(run);
  }

  /**
   * Request cancellation of a run (S07-12): move it to the cancellation-in-flight state {@code
   * CANCEL_REQUESTED}, which the worker cooperatively drains — it stops not-yet-started steps,
   * keeps completed ones, and closes the run to {@code CANCELLED} once drained.
   *
   * <p>Cancellation is two-phase and one-way (run-state-machine.md section 3): a run never goes
   * straight to the terminal {@code CANCELLED}. This method performs the first phase only. The
   * second phase — {@code CANCEL_REQUESTED} → {@code CANCELLED} — is the worker's job (invariant 4:
   * the single transition service applies it, fenced by the run's {@code lease_epoch}).
   *
   * <p>Cancel is a no-op once the run is already terminal or already cancelling: it never erases a
   * completed result (invariant 4) and never moves a run out of a terminal state, so a double
   * request is idempotent — it returns the current snapshot without a second event.
   *
   * <p>The {@code tenantId} comes from the verified security context; it is never taken from the
   * request body or headers (invariant 1).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param runId the run to cancel
   * @param actor the cancelling actor recorded on the cancel event; {@code null} for a system actor
   * @return the run status snapshot now reflecting {@code CANCEL_REQUESTED}; the unchanged snapshot
   *     when the run is already terminal or already {@code CANCEL_REQUESTED}
   * @throws org.springframework.web.server.ResponseStatusException {@code 503} if no database,
   *     {@code 404} when no run matches {@code (tenant_id, runId)}, {@code 409} when the run is
   *     already {@code CANCELLED} or {@code CANCEL_REQUESTED}
   */
  @Transactional
  public RunDto requestCancellation(UUID tenantId, UUID runId, String actor) {
    requireRepository(runRepository);
    RunView run =
        runRepository
            .findRun(tenantId, runId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no run with id " + runId + " for this tenant"));
    // Already terminal (SUCCEEDED / FAILED / CANCELLED) or already cancelling: a no-op so a
    // duplicate request never erases a completed result or re-drains an already-draining run.
    if (run.status().isTerminal() || WorkflowRunState.CANCEL_REQUESTED.equals(run.status())) {
      return runDto(run);
    }
    // Phase 1 of two-phase cancel: a single, transactional transition + event append via the
    // transition service (invariant 4). The transition is atomic with the outbox event.
    if (transitionService == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "cancelling a run is unavailable: no database connection is configured");
    }
    transitionService.transitionRun(
        tenantId, runId, WorkflowRunState.CANCEL_REQUESTED, "run.cancel", actor);
    // Re-read to reflect the new state in the returned snapshot (the event already committed
    // with the run in the transition service's transaction).
    RunView cancelled =
        runRepository
            .findRun(tenantId, runId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "cancel did not persist: no run after a transition to CANCEL_REQUESTED"));
    return runDto(cancelled);
  }

  /**
   * Build the API {@link RunDto} from a tenant-scoped run snapshot.
   *
   * @param run the tenant-scoped run snapshot
   * @return the API DTO
   */
  private static RunDto runDto(RunView run) {
    return new RunDto(
        run.runId(), run.workflowVersionId(), run.status().name(), run.attempt(), run.createdAt());
  }

  /**
   * Create the run against the pinned active version and append its outbox event in one transaction
   * (S06-05), storing the idempotency key and payload fingerprint alongside the row.
   */
  private RunDto createAndPin(
      UUID tenantId,
      String createdBy,
      CreateRunRequest request,
      String payloadFingerprint,
      String traceparent) {
    // Pin to the single active (immutable) version of the workflow (invariant 13). A draft or a
    // foreign version is a 404, so a run can never start against anything but the locked contract.
    WorkflowVersionDto version = versionService.getActiveVersion(tenantId, request.workflowId());
    // Stable id across a retry; the run row and the event carry it.
    UUID runId = UUID.randomUUID();
    runRepository.create(
        runId, tenantId, version.id(), request.idempotencyKey(), payloadFingerprint, createdBy);
    // Append the request event in the SAME transaction as the run: neither commits without the
    // other (invariant 3). The payload is redacted correlation metadata, never a secret/prompt. The
    // run is the whole correlation chain for a request event, so correlationId = runId and
    // causationId is null (there is no predecessor event); the request's traceparent is threaded
    // through when present (S06-12). The tenant is scoped through outbox_events.tenant_id (a real
    // FK), never through these correlation headers (invariant 1).
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
            CorrelationHeaders.of(runId.toString(), null, traceparent).toJson());
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
