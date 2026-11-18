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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the durable budget guard {@link BudgetService} (S12-04 / S12-06): the check that
 * blocks a run before it starts, the idempotent {@code reserve} / {@code settle} lifecycle and the
 * S12-06 reconciliation that recovers a reservation stranded by a crash. The repository is a small
 * in-memory fake so the pure service logic - the accrual headroom comparison, the block, the
 * duplicate-reservation no-op, the idempotent settle and the reconcile - is exercised without a
 * database.
 *
 * <p>The fake models the real store's consumption semantics: {@link BudgetRepository#consumed} is
 * the accrual sum of every reservation - an unsettled reservation counts at its {@code
 * reserved_amount}, a settled one at its {@code actual_cost} - and the real {@code findUnsettled}
 * scan boundary ({@code created_at < before}).
 */
@DisplayName("BudgetService (unit)")
class BudgetServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID RUN = UUID.randomUUID();
  private static final BigDecimal COST = new BigDecimal("100");

  // A fixed instant: a reservation created before it is stranded, one created after it is
  // in-flight.
  private static final OffsetDateTime RECONCILE_AT =
      OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);
  private static final OffsetDateTime BEFORE = RECONCILE_AT.minusHours(1);
  private static final OffsetDateTime AFTER = RECONCILE_AT.plusHours(1);

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

  @Test
  @DisplayName("reserve keys the reservation id on the attempt so a duplicate attempt is a no-op")
  void reserve_keys_id_on_attempt() {
    repo.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 5, 1_000, COST));

    BudgetReservation first =
        service.reserve(TENANT, RUN, BudgetMetric.COST, new BigDecimal("60"), 1L);
    BudgetReservation second =
        service.reserve(TENANT, RUN, BudgetMetric.COST, new BigDecimal("60"), 1L);

    // Both attempts derive the same id, so the second is a no-op - charged exactly once.
    assertThat(first.reservationId()).isEqualTo(second.reservationId());
    assertThat(repo.reservations()).hasSize(1);
    // The attempt is stored on the reservation.
    assertThat(first.attempt()).isEqualTo(1L);
  }

  @Test
  @DisplayName("reconcileUnsettled settles a stranded reservation at its reserved amount, once")
  void reconcile_unsettled_settles_stranded_once() {
    repo.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 5, 1_000, COST));

    // A reservation stranded before the reconcile boundary (the worker died before it could
    // settle):
    // created strictly before `before`, unsettled.
    UUID stranded =
        repo.seedReservationCreatedBefore(
            TENANT, BudgetMetric.COST, new BigDecimal("70"), BEFORE.minusHours(1));

    int reconciled = service.reconcileUnsettled(TENANT, BEFORE);
    assertThat(reconciled).isEqualTo(1);
    // The stranded reservation now counts at its best-known (reserved) amount and is reconciled.
    assertThat(repo.consumed(TENANT, BudgetMetric.COST)).isEqualByComparingTo(new BigDecimal("70"));
    assertThat(repo.reservationById(stranded).isReconciled()).isTrue();

    // A second reconcile of the same row is a no-op (idempotent - invariant 3).
    assertThat(service.reconcileUnsettled(TENANT, BEFORE)).isEqualTo(0);
  }

  @Test
  @DisplayName("reconcileUnsettled ignores an in-flight reservation created after the boundary")
  void reconcile_unsettled_ignores_in_flight() {
    repo.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 5, 1_000, COST));

    // A reservation created after the reconcile boundary is still in-flight, not stranded.
    UUID recent =
        repo.seedReservationCreatedBefore(TENANT, BudgetMetric.COST, new BigDecimal("40"), AFTER);

    int reconciled = service.reconcileUnsettled(TENANT, BEFORE);
    assertThat(reconciled).isEqualTo(0);
    assertThat(repo.reservationById(recent).isReconciled()).isFalse();
  }

  @Test
  @DisplayName("reconcileUnsettled returns zero when nothing is stranded")
  void reconcile_unsettled_returns_zero_when_nothing_stranded() {
    repo.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 5, 1_000, COST));
    assertThat(service.reconcileUnsettled(TENANT, BEFORE)).isEqualTo(0);
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
          new Row(
              UUID.randomUUID(), tenant, tenant, metric, amount, false, null, null, BEFORE, null));
    }

    /** Seed an unsettled reservation created at {@code createdAt} (models the real created_at). */
    UUID seedReservationCreatedBefore(
        UUID tenant, BudgetMetric metric, BigDecimal amount, OffsetDateTime createdAt) {
      UUID id = UUID.randomUUID();
      reservations.put(
          id, new Row(id, tenant, tenant, metric, amount, false, null, null, createdAt, null));
      return id;
    }

    List<BudgetReservation> reservations() {
      return reservations.values().stream().map(Row::toReservation).toList();
    }

    BudgetReservation reservationById(UUID id) {
      return reservations.get(id).toReservation();
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
    public List<BudgetReservation> findUnsettled(UUID tenantId, OffsetDateTime before) {
      // Model the real scan: only unsettled reservations created before `before` are stranded.
      return reservations.values().stream()
          .filter(row -> row.tenantId().equals(tenantId) && !row.settled())
          .filter(row -> row.createdAt().isBefore(before))
          .map(Row::toReservation)
          .toList();
    }

    @Override
    public int insertReservation(
        UUID reservationId,
        UUID tenantId,
        UUID runId,
        BudgetMetric metric,
        BigDecimal reservedAmount,
        Long attempt) {
      if (reservations.containsKey(reservationId)) {
        // A duplicate id already exists: the ON CONFLICT DO NOTHING is a no-op (return 0).
        return 0;
      }
      reservations.put(
          reservationId,
          new Row(
              reservationId,
              tenantId,
              runId,
              metric,
              reservedAmount,
              false,
              null,
              attempt,
              BEFORE,
              null));
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
              actualCost,
              row.attempt(),
              row.createdAt(),
              null));
      return 1;
    }

    @Override
    public int reconcileUnsettled(UUID tenantId, OffsetDateTime before) {
      int reconciled = 0;
      for (Row row : reservations.values()) {
        if (row.tenantId().equals(tenantId) && !row.settled() && row.createdAt().isBefore(before)) {
          // A reconciliation settles the stranded reservation at its reserved amount and marks it
          // (settled + reconciled) so a second reconcile is a no-op.
          reservations.put(
              row.reservationId(),
              new Row(
                  row.reservationId(),
                  row.tenantId(),
                  row.runId(),
                  row.metric(),
                  row.amount(),
                  true,
                  row.amount(),
                  row.attempt(),
                  row.createdAt(),
                  before));
          reconciled++;
        }
      }
      return reconciled;
    }

    /**
     * The settled status, effective amount, creation instant and reconciliation instant of a single
     * reservation. {@code reconciledAt} is {@code null} until {@link #reconcileUnsettled} marks the
     * row, so {@link BudgetReservation#reconciledAt()} distinguishes a settled-from-cost row from a
     * settled-from-reconcile row.
     */
    private record Row(
        UUID reservationId,
        UUID tenantId,
        UUID runId,
        BudgetMetric metric,
        BigDecimal amount,
        boolean settled,
        BigDecimal actualCost,
        Long attempt,
        OffsetDateTime createdAt,
        OffsetDateTime reconciledAt) {

      BudgetReservation toReservation() {
        return new BudgetReservation(
            reservationId, tenantId, runId, metric, amount, reconciledAt, attempt);
      }
    }
  }
}
