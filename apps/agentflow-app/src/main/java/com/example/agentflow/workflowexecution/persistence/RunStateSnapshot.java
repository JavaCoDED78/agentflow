package com.example.agentflow.workflowexecution.persistence;

import com.example.agentflow.workflowexecution.domain.WorkflowRunState;

/**
 * The minimal persisted state the transition service (S05-11) needs to decide a run transition: the
 * current {@link WorkflowRunState} (the {@code status} column, used by the domain gate) and the
 * application-managed optimistic-lock {@code version}. The {@code workflow_runs.id} and {@code
 * tenant_id} are matched by the tenant-scoped {@code WHERE} clause of every lookup, so they do not
 * need to live on the snapshot — the caller supplies them and the SQL re-checks them.
 *
 * @param status the current run state, read from the {@code status} column
 * @param version the current optimistic-lock counter, read from the {@code version} column
 */
public record RunStateSnapshot(WorkflowRunState status, int version) {}
