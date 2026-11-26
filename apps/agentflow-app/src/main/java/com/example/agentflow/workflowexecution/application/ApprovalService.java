package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.messaging.application.CorrelationHeaders;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import com.example.agentflow.workflowexecution.domain.ApprovalPayloadFingerprint;
import com.example.agentflow.workflowexecution.domain.ApprovalStatus;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.ApprovalRepository;
import com.example.agentflow.workflowexecution.persistence.ApprovalRow;
import com.example.agentflow.workflowexecution.persistence.OverdueApprovalRow;
import com.example.agentflow.workflowexecution.persistence.RunView;
import com.example.agentflow.workflowexecution.persistence.StepStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The human-approval decision service (S11-04 approve, S11-05 reject + expiry) — the public entry
 * point named in {@code docs/architecture/module-api.md}: the decision half of the approval
 * lifecycle, scoped {@code run:approve}.
 *
 * <p>{@link #approve} and {@link #reject} are {@link Transactional}: the conditional {@code
 * approvals} decision write, the {@link AuditService} append, the run/step completion and the
 * outbox event commit together or roll back together, so an audit record can never diverge from the
 * decision it describes (invariant 3, the plan's "записывать decision/audit атомарно") and a
 * decided run is never left without its continuation/refusal event ("продолжение/отказ публиковать
 * только через outbox"). The audit write crosses the {@code workflowexecution -> audit} compile
 * edge recorded as {@code ADR-018}; the outbox write reuses the existing {@code workflowexecution
 * -> messaging} edge ({@code ADR-004}).
 *
 * <p>The decision is guarded in depth, the S11-04 acceptance ("unauthorized user / another tenant
 * cannot approve a step"):
 *
 * <ol>
 *   <li><b>capability</b> — the endpoint is gated on the {@code run:approve} authority by the
 *       access matrix (S04-08), enforced before the request reaches this service;
 *   <li><b>tenant</b> — every lookup is scoped {@code (tenant_id, run_id, id)}; another tenant's
 *       approval is indistinguishable from a missing one and renders {@code 404} (invariant 1);
 *   <li><b>status</b> — only a {@code PENDING} approval is decidable; the conditional update is
 *       guarded on {@code status = 'PENDING'} and the optimistic-lock {@code version}, so two
 *       competing decisions never overwrite each other (invariant 4);
 *   <li><b>expiry</b> — a decision after {@code expiry} is refused with {@code 409}; the expiry
 *       sweep ({@code worker.ApprovalExpiryScheduler}) owns the {@code EXPIRED} lifecycle and
 *       applies the same refusal a human reject would;
 *   <li><b>step</b> — the gated step must still be {@code WAITING_FOR_APPROVAL}: a step that moved
 *       on (cancelled, …) makes the decision a {@code 409}, never a silent decision on a stale
 *       request;
 *   <li><b>payload</b> (S11-06, approve only) — the stored {@code payload_fingerprint} must still
 *       bind to the exact payload of the run's pinned graph: the decision is the moment the
 *       approved argument set is authorized to execute, so a drifted or unverifiable binding is a
 *       {@code 409} and writes nothing — an approved set of arguments can never execute a different
 *       one.
 * </ol>
 *
 * <p><b>Idempotency.</b> Two mechanisms, both answering a repeat with the recorded decision instead
 * of a second write: by state (a repeat decision on an already-decided approval replays the
 * recorded one) and by key (an {@code Idempotency-Key} header stored with the decision under the
 * {@code approvals_tenant_idempotency_key} guard, {@code V3_3_23}; the stored fingerprint proves a
 * replay, a payload change on a reused key is a {@code 409}). A keyed repeat is answered before the
 * targeted approval is even read, so a retried decision never depends on which request won the
 * race.
 *
 * <p><b>The lifecycle completion</b> (S11-05) is the second half of each decision, applied in the
 * same transaction through the single transition service (invariant 4):
 *
 * <ul>
 *   <li><b>approve</b> resumes the run ({@code WAITING_FOR_APPROVAL -> RUNNING}, {@code
 *       run.approved}): the gated step moves back to {@code RUNNING} and immediately {@code
 *       SUCCEEDED} — the human decision <em>is</em> the gate step's execution, so the step can
 *       never re-pause (a second {@code PENDING} approval would otherwise be creatable on the same
 *       step) — and the continuation is published as {@code workflow.run.resumed.v1} through the
 *       transactional outbox;
 *   <li><b>reject / expiry</b> refuses the side effect ({@code WAITING_FOR_APPROVAL -> FAILED},
 *       {@code run.rejected}): the gated step moves to {@code FAILED} without ever reaching a
 *       handler, the run to {@code FAILED}, and the refusal is published as {@code
 *       workflow.run.rejected.v1} — the acceptance "a rejected/expired request never launches a
 *       side effect".
 * </ul>
 *
 * <p>{@link #expireOverdue} is the expiry sweep's entry point: the guarded {@code EXPIRED} write
 * (no human actor — only {@code decision_at} marks the observed deadline), the {@code EXPIRE} audit
 * row under the system actor, and the same refusal completion a human reject applies.
 *
 * <p>When no database is configured the repositories are {@link Autowired optional} and the service
 * reports {@code 503} instead of silently doing nothing (S01-02).
 */
@Service
public class ApprovalService {

  /**
   * The system actor recorded on the audit trail of an expiry — never a human, never a token. Since
   * S07-18 this is the one stable service principal subject the worker runs under ({@code
   * agentflow-worker}), the same identity the worker loop itself uses for every transition and
   * audit record it writes; the per-scheduler actor was dropped to keep the attribution uniform and
   * attributable to the platform service.
   */
  static final String EXPIRY_ACTOR = ServiceIdentity.WORKER_SERVICE_ACTOR;

  private final ApprovalRepository approvalRepository;
  private final WorkflowStepRunRepository stepRepository;
  private final WorkflowRunRepository runRepository;
  private final WorkflowVersionService versionService;
  private final AuditService auditService;
  private final RunStateMachineService transitionService;
  private final OutboxService outboxService;
  private final Clock clock;

  public ApprovalService(
      @Autowired(required = false) ApprovalRepository approvalRepository,
      @Autowired(required = false) WorkflowStepRunRepository stepRepository,
      @Autowired(required = false) WorkflowRunRepository runRepository,
      @Autowired(required = false) WorkflowVersionService versionService,
      AuditService auditService,
      RunStateMachineService transitionService,
      OutboxService outboxService,
      @Autowired(required = false) Clock clock) {
    this.approvalRepository = approvalRepository;
    this.stepRepository = stepRepository;
    this.runRepository = runRepository;
    this.versionService = versionService;
    this.auditService = auditService;
    this.transitionService = transitionService;
    this.outboxService = outboxService;
    this.clock = clock != null ? clock : Clock.systemUTC();
  }

  /**
   * Record an {@code APPROVED} decision on a {@code PENDING} approval and resume the run,
   * idempotently.
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param actor the deciding actor (never a token)
   * @param runId the run named in the endpoint path
   * @param approvalId the approval record named in the endpoint path
   * @param comment the free-text decision note, or {@code null} (never a secret — the caller
   *     redacts; invariant 8)
   * @param idempotencyKey the client {@code Idempotency-Key}, or {@code null} for an unkeyed
   *     decision
   * @param requestFingerprint the lowercase-hex SHA-256 of the canonical {@code {approvalId,
   *     decision, comment}} payload, or {@code null} when unkeyed
   * @return the persisted decision snapshot; on a repeat (state- or key-based) the recorded
   *     decision is replayed
   * @throws ResponseStatusException {@code 503} when no database is configured, {@code 404} when no
   *     approval matches {@code (tenantId, runId, approvalId)} (or the gated step is gone), {@code
   *     409} when the approval is already decided otherwise, expired, its step moved on, its stored
   *     payload fingerprint no longer binds to the run's pinned payload (S11-06), or a reused key
   *     carries a different payload
   */
  @Transactional
  public ApprovalDecisionDto approve(
      UUID tenantId,
      String actor,
      UUID runId,
      UUID approvalId,
      String comment,
      UUID idempotencyKey,
      String requestFingerprint) {
    requireRepository(approvalRepository);
    requireRepository(stepRepository);

    if (idempotencyKey != null) {
      Optional<ApprovalRow> keyed =
          approvalRepository.findByIdempotencyKey(tenantId, idempotencyKey);
      if (keyed.isPresent()) {
        return answerKeyedReplay(keyed.get(), requestFingerprint, idempotencyKey);
      }
    }

    ApprovalRow approval = findApproval(tenantId, runId, approvalId);

    if (approval.status().equals(ApprovalStatus.APPROVED.name())) {
      // State-based idempotent replay: the decision already exists, so a repeat (unkeyed, or a
      // race lost to a concurrent approver) answers with the recorded fact and writes nothing.
      return toDto(approval);
    }
    if (!approval.status().equals(ApprovalStatus.PENDING.name())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "approval "
              + approvalId
              + " is already "
              + approval.status()
              + " and cannot be approved");
    }
    OffsetDateTime now = OffsetDateTime.now(clock);
    assertNotExpired(approval, now);
    assertStepStillWaiting(tenantId, runId, approval);
    // S11-06 — the payload binding: an approve authorizes the gated side effect, so the stored
    // fingerprint must still bind to the exact payload the run would execute. A mismatch (or an
    // unverifiable pinned graph) refuses the decision — the approved argument set can never differ
    // from the executed one — and nothing is written.
    assertPayloadUnchanged(tenantId, runId, approval);

    try {
      approvalRepository.recordApprove(
          tenantId,
          runId,
          approvalId,
          actor,
          now,
          comment,
          idempotencyKey,
          requestFingerprint,
          approval.version());
    } catch (DuplicateKeyException e) {
      // A concurrent decision committed the same key on this (or another) approval first: the
      // unique guard turned the race into an answerable fact — reply from the keyed row.
      ApprovalRow keyed =
          approvalRepository
              .findByIdempotencyKey(tenantId, idempotencyKey)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.CONFLICT,
                          "idempotency key " + idempotencyKey + " raced a concurrent decision"));
      return answerKeyedReplay(keyed, requestFingerprint, idempotencyKey);
    } catch (ConflictException e) {
      // The conditional update matched no row: another decision won the race, or the row moved.
      // Re-read: if the winner is the same decision, the repeat replays it; otherwise the
      // conflict is permanent.
      ApprovalRow current = findApproval(tenantId, runId, approvalId);
      if (current.status().equals(ApprovalStatus.APPROVED.name())) {
        return toDto(current);
      }
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }

    auditService.record(
        tenantId,
        actor,
        AuditAction.APPROVE,
        AuditResourceType.APPROVAL,
        approvalId,
        "step=" + approval.stepKey());

    resumeAfterApproval(tenantId, runId, approval.stepKey(), approvalId, actor);

    // Re-read the decided row so the response reflects exactly what committed (including the
    // server-assigned decision instant), not the values this thread computed.
    return toDto(findApproval(tenantId, runId, approvalId));
  }

  /**
   * Record a {@code REJECTED} decision on a {@code PENDING} approval and refuse the gated side
   * effect, idempotently — the S11-05 reject half of the lifecycle, mirroring {@link #approve}
   * guard for guard: tenant, status, expiry, step, the conditional write, both replay mechanisms
   * and the atomic audit append. The completion differs by design: the run and the gated step move
   * to {@code FAILED} (never to {@code RUNNING}), the side effect never executes, and the refusal —
   * not a continuation — is published through the outbox.
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param actor the deciding actor (never a token)
   * @param runId the run named in the endpoint path
   * @param approvalId the approval record named in the endpoint path
   * @param comment the free-text decision note, or {@code null} (never a secret)
   * @param idempotencyKey the client {@code Idempotency-Key}, or {@code null} for an unkeyed
   *     decision
   * @param requestFingerprint the lowercase-hex SHA-256 of the canonical {@code {approvalId,
   *     decision, comment}} payload, or {@code null} when unkeyed
   * @return the persisted decision snapshot; on a repeat (state- or key-based) the recorded
   *     decision is replayed
   * @throws ResponseStatusException {@code 503} when no database is configured, {@code 404} when no
   *     approval matches {@code (tenantId, runId, approvalId)} (or the gated step is gone), {@code
   *     409} when the approval is already decided otherwise, expired, its step moved on, or a
   *     reused key carries a different payload
   */
  @Transactional
  public ApprovalDecisionDto reject(
      UUID tenantId,
      String actor,
      UUID runId,
      UUID approvalId,
      String comment,
      UUID idempotencyKey,
      String requestFingerprint) {
    requireRepository(approvalRepository);
    requireRepository(stepRepository);

    if (idempotencyKey != null) {
      Optional<ApprovalRow> keyed =
          approvalRepository.findByIdempotencyKey(tenantId, idempotencyKey);
      if (keyed.isPresent()) {
        return answerKeyedReplay(keyed.get(), requestFingerprint, idempotencyKey);
      }
    }

    ApprovalRow approval = findApproval(tenantId, runId, approvalId);

    if (approval.status().equals(ApprovalStatus.REJECTED.name())) {
      // State-based idempotent replay, the reject counterpart of the approve replay.
      return toDto(approval);
    }
    if (!approval.status().equals(ApprovalStatus.PENDING.name())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "approval "
              + approvalId
              + " is already "
              + approval.status()
              + " and cannot be rejected");
    }
    OffsetDateTime now = OffsetDateTime.now(clock);
    assertNotExpired(approval, now);
    assertStepStillWaiting(tenantId, runId, approval);

    try {
      approvalRepository.recordReject(
          tenantId,
          runId,
          approvalId,
          actor,
          now,
          comment,
          idempotencyKey,
          requestFingerprint,
          approval.version());
    } catch (DuplicateKeyException e) {
      ApprovalRow keyed =
          approvalRepository
              .findByIdempotencyKey(tenantId, idempotencyKey)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.CONFLICT,
                          "idempotency key " + idempotencyKey + " raced a concurrent decision"));
      return answerKeyedReplay(keyed, requestFingerprint, idempotencyKey);
    } catch (ConflictException e) {
      ApprovalRow current = findApproval(tenantId, runId, approvalId);
      if (current.status().equals(ApprovalStatus.REJECTED.name())) {
        return toDto(current);
      }
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }

    auditService.record(
        tenantId,
        actor,
        AuditAction.REJECT,
        AuditResourceType.APPROVAL,
        approvalId,
        "step=" + approval.stepKey());

    refuseSideEffect(tenantId, runId, approval.stepKey(), approvalId, actor, "REJECTED");

    return toDto(findApproval(tenantId, runId, approvalId));
  }

  /**
   * The redacted audit metadata for an expiry: the gated step (and, when the run carries an actor,
   * the run's requester — the user who initiated the run, kept on the run row), S07-18. Neither the
   * step key nor the requester is a secret; the subject itself is the stable service principal
   * ({@link ServiceIdentity}), never a user token.
   */
  private static String expiryAuditMetadata(OverdueApprovalRow overdue) {
    String requester = overdue.requester();
    String base = "step=" + overdue.stepKey();
    return (requester == null || requester.isBlank()) ? base : base + ",requester=" + requester;
  }

  /**
   * Expire one overdue {@code PENDING} approval and refuse its gated side effect — the entry point
   * of the approval-expiry sweep (S11-05). The guarded {@code EXPIRED} write wins the row against a
   * racing human decision (or a concurrent sweeper); the loser sees a {@link ConflictException} and
   * answers {@code false}, so an approval is expired at most once (at-least-once, ADR-003). The
   * audit row carries the system actor (an expiry is never a human decision) and the refusal
   * completion is the same one a human reject applies — the run and the gated step fail without the
   * side effect ever executing.
   *
   * @param overdue the overdue approval the scan observed
   * @return {@code true} when this pass won and completed the expiry, {@code false} when the row
   *     was decided concurrently and is left to the winner
   * @throws ResponseStatusException {@code 503} when no database is configured
   */
  @Transactional
  public boolean expireOverdue(OverdueApprovalRow overdue) {
    requireRepository(approvalRepository);
    OffsetDateTime now = OffsetDateTime.now(clock);
    try {
      approvalRepository.expirePending(
          overdue.tenantId(), overdue.runId(), overdue.id(), now, overdue.version());
    } catch (ConflictException e) {
      // A human decision (or another sweeper) won the row between the scan and this write: the
      // winner owns the lifecycle, this pass affects nothing.
      return false;
    }
    auditService.record(
        overdue.tenantId(),
        EXPIRY_ACTOR,
        AuditAction.EXPIRE,
        AuditResourceType.APPROVAL,
        overdue.id(),
        expiryAuditMetadata(overdue));
    refuseSideEffect(
        overdue.tenantId(),
        overdue.runId(),
        overdue.stepKey(),
        overdue.id(),
        EXPIRY_ACTOR,
        "EXPIRED");
    return true;
  }

  /**
   * The approve completion (S11-05): resume the run through the single transition service. The
   * run-level resume is the higher-level fact and moves first (mirroring the pause); the gated step
   * moves back to {@code RUNNING} ({@code run.approved}, the step-state-machine.md guard 11) and
   * immediately to {@code SUCCEEDED} — the human decision is the gate step's whole execution, so it
   * can never re-pause and recreate a second {@code PENDING} approval on the same step. The
   * continuation is published through the transactional outbox, the only publication channel
   * (invariant 3): the event and the state change commit or roll back together.
   */
  private void resumeAfterApproval(
      UUID tenantId, UUID runId, String stepKey, UUID approvalId, String actor) {
    transitionService.transitionRun(
        tenantId, runId, WorkflowRunState.RUNNING, "run.approved", actor);
    transitionService.transitionStep(
        tenantId, runId, stepKey, WorkflowStepState.RUNNING, "run.approved", actor);
    transitionService.transitionStep(
        tenantId, runId, stepKey, WorkflowStepState.SUCCEEDED, "step.approved", actor);
    outboxService.enqueue(resumedEvent(tenantId, runId, approvalId));
  }

  /**
   * The reject/expiry completion (S11-05): refuse the side effect through the single transition
   * service — the run and the gated step move {@code WAITING_FOR_APPROVAL -> FAILED} ({@code
   * run.rejected}), so the gated handler never executes (the acceptance: a rejected/expired request
   * never launches a side effect) and the refusal is published through the transactional outbox,
   * the only publication channel.
   */
  private void refuseSideEffect(
      UUID tenantId, UUID runId, String stepKey, UUID approvalId, String actor, String cause) {
    transitionService.transitionRun(
        tenantId, runId, WorkflowRunState.FAILED, "run.rejected", actor);
    transitionService.transitionStep(
        tenantId, runId, stepKey, WorkflowStepState.FAILED, "run.rejected", actor);
    outboxService.enqueue(refusedEvent(tenantId, runId, approvalId, cause));
  }

  /**
   * The continuation event of an approved gate: {@code workflow.run.resumed.v1} on {@code
   * agentflow.workflow.run-resumed.v1}. The payload carries the redacted correlation pair (run +
   * approval) — never the payload preimage or a secret (invariant 8); the run is the whole
   * correlation chain, so {@code correlationId = runId} and there is no causation event.
   */
  private static OutboxEvent resumedEvent(UUID tenantId, UUID runId, UUID approvalId) {
    return OutboxEvent.pending(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "workflow.run.resumed.v1",
        "agentflow.workflow.run-resumed.v1",
        "workflow_run",
        runId,
        tenantId,
        "{\"runId\":\"" + runId + "\",\"approvalId\":\"" + approvalId + "\"}",
        CorrelationHeaders.of(runId.toString(), null, null).toJson());
  }

  /**
   * The refusal event of a rejected/expired gate: {@code workflow.run.rejected.v1} on {@code
   * agentflow.workflow.run-rejected.v1}. The {@code cause} distinguishes the human decision ({@code
   * REJECTED}) from the deadline ({@code EXPIRED}) — one event type, two causes, the vocabulary
   * closed by the two callers (never caller input).
   */
  private static OutboxEvent refusedEvent(
      UUID tenantId, UUID runId, UUID approvalId, String cause) {
    return OutboxEvent.pending(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "workflow.run.rejected.v1",
        "agentflow.workflow.run-rejected.v1",
        "workflow_run",
        runId,
        tenantId,
        "{\"runId\":\""
            + runId
            + "\",\"approvalId\":\""
            + approvalId
            + "\",\"cause\":\""
            + cause
            + "\"}",
        CorrelationHeaders.of(runId.toString(), null, null).toJson());
  }

  /**
   * Answers a keyed repeat against the stored fingerprint: a row decided under the same key with
   * the same payload is replayed ({@code 200} with the recorded decision); the same key carrying a
   * different payload is a permanent {@code 409} — a reused key claims the payload too, exactly the
   * create-run rule (S06-06).
   */
  private ApprovalDecisionDto answerKeyedReplay(
      ApprovalRow keyed, String requestFingerprint, UUID idempotencyKey) {
    if (!Objects.equals(keyed.idempotencyFingerprint(), requestFingerprint)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "idempotency key "
              + idempotencyKey
              + " is already used with a different request payload");
    }
    // The keyed row always belongs to this tenant (the lookup is tenant-scoped); a replay of a
    // decided row is the recorded fact, regardless of which path id the caller named.
    return toDto(keyed);
  }

  /**
   * The tenant-scoped approval lookup: a cross-tenant id (or a guessed id under another run) is
   * indistinguishable from a missing one and renders {@code 404} (invariant 1).
   */
  private ApprovalRow findApproval(UUID tenantId, UUID runId, UUID approvalId) {
    return approvalRepository
        .find(tenantId, runId, approvalId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "approval "
                        + approvalId
                        + " on run "
                        + runId
                        + " was not found for this tenant"));
  }

  /**
   * The expiry guard: a decision after {@code expiry} is a permanent {@code 409} — the expiry
   * sweep, not the decider, moves an overdue request to {@code EXPIRED}.
   */
  private void assertNotExpired(ApprovalRow approval, OffsetDateTime now) {
    if (approval.expiry() != null && now.isAfter(approval.expiry())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "approval "
              + approval.id()
              + " expired at "
              + approval.expiry()
              + " and can no longer be decided");
    }
  }

  /**
   * The step guard: the approval's step must still be {@code WAITING_FOR_APPROVAL}. A missing step
   * is a {@code 404} (indistinguishable from a stale request), a step that moved on is a {@code
   * 409} — deciding a stale request would let a side effect run past its gate.
   */
  private void assertStepStillWaiting(UUID tenantId, UUID runId, ApprovalRow approval) {
    StepStateSnapshot step =
        stepRepository
            .findState(tenantId, runId, approval.stepKey())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "step '"
                            + approval.stepKey()
                            + "' on run "
                            + runId
                            + " was not found for this tenant"));
    if (step.status() != WorkflowStepState.WAITING_FOR_APPROVAL) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "step '"
              + approval.stepKey()
              + "' on run "
              + runId
              + " is "
              + step.status()
              + ", not WAITING_FOR_APPROVAL — the approval is stale");
    }
  }

  /**
   * The S11-06 payload-binding guard, the approve half: the decision completes the gate — it is the
   * moment the approved argument set is authorized to execute — so the fingerprint stored on the
   * request must still bind to the exact payload of the run's pinned graph. The digest is
   * recomputed from the pinned, immutable workflow version (the same {@link
   * ApprovalPayloadFingerprint} algorithm the worker used at the pause) and compared with the
   * stored {@code payload_fingerprint}; a mismatch, a gated step missing from the graph, or an
   * unverifiable (missing) pinned graph refuses the decision with {@code 409} and writes nothing —
   * an approved set of arguments can never execute a different one (the S11-06 acceptance). The run
   * stays parked in {@code WAITING_FOR_APPROVAL}: the stale request must be rejected (or expired)
   * and a new approval requested before the side effect may proceed.
   *
   * <p>The graph read reuses the existing {@code workflowexecution -> workflowdefinition} edge
   * (module-map arrow 2, the same public {@link WorkflowVersionService} the worker loads pinned
   * graphs through) — no new compile edge. The reject/expiry paths carry no such check on purpose:
   * they refuse the side effect, so a drifted binding creates no risk there, and leaving them open
   * lets an operator retire a stale request.
   */
  private void assertPayloadUnchanged(UUID tenantId, UUID runId, ApprovalRow approval) {
    requireRepository(runRepository);
    requireRepository(versionService);
    RunView run =
        runRepository
            .findRun(tenantId, runId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "run " + runId + " was not found for this tenant"));
    WorkflowGraph graph = versionService.loadGraph(tenantId, run.workflowVersionId());
    String currentFingerprint = null;
    if (graph != null) {
      for (WorkflowNode node : graph.nodes()) {
        if (node.id().equals(approval.stepKey())) {
          currentFingerprint = ApprovalPayloadFingerprint.compute(node.id(), node.config());
          break;
        }
      }
    }
    if (currentFingerprint == null || !currentFingerprint.equals(approval.payloadFingerprint())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "the gated parameters on run "
              + runId
              + " no longer match the payload this approval was requested for — the stored"
              + " fingerprint does not bind to the pinned payload; reject the stale request and"
              + " request a new approval");
    }
  }

  private static ApprovalDecisionDto toDto(ApprovalRow row) {
    return new ApprovalDecisionDto(
        row.id(),
        row.runId(),
        row.stepKey(),
        ApprovalStatus.valueOf(row.status()),
        row.decisionActor(),
        row.decisionAt(),
        row.decisionComment());
  }

  /**
   * @return the repository, or a {@code 503} when no database is configured (see S01-02)
   */
  private <T> T requireRepository(T repository) {
    if (repository == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "approval decisions are unavailable: no database connection is configured");
    }
    return repository;
  }
}
