package com.example.agentflow.workflowexecution.application;

import java.util.UUID;

/**
 * Public application input for starting a run (S06-06).
 *
 * <p>Carries the workflow the run should execute ({@code workflowId}) and the client's {@code
 * idempotencyKey} &mdash; the key the create is deduplicated under (tenant-scoped). An {@code
 * idempotencyKey} of {@code null} means the request is not deduplicated; a non-null key makes
 * {@link RunService#createRun} return the original run on a repeat payload and a {@code 409} on a
 * mismatching one.
 *
 * @param workflowId the definition the run belongs to (resolved to a pinned active version)
 * @param idempotencyKey the client idempotency key, or {@code null} for an unkeyed create
 */
public record CreateRunRequest(UUID workflowId, UUID idempotencyKey) {}
