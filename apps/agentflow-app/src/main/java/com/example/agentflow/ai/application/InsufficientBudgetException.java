package com.example.agentflow.ai.application;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Thrown by the {@link ModelBudget} pre-call guard when a reservation would exceed the budget, so a
 * call is stopped <em>before</em> the request is sent (S09-10).
 *
 * <p>It carries the remaining headroom and the limit so the caller (and observability) can see how
 * far the budget was overdrawn without inventing a cost: the available amount is {@code limit -
 * reserved}, the budget is already committed up to its limit when this is thrown. This is the
 * single, loud signal that replaces any silent "allow the call anyway" fallback (invariant 7: bound
 * the cost of AI calls).
 *
 * <p>The identifiers it names ({@link #limit()}, {@link #available()}) are monetary amounts, not
 * high-cardinality metrics labels, so they belong in traces and errors only (invariant 9).
 */
public class InsufficientBudgetException extends RuntimeException {

  private final BigDecimal available;
  private final BigDecimal limit;
  private final Currency currency;

  /**
   * @param available the remaining headroom at the moment the reservation was refused (never
   *     negative)
   * @param limit the budget limit the reservation would have exceeded
   * @param currency the budget currency
   * @param message the human-readable refusal reason
   */
  public InsufficientBudgetException(
      BigDecimal available, BigDecimal limit, Currency currency, String message) {
    super(message);
    this.available = available;
    this.limit = limit;
    this.currency = currency;
  }

  /**
   * @return the remaining headroom (limit minus the already reserved amount) when the refusal
   *     happened; never negative
   */
  public BigDecimal available() {
    return available;
  }

  /**
   * @return the budget limit that was exceeded
   */
  public BigDecimal limit() {
    return limit;
  }

  /**
   * @return the budget currency
   */
  public Currency currency() {
    return currency;
  }
}
