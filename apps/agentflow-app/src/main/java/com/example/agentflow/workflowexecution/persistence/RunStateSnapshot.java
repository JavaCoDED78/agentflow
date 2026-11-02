package com.example.agentflow.workflowexecution.persistence;

import com.example.agentflow.workflowexecution.domain.WorkflowRunState;

/**
 * The minimal persisted state the transition service (S05-11) needs to decide a run transition: the
 * current {@link WorkflowRunState} (the {@code status} column, used by the domain gate), the
 * application-managed optimistic-lock {@code version}, and the run's {@code lease_epoch} (the
 * fencing counter, ADR-011). The {@code workflow_runs.id} and {@code tenant_id} are matched by the
 * tenant-scoped {@code WHERE} clause of every lookup, so they do not need to live on the snapshot —
 * the caller supplies them and the SQL re-checks them.
 *
 * <p>The {@code lease_epoch} is echoed on the run write (S07-05): the conditional update guards on
 * it as well as on {@code version}, so a write whose epoch no longer matches the row — because a
 * competing worker re-claimed the run and bumped the epoch — matches zero rows and becomes a {@code
 * 409} instead of overwriting the new owner (invariant 5 / fencing).
 *
 * @param status the current run state, read from the {@code status} column
 * @param version the current optimistic-lock counter, read from the {@code version} column
 * @param leaseEpoch the current fencing counter, read from the {@code lease_epoch} column
 */
public record RunStateSnapshot(WorkflowRunState status, int version, int leaseEpoch) {}
