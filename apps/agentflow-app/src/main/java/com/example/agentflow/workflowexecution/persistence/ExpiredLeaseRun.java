package com.example.agentflow.workflowexecution.persistence;

import java.util.UUID;

/**
 * The tenant-scoped identity of a run whose run-level lease has expired, returned by the stale-run
 * recovery scan ({@link WorkflowRunRepository#findExpiredLeaseRuns}, S07-09, ADR-011).
 *
 * <p>A run is a stale-run candidate when its {@code lease_owner} is set (some worker owned it), its
 * {@code lease_until} is already past (that worker stopped heartbeating — crashed, drained or
 * terminated) and the run is not in a terminal state (a terminal run is not recoverable; the
 * recovery scan skips it). The {@code tenant_id} is carried here so the caller can re-claim the run
 * scoped to it ({@code RunLeaseService#claim(tenantId, runId)}), even though the scan itself is a
 * platform-wide lease scan rather than a single-tenant read.
 *
 * @param runId the run whose lease expired (the canonical {@code runId})
 * @param tenantId the owning tenant, so the re-claim is scoped to the run's own tenant
 */
public record ExpiredLeaseRun(UUID runId, UUID tenantId) {}
