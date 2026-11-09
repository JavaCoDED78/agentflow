package com.example.agentflow.ai.application;

/**
 * The Planner produced a response the backend cannot turn into a plan.
 *
 * <p>This is the S09-06 structured error signal: a response the model gave that is either not
 * well-formed JSON, or not a structurally valid plan (duplicate step id, dangling or self
 * dependency). It carries the underlying {@link #cause()} so the caller can tell a provider
 * formatting fault (a malformed {@code JacksonException}) apart from a structural violation (a
 * {@link PlannerPlan} canonical-construction failure). The follow-up {@code S09-08} turns this
 * signal into a bounded repair attempt; until then a malformed plan is a clear, visible fault,
 * never a silent {@code null} plan (the S09-08 acceptance).
 *
 * <p>It is unchecked: an untrusted planner response is a contract-level failure the caller reports
 * or repairs, not a checked exception every call site must swallow.
 */
public class PlannerPlanParseException extends RuntimeException {

  public PlannerPlanParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
