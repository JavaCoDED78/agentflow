package com.example.agentflow.usage.domain;

/**
 * The dimension a reservation is measured against (S12-04 / {@code V3_3_26}). The durable budget
 * guards two, both a cost of a run: the estimated {@link #COST} (money, {@code NUMERIC}) and the
 * estimated {@link #TOKEN} count. Steps and tool calls are bounded separately by the per-run {@code
 * RunExecutionLimits} (S11-13), so they are not a budget metric here.
 */
public enum BudgetMetric {
  /** The estimated cost of the operation (money, never a float - invariant 10). */
  COST,
  /** The estimated token count of the operation. */
  TOKEN
}
