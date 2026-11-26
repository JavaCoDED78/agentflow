package com.example.agentflow.workflowexecution.persistence;

import java.util.UUID;

/**
 * The expiry-sweep projection of an overdue {@code PENDING} approval (S11-05): the fields the
 * refusal completion needs — the identity triple (for the tenant-scoped guarded write, invariant
 * 1), the gated step, the optimistic-lock {@code version} the scan observed, and the run's
 * requester (the user who initiated the run, {@code workflow_runs.created_by} — nullable when the
 * run was begun without an actor) so the system audit record can carry it, the S07-18 contract (the
 * actor is the stable service subject; the requester stays on the run). A dedicated, minimal
 * projection (no decision metadata, no idempotency pair) because the sweep never decides anything a
 * human could have decided: it only fails what already outlived its deadline.
 */
public record OverdueApprovalRow(
    UUID id, UUID tenantId, UUID runId, String stepKey, int version, String requester) {}
