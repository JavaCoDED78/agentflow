package com.example.agentflow.workflowexecution.application;

/**
 * The approval-decision request body ({@code POST /api/v1/runs/{runId}/approvals/{approvalId}},
 * S11-04 glossary §9).
 *
 * <p>{@code decision} is the closed decision vocabulary; S11-04 implements {@code approve} only
 * (reject and the expiry lifecycle are S11-05), so any other value is a {@code 400} at the edge,
 * never a service-level surprise. {@code comment} is an optional, redacted free-text note — never a
 * secret, a full prompt or a document (invariant 8).
 *
 * @param decision the requested decision — only {@code "approve"} is accepted until S11-05 adds
 *     reject
 * @param comment an optional decision note, or {@code null}
 */
public record ApprovalDecisionRequest(String decision, String comment) {}
