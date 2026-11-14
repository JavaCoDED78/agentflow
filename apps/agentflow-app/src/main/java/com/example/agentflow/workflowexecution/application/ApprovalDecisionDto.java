package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.workflowexecution.domain.ApprovalStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The response of the approval-decision endpoint ({@code POST
 * /api/v1/runs/{runId}/approvals/{approvalId}}, S11-04 glossary §9): the persisted decision
 * snapshot the approve call (or its idempotent replay) answers with.
 *
 * <p>It carries only decision metadata — never the payload, the fingerprint's preimage, a full
 * prompt or a document (invariant 8). The decision is bound to the {@code payload_fingerprint}
 * stored on the row (S11-06 enforces the binding at execution); this DTO intentionally does not
 * expose it.
 *
 * @param approvalId the approval record id
 * @param runId the run the approval gates
 * @param stepKey the graph node / step the request is gating
 * @param status the approval lifecycle status after the call (always {@code APPROVED} here)
 * @param decisionActor the actor who made the decision, or {@code null} while {@code PENDING}
 * @param decisionAt the decision instant, or {@code null} while {@code PENDING}
 * @param decisionComment the free-text decision note, or {@code null}
 */
public record ApprovalDecisionDto(
    UUID approvalId,
    UUID runId,
    String stepKey,
    ApprovalStatus status,
    String decisionActor,
    OffsetDateTime decisionAt,
    String decisionComment) {}
