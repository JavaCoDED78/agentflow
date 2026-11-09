package com.example.agentflow.ai.application;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * The pre-call <b>model budget guard</b> (S09-10). It reserves a cost budget <em>before</em> a
 * potentially expensive chat call and settles it <em>after</em> the provider reports its real
 * usage, so an exhausted budget stops the call before the request is sent.
 *
 * <p><b>Why {@code ai} owns this.</b> The durable budget data (the limits in {@code
 * tenant_budgets}, the metering in {@code usage_records}) is owned by the {@code usage} module
 * ({@code docs/domain-glossary.md} "Cost budget" / "Budget"), and the gate forbids the {@code ai}
 * module from compiling against {@code usage}. So, exactly as {@code ai} owns its own {@link
 * ProviderResiliencePolicy} instead of importing the {@code workflowexecution}/{@code
 * toolexecution} retry policies, {@code ai} owns this pure guard value: it mirrors the {@code
 * usage} reservation/settlement concept so the AI layer can reserve before a call, but lives in
 * {@code ai.application} and depends on nothing outside {@code ai}. The persistent budget that
 * supplies the limit in production is wired in as a Spring bean by {@code usage} (S12); until then
 * a {@code null} guard means "no budget guard" (invariant-safe: calls proceed).
 *
 * <p><b>Reserve before the call.</b> {@link #reserve()} reserves the {@link
 * #conservativePerCallCost()} and throws {@link InsufficientBudgetException} when the reservation
 * would exceed the limit, so the caller never sends a request against an exhausted budget — this is
 * the S09-10 acceptance. The price of a call is unknown before it runs, so a <em>conservative</em>
 * (upper-bound) estimate is reserved (the "conservative estimate when the price is unknown"
 * behaviour); {@link #reserve(BigDecimal)} reserves a specific estimate when one is already known.
 *
 * <p><b>Settle after the call.</b> The {@link ModelBudgetReservation} returned by a reserve is
 * settled with {@link ModelBudgetReservation#settle(BigDecimal)}; in this sprint the actual cost is
 * not metered, so settling releases the reservation unchanged (cost-accurate settlement is {@code
 * S11}).
 *
 * <p><b>State.</b> Unlike the pure value types around it, this guard carries the in-memory {@link
 * #reserved()} amount (the committed headroom), so it is <em>not</em> a constant: it is a guard,
 * not a policy. In production the backing bean is shared, so its reservation is the running
 * commitment; a real-PostgreSQL implementation settles it idempotently (S12).
 *
 * @param limit the maximum estimated cost the guard permits; strictly positive
 * @param currency the budget currency (money is never a float; invariant 10)
 * @param conservativePerCallCost the upper-bound cost reserved before a call whose real price is
 *     unknown (never negative; a value of {@code 0} means "reserve nothing" — a guard that never
 *     blocks)
 */
public final class ModelBudget {

  private static final BigDecimal ZERO = BigDecimal.ZERO;

  private final BigDecimal limit;
  private final Currency currency;
  private final BigDecimal conservativePerCallCost;
  private BigDecimal reserved;

  /**
   * @param limit the maximum estimated cost; strictly positive
   * @param currency the budget currency; never {@code null}
   * @param conservativePerCallCost the per-call conservative estimate reserved when the price is
   *     unknown; non-negative
   * @throws NullPointerException if {@code currency} is {@code null}
   * @throws IllegalArgumentException if {@code limit} is not strictly positive or the estimate is
   *     negative
   */
  public ModelBudget(BigDecimal limit, Currency currency, BigDecimal conservativePerCallCost) {
    Objects.requireNonNull(currency, "currency must not be null");
    if (limit == null || limit.signum() <= 0) {
      throw new IllegalArgumentException("limit must be > 0 but was " + limit);
    }
    if (conservativePerCallCost == null || conservativePerCallCost.signum() < 0) {
      throw new IllegalArgumentException(
          "conservativePerCallCost must be >= 0 but was " + conservativePerCallCost);
    }
    this.limit = limit;
    this.currency = currency;
    this.conservativePerCallCost = conservativePerCallCost;
    this.reserved = ZERO;
  }

  /**
   * @return the budget limit; never {@code null}
   */
  public BigDecimal limit() {
    return limit;
  }

  /**
   * @return the budget currency; never {@code null}
   */
  public Currency currency() {
    return currency;
  }

  /**
   * @return the upper-bound cost reserved before a call whose real price is unknown; never {@code
   *     null}
   */
  public BigDecimal conservativePerCallCost() {
    return conservativePerCallCost;
  }

  /**
   * @return the amount already committed by outstanding reservations; never {@code null}
   */
  public BigDecimal reserved() {
    return reserved;
  }

  /**
   * @return {@code true} while more than zero of the budget is still uncommitted, so a further call
   *     may still reserve; {@code false} once the budget is fully committed
   */
  public boolean hasRemaining() {
    return remaining().signum() > 0;
  }

  /**
   * @return the uncommitted headroom ({@code limit - reserved}); never negative
   */
  public BigDecimal remaining() {
    return limit.subtract(reserved).max(ZERO);
  }

  /**
   * Reserve the call using the conservative per-call estimate (the price is unknown before the call
   * runs, so a conservative upper bound is reserved — S09-10).
   *
   * @return the reservation, to be settled after the call
   * @throws InsufficientBudgetException when the reservation would exceed the limit, so the caller
   *     stops the call before sending the request
   */
  public ModelBudgetReservation reserve() {
    return reserve(conservativePerCallCost);
  }

  /**
   * Reserve a specific estimate for the call.
   *
   * @param estimatedCost the estimated cost of the call; a value of {@code 0} reserves nothing
   * @return the reservation, to be settled after the call
   * @throws InsufficientBudgetException when the reservation would exceed the limit, so the caller
   *     stops the call before sending the request
   * @throws IllegalArgumentException if {@code estimatedCost} is negative
   */
  public ModelBudgetReservation reserve(BigDecimal estimatedCost) {
    if (estimatedCost == null) {
      throw new IllegalArgumentException("estimatedCost must not be null");
    }
    if (estimatedCost.signum() < 0) {
      throw new IllegalArgumentException("estimatedCost must be >= 0 but was " + estimatedCost);
    }
    BigDecimal candidate = reserved.add(estimatedCost);
    if (candidate.compareTo(limit) > 0) {
      throw new InsufficientBudgetException(
          remaining(),
          limit,
          currency,
          "model call rejected: the estimated-cost reservation of "
              + estimatedCost
              + " exceeds the remaining budget of "
              + remaining()
              + " of "
              + limit
              + " "
              + currency);
    }
    reserved = candidate;
    return new ModelBudgetReservation(this, estimatedCost);
  }

  /**
   * Reconcile a reservation to its actual cost (package-private: only {@link
   * ModelBudgetReservation} drives this). Moves the budget's reserved amount from {@code estimated}
   * to {@code actualCost}: {@code reserved = reserved - estimated + actualCost}, floored at zero. A
   * {@code null} actual releases the reservation unchanged. This is idempotent in the actual cost —
   * re-settling the same actual is a no-op, never a double charge.
   *
   * @param estimated the amount the reservation charged (never {@code null})
   * @param actualCost the actual cost, or {@code null} to release the reservation unchanged
   */
  void reconcile(BigDecimal estimated, BigDecimal actualCost) {
    BigDecimal settled = actualCost == null ? estimated : actualCost;
    BigDecimal next = reserved.subtract(estimated).add(settled);
    reserved = next.signum() < 0 ? ZERO : next;
  }
}
