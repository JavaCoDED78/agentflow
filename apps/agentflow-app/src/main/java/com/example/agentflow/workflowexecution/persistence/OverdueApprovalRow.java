package com.example.agentflow.workflowexecution.persistence;

import java.util.UUID;

/**
 * The expiry-sweep projection of an overdue {@code PENDING} approval (S11-05): the fields the
 * refusal completion needs — the identity triple (for the tenant-scoped guarded write, invariant
 * 1), the gated step and the optimistic-lock {@code version} the scan observed. A dedicated,
 * minimal projection (no decision metadata, no idempotency pair) because the sweep never decides
 * anything a human could have decided: it only fails what already outlived its deadline.
 */
public record OverdueApprovalRow(UUID id, UUID tenantId, UUID runId, String stepKey, int version) {}
