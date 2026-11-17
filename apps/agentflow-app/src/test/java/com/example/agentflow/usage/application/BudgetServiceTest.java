package com.example.agentflow.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.usage.domain.BudgetCheckResult;
import com.example.agentflow.usage.domain.BudgetDto;
import com.example.agentflow.usage.domain.BudgetMetric;
import com.example.agentflow.usage.domain.BudgetPeriod;
import com.example.agentflow.usage.domain.BudgetReservation;
import com.example.agentflow.usage.persistence.BudgetRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the durable budget guard {@link BudgetService} (S12-04): the check that blocks a
 * run before it starts and the idempotent {@code reserve} / {@code settle} lifecycle. The
 * repository is a small in-memory fake so the pure service logic - the accrual headroom comparison,
 * the block, the duplicate-reservation no-op and the idempotent settle - is exercised without a
 * database.
 *
 * <p>The fake models the real store's consumption semantics: {@link BudgetRepository#consumed} is
 * the accrual sum of every reservation - an unsettled reservation counts at its {@code
 * reserved_amount}, a settled one at its {@code actual_cost}.
 */
@DisplayName("BudgetService (unit)")
class BudgetServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID RUN = UUID.randomUUID();
  private static final BigDecimal COST = new BigDecimal("100");

  private final FakeBudgetRepository repo = new FakeBudgetRepository();
  private final BudgetService service = new BudgetService(repo);

  @Test
  @DisplayName("checkBudget allows when the tenant has no budget configured")
  void checkBudget_allows_when_no_budget_configured() {
    assertThat(service.checkBudget(TENANT).allowed()).isTrue();
  }

  @Test
  @DisplayName("checkBudget allows within budget and denies once exhausted")
  void checkBudget_allows_within_budget_then_denies_when_exhausted() {
    repo.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 5, 1_000, COST));

    assertThat(service.checkBudget(TENANT).allowed()).isTrue();

    // Consume the whole limit with an unsettled reservation.
    repo.seedReservation(TENANT, BudgetMetric.COST, COST);
    BudgetCheckResult denied = service.checkBudget(TENANT);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.reason()).contains("COST");
  }

  @Test
  @DisplayName("reserve blocks the operation when the reservation exceeds the remaining budget")
  void reserve_blocks_when_exceeding_remaining() {
    repo.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 5, 1_000, COST));

    assertThatThrownBy(
            () ->
                service.reserve(
                    TENANT, RUN, BudgetMetric.COST, new BigDecimal("150"), UUID.randomUUID()))
        .isInstanceOf(BudgetExhaustedException.class)
        .satisfies(
            ex -> {
              BudgetExhaustedException e = (BudgetExhaustedException) ex;
              assertThat(e.metric()).isEqualTo(BudgetMetric.COST);
              assertThat(e.limit()).isEqualByComparingTo(COST);
              assertThat(e.remaining()).isEqualByComparingTo(COST);
            });
    // Nothing was charged: a blocked reservation never inserts.
    assertThat(repo.reservations()).isEmpty();
  }

  @Test
  @DisplayName("reserve succeeds within the remaining budget")
  void reserve_succeeds_within_remaining() {
    repo.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 5, 1_000, COST));

    BudgetReservation reservation =
        service.reserve(TENANT, RUN, BudgetMetric.COST, new BigDecimal("60"), UUID.randomUUID());

    assertThat(reservation.reservationId()).isNotNull();
    assertThat(reservation.reservedAmount()).isEqualByComparingTo(new BigDecimal("60"));
    assertThat(repo.consumed(TENANT, BudgetMetric.COST)).isEqualByComparingTo(new BigDecimal("60"));
  }

  @Test
  @DisplayName("a duplicate reservation with the same id is a no-op, never a second charge")
  void duplicate_reservation_is_idempotent() {
    repo.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 5, 1_000, COST));
    UUID id = UUID.randomUUID();

    service.reserve(TENANT, RUN, BudgetMetric.COST, new BigDecimal("60"), id);
    BudgetReservation second =
        service.reserve(TENANT, RUN, BudgetMetric.COST, new BigDecimal("60"), id);

    // The repository only stored one reservation (the fake counts inserts); the second call
    // returned the existing one, so the tenant is charged exactly once.
    assertThat(second.reservationId()).isEqualTo(id);
    assertThat(repo.reservations()).hasSize(1);
  }

  @Test
  @DisplayName("settle is idempotent and reconciles the reservation to its actual cost")
  void settle_is_idempotent_and_reconciles_actual_cost() {
    repo.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 5, 1_000, COST));
    UUID id = UUID.randomUUID();

    service.reserve(TENANT, RUN, BudgetMetric.COST, new BigDecimal("60"), id);
    assertThat(repo.consumed(TENANT, BudgetMetric.COST)).isEqualByComparingTo(new BigDecimal("60"));

    // Settle to the actual (lower) cost; the row still counts, now at its actual cost.
    service.settle(TENANT, id, new BigDecimal("50"));
    service.settle(TENANT, id, new BigDecimal("50"));

    assertThat(repo.consumed(TENANT, BudgetMetric.COST)).isEqualByComparingTo(new BigDecimal("50"));
    // Only the first settle affected a row; the second is an idempotent no-op.
    assertThat(repo.settleRowsAffected()).isEqualTo(1);
  }

  @Test
  @DisplayName("settle with a null actual cost leaves the estimate unchanged")
  void settle_with_null_actual_cost_keeps_estimate() {
    repo.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 5, 1_000, COST));
    UUID id = UUID.randomUUID();

    service.reserve(TENANT, RUN, BudgetMetric.COST, new BigDecimal("60"), id);
    service.settle(TENANT, id, null);

    assertThat(repo.consumed(TENANT, BudgetMetric.COST)).isEqualByComparingTo(new BigDecimal("60"));
  }

  /** An in-memory {@link BudgetRepository} fake: no JDBC, so the {@code JdbcTemplate} is null. */
  static final class FakeBudgetRepository extends BudgetRepository {

    private final Map<UUID, BudgetDto> budgets = new HashMap<>();
    // reservation id -> its settled status and effective amount.
    private final Map<UUID, Row> reservations = new LinkedHashMap<>();
    private int settleRowsAffected;

    FakeBudgetRepository() {
      super(null);
    }

    void putBudget(BudgetDto budget) {
      budgets.put(budget.tenantId(), budget);
    }

    /** Seed an unsettled reservation directly (a pre-existing consumption for the tenant). */
    void seedReservation(UUID tenant, BudgetMetric metric, BigDecimal amount) {
      reservations.put(
          UUID.randomUUID(),
          new Row(UUID.randomUUID(), tenant, tenant, metric, amount, false, null));
    }

    List<BudgetReservation> reservations() {
      return reservations.values().stream().map(Row::toReservation).toList();
    }

    int settleRowsAffected() {
      return settleRowsAffected;
    }

    @Override
    public Optional<BudgetDto> readBudget(UUID tenantId) {
      return Optional.ofNullable(budgets.get(tenantId));
    }

    @Override
    public BigDecimal consumed(UUID tenantId, BudgetMetric metric) {
      return reservations.values().stream()
          .filter(row -> row.tenantId().equals(tenantId) && row.metric().equals(metric))
          .map(row -> (row.settled() && row.actualCost() != null) ? row.actualCost() : row.amount())
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public void lockBudgetRow(UUID tenantId) {
      // No lock needed in the in-memory fake.
    }

    @Override
    public Optional<BudgetReservation> findReservation(UUID reservationId) {
      Row row = reservations.get(reservationId);
      return row == null ? Optional.empty() : Optional.of(row.toReservation());
    }

    @Override
    public int insertReservation(
        UUID reservationId,
        UUID tenantId,
        UUID runId,
        BudgetMetric metric,
        BigDecimal reservedAmount) {
      if (reservations.containsKey(reservationId)) {
        // A duplicate id already exists: the ON CONFLICT DO NOTHING is a no-op (return 0).
        return 0;
      }
      reservations.put(
          reservationId,
          new Row(reservationId, tenantId, runId, metric, reservedAmount, false, null));
      return 1;
    }

    @Override
    public int settleReservation(UUID reservationId, BigDecimal actualCost) {
      Row row = reservations.get(reservationId);
      if (row == null || row.settled()) {
        // Already settled (or unknown): the settled_at IS NULL guard is a no-op.
        return 0;
      }
      settleRowsAffected++;
      reservations.put(
          reservationId,
          new Row(
              reservationId,
              row.tenantId(),
              row.runId(),
              row.metric(),
              row.amount(),
              true,
              actualCost));
      return 1;
    }

    /** The settled status and effective amount of a single reservation. */
    private record Row(
        UUID reservationId,
        UUID tenantId,
        UUID runId,
        BudgetMetric metric,
        BigDecimal amount,
        boolean settled,
        BigDecimal actualCost) {

      BudgetReservation toReservation() {
        return new BudgetReservation(reservationId, tenantId, runId, metric, amount);
      }
    }
  }
}
