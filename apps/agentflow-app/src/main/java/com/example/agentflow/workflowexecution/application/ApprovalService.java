package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.workflowexecution.domain.ApprovalStatus;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.ApprovalRepository;
import com.example.agentflow.workflowexecution.persistence.ApprovalRow;
import com.example.agentflow.workflowexecution.persistence.StepStateSnapshot;
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
 * The human-approval decision service (S11-04) — the public entry point named in {@code
 * docs/architecture/module-api.md}: the approve half of the approval lifecycle, scoped {@code
 * run:approve}.
 *
 * <p>{@link #approve} is {@link Transactional}: the conditional {@code approvals} decision write
 * and the {@link AuditService} append commit together or roll back together, so an audit record can
 * never diverge from the decision it describes (invariant 3, the plan's "записывать decision/audit
 * атомарно"). The audit write crosses the {@code workflowexecution -> audit} compile edge recorded
 * as {@code ADR-018}.
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
 *   <li><b>expiry</b> — a decision after {@code expiry} is refused with {@code 409} (the expiry
 *       lifecycle itself is S11-05);
 *   <li><b>step</b> — the gated step must still be {@code WAITING_FOR_APPROVAL}: a step that moved
 *       on (cancelled, …) makes the decision a {@code 409}, never a silent approval of a stale
 *       request.
 * </ol>
 *
 * <p><b>Idempotency.</b> Two mechanisms, both answering a repeat with the recorded decision instead
 * of a second write: by state (a repeat approve on an already-{@code APPROVED} approval replays the
 * decision) and by key (an {@code Idempotency-Key} header stored with the decision under the {@code
 * approvals_tenant_idempotency_key} guard, {@code V3_3_23}; the stored fingerprint proves a replay,
 * a payload change on a reused key is a {@code 409}). A keyed repeat is answered before the
 * targeted approval is even read, so a retried decision never depends on which request won the
 * race.
 *
 * <p>The service does not move the run or the step: the resume (run state + outbox continuation) is
 * S11-05. When no database is configured the repositories are {@link Autowired optional} and the
 * service reports {@code 503} instead of silently doing nothing (S01-02).
 */
@Service
public class ApprovalService {

  private final ApprovalRepository approvalRepository;
  private final WorkflowStepRunRepository stepRepository;
  private final AuditService auditService;
  private final Clock clock;

  public ApprovalService(
      @Autowired(required = false) ApprovalRepository approvalRepository,
      @Autowired(required = false) WorkflowStepRunRepository stepRepository,
      AuditService auditService,
      @Autowired(required = false) Clock clock) {
    this.approvalRepository = approvalRepository;
    this.stepRepository = stepRepository;
    this.auditService = auditService;
    this.clock = clock != null ? clock : Clock.systemUTC();
  }

  /**
   * Record an {@code APPROVED} decision on a {@code PENDING} approval, idempotently.
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
   *     409} when the approval is already decided otherwise, expired, its step moved on, or a
   *     reused key carries a different payload
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
    if (approval.expiry() != null && now.isAfter(approval.expiry())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "approval "
              + approvalId
              + " expired at "
              + approval.expiry()
              + " and can no longer be decided");
    }
    assertStepStillWaiting(tenantId, runId, approval);

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
      // Re-read: if the winner is an APPROVED decision, the repeat replays it; otherwise the
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

    // Re-read the decided row so the response reflects exactly what committed (including the
    // server-assigned decision instant), not the values this thread computed.
    return toDto(findApproval(tenantId, runId, approvalId));
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
   * The step guard: the approval's step must still be {@code WAITING_FOR_APPROVAL}. A missing step
   * is a {@code 404} (indistinguishable from a stale request), a step that moved on is a {@code
   * 409} — approving a stale request would let a side effect run past its gate.
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
