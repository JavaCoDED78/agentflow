package com.example.agentflow.workflowexecution.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public API of a {@code workflow_runs} row (S06-05) — the immutable view returned when a run is
 * created.
 *
 * <p>A distinct type from the persistence entity, exactly as the client-facing representation must
 * be (S03-13): the run id, the pinned active version it executes, its status ({@code QUEUED}), its
 * attempt number ({@code 1}) and the instant it was created.
 *
 * @param id the run id
 * @param workflowVersionId the pinned active version the run executes
 * @param status the run state ({@code QUEUED} at creation); set only through the state machine
 * @param attempt the execution attempt number ({@code 1} for a fresh run)
 * @param createdAt the instant the run was created
 */
public record RunDto(
    UUID id, UUID workflowVersionId, String status, int attempt, OffsetDateTime createdAt) {}
