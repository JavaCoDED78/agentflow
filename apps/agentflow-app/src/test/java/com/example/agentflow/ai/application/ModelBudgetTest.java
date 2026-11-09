package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pre-call model budget guard {@link ModelBudget} (S09-10): it reserves a cost
 * budget before a call and settles it after, so an exhausted budget blocks the call. The guard is a
 * pure in-memory value (the durable budget data lives in {@code usage}, S12), so these tests pin
 * the reserve / settle / block behaviour directly.
 */
class ModelBudgetTest {

  private static final Currency CURRENCY = Currency.getInstance("USD");

  private static final BigDecimal LIMIT = new BigDecimal("10");
  private static final BigDecimal PER_CALL = new BigDecimal("1");

  @Test
  void reserve_uses_the_conservative_estimate_when_the_price_is_unknown() {
    ModelBudget budget = new ModelBudget(LIMIT, CURRENCY, PER_CALL);

    ModelBudgetReservation reservation = budget.reserve();

    assertThat(reservation.estimated()).isEqualTo(PER_CALL);
    assertThat(budget.reserved()).isEqualTo(PER_CALL);
  }

  @Test
  void reserve_succeeds_while_it_fits_within_the_limit() {
    ModelBudget budget = new ModelBudget(LIMIT, CURRENCY, PER_CALL);

    ModelBudgetReservation reservation = budget.reserve();

    assertThat(reservation).isNotNull();
    assertThat(budget.hasRemaining()).isTrue();
    assertThat(budget.remaining()).isEqualTo(LIMIT.subtract(PER_CALL));
  }

  @Test
  void reserve_blocks_when_the_conservative_estimate_overdraws_a_small_limit() {
    // A limit smaller than the conservative per-call estimate overdraws on the very first reserve,
    // so the call is stopped before the request is sent (the S09-10 acceptance).
    ModelBudget budget = new ModelBudget(new BigDecimal("1"), CURRENCY, new BigDecimal("10"));

    assertThatThrownBy(budget::reserve)
        .isInstanceOf(InsufficientBudgetException.class)
        .satisfies(
            ex -> {
              InsufficientBudgetException e = (InsufficientBudgetException) ex;
              assertThat(e.limit()).isEqualByComparingTo(new BigDecimal("1"));
              assertThat(e.currency()).isEqualTo(CURRENCY);
            });
    // Nothing was committed before the refusal, so the remaining headroom is the full limit.
    assertThat(budget.remaining()).isEqualByComparingTo(new BigDecimal("1"));
  }

  @Test
  void reserve_blocks_on_a_follow_up_call_once_the_budget_is_fully_committed() {
    ModelBudget budget = new ModelBudget(new BigDecimal("1"), CURRENCY, new BigDecimal("1"));

    ModelBudgetReservation first = budget.reserve();
    assertThat(first).isNotNull();
    assertThat(budget.remaining()).isZero();
    assertThat(budget.hasRemaining()).isFalse();

    // The budget is fully committed, so the follow-up reserve overdraws and is blocked.
    assertThatThrownBy(budget::reserve)
        .isInstanceOf(InsufficientBudgetException.class)
        .satisfies(ex -> assertThat(((InsufficientBudgetException) ex).available()).isZero());
  }

  @Test
  void settle_with_a_null_actual_leaves_the_reservation_committed() {
    // The real cost is not metered yet (price catalogue is S11), so settle(null) is a committed
    // no-op: the guard keeps its conservative reservation and never under-reserves.
    ModelBudget budget = new ModelBudget(LIMIT, CURRENCY, PER_CALL);
    ModelBudgetReservation reservation = budget.reserve();

    reservation.settle(null);

    assertThat(budget.reserved()).isEqualTo(PER_CALL);
  }

  @Test
  void settle_with_the_actual_cost_reconciles_the_reserved_amount() {
    // A cheaper-than-expected call frees the difference (S11 settlement, exercised here on the
    // guard value).
    ModelBudget budget = new ModelBudget(LIMIT, CURRENCY, PER_CALL);
    ModelBudgetReservation reservation = budget.reserve();

    reservation.settle(new BigDecimal("0.4"));

    assertThat(budget.reserved()).isEqualByComparingTo("0.4");
  }

  @Test
  void settle_null_is_idempotent_leaves_reserved_unchanged() {
    // settle(null) reconciles estimated -> estimated, so it is a committed no-op: re-calling it is
    // never a double charge. (The cost-accurate settle(actual) is a one-shot reconciliation.)
    ModelBudget budget = new ModelBudget(LIMIT, CURRENCY, PER_CALL);
    ModelBudgetReservation reservation = budget.reserve();

    reservation.settle(null);
    reservation.settle(null);

    assertThat(budget.reserved()).isEqualTo(PER_CALL);
  }

  @Test
  void constructor_rejects_a_non_positive_limit_and_a_null_currency() {
    assertThatThrownBy(() -> new ModelBudget(BigDecimal.ZERO, CURRENCY, PER_CALL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ModelBudget(LIMIT, null, PER_CALL))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void constructor_rejects_a_negative_conservative_estimate() {
    assertThatThrownBy(() -> new ModelBudget(LIMIT, CURRENCY, new BigDecimal("-1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reserve_rejects_a_negative_estimate() {
    ModelBudget budget = new ModelBudget(LIMIT, CURRENCY, PER_CALL);

    assertThatThrownBy(() -> budget.reserve(new BigDecimal("-1")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
