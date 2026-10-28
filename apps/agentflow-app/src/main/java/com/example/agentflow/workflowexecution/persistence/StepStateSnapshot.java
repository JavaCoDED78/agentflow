package com.example.agentflow.workflowexecution.persistence;

import com.example.agentflow.workflowexecution.domain.WorkflowStepState;

/**
 * The minimal persisted state the transition service (S05-11) needs to decide a step transition:
 * the step's system {@code id} (needed to scope a step-level {@code workflow_events} row), the
 * current {@link WorkflowStepState} (the domain gate), and the current {@code lease_epoch} (the
 * fencing primitive the step table uses instead of an optimistic-lock version, per {@code
 * V3_3_6__create_workflow_runs_and_steps.sql}).
 *
 * @param stepId the step's system UUID primary key
 * @param status the current step state, read from the {@code status} column
 * @param leaseEpoch the current fence, read from the {@code lease_epoch} column
 */
public record StepStateSnapshot(String stepId, WorkflowStepState status, int leaseEpoch) {}
