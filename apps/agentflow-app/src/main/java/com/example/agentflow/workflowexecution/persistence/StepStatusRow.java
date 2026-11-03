package com.example.agentflow.workflowexecution.persistence;

import com.example.agentflow.workflowexecution.domain.WorkflowStepState;

/**
 * The transition decision data of one persisted step row (glossary "Step"): the {@code step_key}
 * that names the graph node, the current {@link WorkflowStepState} (the domain gate) and the step's
 * {@code lease_epoch} (the fence the step write uses instead of an optimistic-lock version, per
 * {@code V3_3_6__create_workflow_runs_and_steps.sql}).
 *
 * <p>The {@code run_id} and the parent {@code tenant_id} are matched by the tenant-scoped {@code
 * JOIN ... WHERE} of every lookup, so they do not need to live on this record — the caller supplies
 * them and the SQL re-checks them (invariant 1).
 *
 * @param stepKey the graph node the step runs
 * @param status the current step state, read from the {@code status} column
 * @param leaseEpoch the current fence, read from the {@code lease_epoch} column
 */
public record StepStatusRow(String stepKey, WorkflowStepState status, int leaseEpoch) {}
