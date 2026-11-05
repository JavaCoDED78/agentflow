package com.example.agentflow.workflowexecution.persistence;

import java.time.OffsetDateTime;

/**
 * One row of the {@code workflow_step_attempt_history} table (S07-14): a single attempt's durable
 * record — the attempt number, the graph node it ran ({@code step_key}), the worker that ran it,
 * the status it reached ({@code outcome}) and its start/finish instants. The safe fault metadata
 * ({@code error_class} token + {@code error_summary}) is stored here but never the secret payload;
 * the reader derives {@code duration} from {@code started_at}/{@code finished_at}.
 *
 * <p>The {@code run_id} and the parent {@code tenant_id} are matched by the tenant-scoped {@code
 * JOIN ... WHERE} of the read, so they do not need to live on this record — the caller supplies
 * them and the SQL re-checks them (invariant 1).
 *
 * @param attempt the per-step attempt index (1-based)
 * @param stepKey the graph node the attempt ran
 * @param workerInstance the worker that ran the attempt, or {@code null} when begun without an
 *     actor
 * @param outcome the status the attempt reached, or {@code null} while it was still running
 * @param startedAt the instant the attempt began
 * @param finishedAt the instant the attempt concluded, or {@code null} while in flight
 * @param errorClass the safe {@code ErrorClass} token of the fault, or {@code null}
 * @param errorSummary the short redacted reason for the fault, or {@code null}
 */
public record AttemptHistoryRow(
    int attempt,
    String stepKey,
    String workerInstance,
    String outcome,
    OffsetDateTime startedAt,
    OffsetDateTime finishedAt,
    String errorClass,
    String errorSummary) {}
