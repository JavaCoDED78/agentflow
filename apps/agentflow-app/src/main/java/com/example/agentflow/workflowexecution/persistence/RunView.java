package com.example.agentflow.workflowexecution.persistence;

import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The tenant-scoped status snapshot of a run: the {@code workflow_runs.id}, the pinned {@code
 * workflow_version_id} the run executes, the current run {@link WorkflowRunState}, the execution
 * {@code attempt}, and the {@code created_at} time.
 *
 * <p>This is the single tenant-scoped read of a run row ({@link WorkflowRunRepository#findRun}). It
 * is both the identity the worker needs before it claims and schedules the run (it pins to {@code
 * workflowVersionId}) and the status snapshot the {@code GET /runs/{runId}} API returns; the API
 * {@link com.example.agentflow.workflowexecution.application.RunDto} is built directly from it,
 * exactly as the idempotent replay does.
 *
 * <p>The {@code tenant_id} is not carried here — every lookup that returns a {@code RunView} is
 * scoped to the parent's {@code tenant_id} in the {@code WHERE} clause (invariant 1), so a cross
 * tenant id is indistinguishable from a missing run and never reaches this record.
 *
 * @param runId the run id (the canonical {@code runId})
 * @param workflowVersionId the pinned, immutable workflow version the run executes
 * @param status the current run state, read from the {@code status} column
 * @param attempt the current execution attempt, read from the {@code attempt} column
 * @param createdAt the creation time, read from the {@code created_at} column
 */
public record RunView(
    UUID runId,
    UUID workflowVersionId,
    WorkflowRunState status,
    int attempt,
    OffsetDateTime createdAt) {}
