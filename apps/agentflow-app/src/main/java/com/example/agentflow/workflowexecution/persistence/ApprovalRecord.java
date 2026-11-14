package com.example.agentflow.workflowexecution.persistence;

import java.util.UUID;

/**
 * The identity of a persisted {@code approvals} row (S11-02 / S11-03): the approval record id, the
 * run its step is gating and the graph node / step key it represents.
 *
 * <p>It carries no decision metadata (that is read back separately once a decision is made) — only
 * the identity the pause needs to record and the test needs to assert (S11-03).
 *
 * @param id the approval record id (UUID primary key)
 * @param runId the run the approval gates (its tenant isolation flows through the parent run)
 * @param stepKey the graph node / step the request is gating (matches {@code
 *     workflow_step_runs.step_key})
 */
public record ApprovalRecord(UUID id, UUID runId, String stepKey) {}
