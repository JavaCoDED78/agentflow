package com.example.agentflow.ai.application;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A call's slice of the {@link ModelBudget} (S09-10): the amount reserved <em>before</em> a model
 * call is settled <em>after</em> the provider reports its real usage.
 *
 * <p>The reservation is created by {@link ModelBudget#reserve()} / {@link
 * ModelBudget#reserve(BigDecimal)} and held across the call. On success it is settled with {@link
 * #settle(BigDecimal)}; on a failed attempt it is left reserved so an already-committed budget
 * stops the next attempt (that is how the guard blocks a retry once the budget is spent).
 *
 * <p><b>Settlement is reconciling, not accumulating.</b> {@link #settle(BigDecimal)} reconciles the
 * reservation to the call's real cost: {@code reserved = reserved - estimated + actualCost}
 * (floored at zero, so a cheaper-than-expected call frees the difference, and an overrun is
 * covered). A {@code null} actual means the real cost is not yet metered (the price catalogue is
 * {@code S11}), so the reservation is left committed <em>unchanged</em> — the budget keeps the
 * conservative estimate as a running commitment and the guard stays safe (it never under-reserves).
 * {@code settle(null)} is idempotent (re-calling it is a committed no-op); {@code settle(actual)}
 * is a one-shot reconciliation. The durable at-least-once reconciliation — deduplicated by record
 * id — is owned in {@code usage} (S12). The blocking behaviour the acceptance checks — an exhausted
 * budget stops the call — is entirely on {@link ModelBudget#reserve()}; settlement is a later task.
 *
 * @param budget the budget the reservation was drawn from; never {@code null}
 * @param estimated the amount reserved for the call; never {@code null}
 */
public final class ModelBudgetReservation {

  private final ModelBudget budget;
  private final BigDecimal estimated;

  ModelBudgetReservation(ModelBudget budget, BigDecimal estimated) {
    this.budget = Objects.requireNonNull(budget, "budget must not be null");
    this.estimated = Objects.requireNonNull(estimated, "estimated must not be null");
  }

  /**
   * @return the amount reserved for the call (the estimate that was charged against the budget)
   */
  public BigDecimal estimated() {
    return estimated;
  }

  /**
   * Settle the reservation against the call's real cost (S09-10).
   *
   * <p>Moves the budget's reserved amount from the {@link #estimated()} reservation to {@code
   * actualCost} (releasing the unused part, or covering an overrun). When {@code actualCost} is
   * {@code null} the real cost is not yet metered (the price catalogue is {@code S11}) so the
   * reservation is left committed <em>unchanged</em> — the budget keeps the conservative estimate
   * as a running commitment, and the reserve-before-the-call guard ({@link ModelBudget#reserve()})
   * is what blocks an exhausted budget. {@code settle(null)} is idempotent (a committed no-op);
   * {@code settle(actual)} is a one-shot reconciliation.
   *
   * @param actualCost the provider-reported cost of the call, or {@code null} when the cost is not
   *     yet metered (the reservation is then left committed unchanged)
   */
  public void settle(BigDecimal actualCost) {
    budget.reconcile(estimated, actualCost);
  }
}
