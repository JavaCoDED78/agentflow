package com.example.agentflow.workflowexecution.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The subset of a {@code workflow_runs} row needed to answer a keyed create-run request (S06-06):
 * the run identity, its pinned version, its live state, the attempt counter, the instant it was
 * created and the fingerprint of the payload it was created with.
 *
 * <p>Used by {@link WorkflowRunRepository#findByIdempotencyKey} to replay an idempotent create
 * without touching the rest of the row. It is a distinct type from {@link
 * com.example.agentflow.workflowexecution.application.RunDto}: the persistence view additionally
 * carries the stored payload fingerprint, which the idempotency comparison needs but the client
 * view must not.
 *
 * @param runId the run identity (also the {@code id} of the {@code workflow_runs} row)
 * @param workflowVersionId the pinned active version the run executes
 * @param status the live run state (set only through the state machine)
 * @param attempt the execution attempt counter
 * @param createdAt the instant the run was created
 * @param fingerprint the stored lowercase-hex SHA-256 of the create-run payload, or {@code null}
 */
public record IdempotentRunView(
    UUID runId,
    UUID workflowVersionId,
    String status,
    int attempt,
    OffsetDateTime createdAt,
    String fingerprint) {}
