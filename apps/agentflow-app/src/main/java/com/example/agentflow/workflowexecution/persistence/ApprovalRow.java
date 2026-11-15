package com.example.agentflow.workflowexecution.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The full tenant-scoped read model of one {@code approvals} row (S11-04): the decision-relevant
 * columns the approve endpoint loads, checks and returns.
 *
 * <p>It is the read counterpart of the insert path ({@link ApprovalRepository#create}, S11-02 /
 * S11-03): status and optimistic-lock version drive the conditional decision update, {@code expiry}
 * bounds the decision window, the decision columns are what an idempotent replay answers with, and
 * the idempotency pair ({@code idempotencyKey} / {@code idempotencyFingerprint}, {@code V3_3_23} /
 * S11-04) answers a keyed repeat. The status is the storage TEXT the {@code approvals_status_check}
 * bounds — mapped to {@link com.example.agentflow.workflowexecution.domain.ApprovalStatus} at the
 * application boundary.
 *
 * @param id the approval record id (UUID primary key)
 * @param runId the run the approval gates (its tenant isolation flows through the parent run)
 * @param stepKey the graph node / step the request is gating
 * @param payloadFingerprint the digest of the exact gated payload the decision binds to (S11-06:
 *     the canonical-JSON SHA-256 of the step plus the node config — never the payload preimage,
 *     invariant 8)
 * @param status the storage status token (one of the {@code approvals_status_check} set)
 * @param expiry the instant after which a decision is no longer valid, or {@code null}
 * @param version the optimistic-lock counter the conditional decision update is guarded on
 * @param decisionActor the actor who made the decision, or {@code null} while {@code PENDING}
 * @param decisionAt the decision instant, or {@code null} while {@code PENDING}
 * @param decisionComment the free-text decision note, or {@code null}
 * @param idempotencyKey the client key the decision was recorded under, or {@code null}
 * @param idempotencyFingerprint the digest of the canonical decision payload, or {@code null}
 */
public record ApprovalRow(
    UUID id,
    UUID runId,
    String stepKey,
    String payloadFingerprint,
    String status,
    OffsetDateTime expiry,
    int version,
    String decisionActor,
    OffsetDateTime decisionAt,
    String decisionComment,
    UUID idempotencyKey,
    String idempotencyFingerprint) {}
