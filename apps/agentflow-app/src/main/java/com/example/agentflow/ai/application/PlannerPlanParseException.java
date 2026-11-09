package com.example.agentflow.ai.application;

import java.util.Objects;

/**
 * The Planner produced a response the backend cannot turn into a plan.
 *
 * <p>This is the S09-06 structured error signal and the S09-08 repair entry point. It now carries a
 * {@link #classification()} that distinguishes <em>why</em> the attempt failed — a malformed {@code
 * JacksonException} ({@link PlannerFailure#MALFORMED_JSON}), a structural plan violation ({@link
 * PlannerFailure#SCHEMA_MISMATCH}), a provider refusal ({@link PlannerFailure#PROVIDER_REFUSAL}) or
 * a transport failure ({@link PlannerFailure#TRANSPORT_FAILURE}) — so the caller can tell a
 * formatting fault apart from a structural one and decide whether a bounded corrective retry is
 * worth taking. It carries the underlying {@link #cause()} for the same reason (a malformed {@code
 * JacksonException} apart from a {@link PlannerPlan} canonical-construction failure). The S09-08
 * bounded repair policy turns this signal into a bounded repair attempt: at most one corrective
 * retry, only for a repairable {@link PlannerFailure#isRepairable()} and only when budget is
 * available, so a malformed plan is a clear, visible fault, never a silent {@code null} plan and
 * never an infinite model-call loop (the S09-08 acceptance).
 *
 * <p>It is unchecked: an untrusted planner response is a contract-level failure the caller reports
 * or repairs, not a checked exception every call site must swallow.
 */
public class PlannerPlanParseException extends RuntimeException {

  private final PlannerFailure classification;

  /**
   * @param message a human-readable explanation of the failure
   * @param classification the failure mode (never {@code null}); the observable, domain-neutral
   *     label of what went wrong
   * @param cause the underlying fault (a {@code JacksonException}, a canonical-construction {@link
   *     IllegalArgumentException}, a transport exception, or {@code null} for a refusal)
   */
  public PlannerPlanParseException(String message, PlannerFailure classification, Throwable cause) {
    super(message, cause);
    this.classification = Objects.requireNonNull(classification, "classification must not be null");
  }

  /**
   * @return the failure mode ({@link PlannerFailure#MALFORMED_JSON}, {@link
   *     PlannerFailure#SCHEMA_MISMATCH}, {@link PlannerFailure#PROVIDER_REFUSAL} or {@link
   *     PlannerFailure#TRANSPORT_FAILURE}); never {@code null}
   */
  public PlannerFailure classification() {
    return classification;
  }
}
