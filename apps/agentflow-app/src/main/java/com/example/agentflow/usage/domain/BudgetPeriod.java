package com.example.agentflow.usage.domain;

/**
 * The window a per-tenant {@link BudgetDto} budget is measured over (S12-04 / {@code V3_3_26}). The
 * durable budget counts consumption inside the window and is reset when the window rolls over; the
 * reset itself is a later task, so the period is expressed here as a bounded vocabulary rather than
 * an implementation detail of the reset.
 */
public enum BudgetPeriod {
  /** The budget resets once per calendar day (UTC). */
  DAILY,
  /** The budget resets once per calendar month (UTC). */
  MONTHLY
}
