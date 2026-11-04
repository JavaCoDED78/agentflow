package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.common.ErrorClass;

/**
 * The outcome of classifying a step failure (S07-11): the canonical {@link ErrorClass} the fault
 * maps to and a short, safe (redacted) reason describing it.
 *
 * <p>This is the value the {@link StepFailureClassifier} produces. The retry decision follows from
 * {@link #errorClass()} via {@link ErrorClass#isRetryable()} (transient &rarr; retry with bounded
 * backoff, permanent &rarr; terminal), and the terminal step state follows from the same flag: a
 * permanent fault ends the step as {@code FAILED}, a transient one as {@code RETRY_WAIT} until its
 * retry budget is exhausted ({@code S07-10} / {@code S07-14}). The {@code safeReason} never carries
 * a secret, a full prompt or a document (invariant 8) &mdash; it is the redacted fault description
 * the worker records in {@code workflow_step_runs.error_summary}.
 *
 * <p>A pure value (no Spring, no I/O), value-equal like {@code
 * com.example.agentflow.workflowexecution.domain.Checkpoint}.
 *
 * @param errorClass the canonical error class the fault classified as (the failure class, the
 *     metric label and the retry decision source)
 * @param safeReason a short, redacted description of the fault, safe to persist and log
 */
public record StepFailure(ErrorClass errorClass, String safeReason) {

  /**
   * @return {@code true} only when {@link #errorClass()} is transient, so the step is retried with
   *     bounded backoff; {@code false} for a permanent fault, which is terminal
   */
  public boolean isRetryable() {
    return errorClass.isRetryable();
  }
}
