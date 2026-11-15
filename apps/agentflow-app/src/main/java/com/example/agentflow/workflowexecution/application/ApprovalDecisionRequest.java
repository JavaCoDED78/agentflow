package com.example.agentflow.workflowexecution.application;

/**
 * The approval-decision request body ({@code POST /api/v1/runs/{runId}/approvals/{approvalId}},
 * S11-04/S11-05, glossary §9).
 *
 * <p>{@code decision} is the closed decision vocabulary — {@code "approve"} (S11-04) resumes the
 * run, {@code "reject"} (S11-05) fails it without ever executing the gated side effect; any other
 * value is a {@code 400} at the edge, never a service-level surprise. {@code comment} is an
 * optional, redacted free-text note — never a secret, a full prompt or a document (invariant 8).
 *
 * @param decision the requested decision — {@code "approve"} or {@code "reject"}
 * @param comment an optional decision note, or {@code null}
 */
public record ApprovalDecisionRequest(String decision, String comment) {}
