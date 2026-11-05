package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.workflowexecution.persistence.AttemptHistoryRow;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Public API of one {@code workflow_step_attempt_history} row (S07-14) — the durable record of a
 * single attempt a step made: its {@code attempt number}, the graph node it ran ({@code stepKey}),
 * the {@code workerInstance} that ran it, the {@code outcome} it reached and its start/finish
 * instants. The {@code durationSeconds} is the {@code finished_at - started_at} the reader derives
 * (invariant 10 — derived, never stored); it is {@code 0} while the attempt is still running. The
 * {@code errorClass} is the safe {@code ErrorClass} token and {@code errorSummary} the short
 * redacted reason — the safe error category S07-14 asks for, never the secret payload (invariant
 * 8).
 *
 * @param attempt the per-step attempt index (1-based)
 * @param stepKey the graph node the attempt ran
 * @param workerInstance the worker that ran the attempt, or {@code null}
 * @param outcome the status the attempt reached, or {@code null} while in flight
 * @param startedAt the instant the attempt began
 * @param finishedAt the instant the attempt concluded, or {@code null} while in flight
 * @param durationSeconds {@code finished_at - started_at} in whole seconds (0 while in flight)
 * @param errorClass the safe {@code ErrorClass} token of the fault, or {@code null}
 * @param errorSummary the short redacted reason for the fault, or {@code null}
 */
public record AttemptRecord(
    int attempt,
    String stepKey,
    String workerInstance,
    String outcome,
    OffsetDateTime startedAt,
    OffsetDateTime finishedAt,
    long durationSeconds,
    String errorClass,
    String errorSummary) {

  /**
   * Build an API record from a persistence row, computing the duration from the start/finish
   * instants (invariant 10 — derived, never stored). An attempt that has not finished yet has no
   * duration, so it is reported as {@code 0}.
   *
   * @param row the attempt-history row
   * @return the API record
   */
  public static AttemptRecord fromRow(AttemptHistoryRow row) {
    long durationSeconds =
        (row.finishedAt() != null)
            ? Duration.between(row.startedAt(), row.finishedAt()).getSeconds()
            : 0L;
    return new AttemptRecord(
        row.attempt(),
        row.stepKey(),
        row.workerInstance(),
        row.outcome(),
        row.startedAt(),
        row.finishedAt(),
        durationSeconds,
        row.errorClass(),
        row.errorSummary());
  }
}
