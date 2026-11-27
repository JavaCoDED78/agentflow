package com.example.agentflow.usage.application;

import com.example.agentflow.usage.domain.BudgetDto;
import com.example.agentflow.usage.domain.BudgetMetric;
import com.example.agentflow.usage.domain.BudgetReservation;
import com.example.agentflow.usage.persistence.BudgetRepository;
import com.example.agentflow.workflowexecution.domain.BudgetCheckResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable per-tenant cost budget guard (S12-04 / {@code V3_3_26}). It reserves the estimated
 * cost / tokens of an operation <em>before</em> the operation starts and blocks the operation when
 * the reservation would exceed the tenant's remaining budget, then settles the reservation against
 * the actual cost <em>after</em> the operation runs - idempotently, so a duplicate delivery or a
 * crash-retry never double-charges.
 *
 * <p>This is the {@code usage}-module owner of the durable budget the domain glossary "Cost budget"
 * / "Budget" names. The {@code ai} module keeps its own pure, in-memory <b>pre-call</b> mirror
 * ({@code ModelBudget}, S09-10); {@code usage} owns the durable store, metering and at-least-once
 * settlement. {@code usage} reaches {@code ai} (allowed, module-map arrow 8) to use {@code
 * InsufficientBudgetException} as the refusal signal only through the {@code ai} guard - here the
 * refusal is {@link BudgetExhaustedException}, the {@code usage} mirror.
 *
 * <p><b>Tenant isolation (invariant 1).</b> Every read and write is keyed by {@code tenantId}, and
 * the predicate is part of the query itself, so another tenant's budget is indistinguishable from a
 * missing one.
 *
 * <p><b>Durability (invariant 2).</b> PostgreSQL is the source of truth: {@code tenant_budgets} and
 * {@code budget_reservations} are plain rows; no reservation is ever only in memory.
 *
 * <p><b>At-least-once + idempotency (invariant 3).</b> {@link #reserve} keys the reservation on an
 * idempotency id ({@link BudgetRepository#insertReservation} uses {@code ON CONFLICT DO NOTHING}),
 * and {@link #settle} is guarded by {@code settled_at IS NULL}; neither turns a repeat into a
 * second charge. Exactly-once is never promised.
 */
@Service
public class BudgetService {

  private final BudgetRepository budgetRepository;

  @Autowired
  public BudgetService(@Autowired(required = false) BudgetRepository budgetRepository) {
    this.budgetRepository = budgetRepository;
  }

  private void requireDatabase(String op) {
    if (budgetRepository == null) {
      throw new IllegalStateException(
          "budget guard unavailable: no database connection is configured (" + op + ")");
    }
  }

  /**
   * Check whether the tenant still has budget before an operation starts. A tenant with no budget
   * configured is allowed (unlimited, never blocked). This is the run-start gate.
   *
   * @param tenantId the owning tenant
   * @return {@link BudgetCheckResult#allow()} when any configured dimension still has headroom, or
   *     {@link BudgetCheckResult#reject(String)} with the exhausted dimension otherwise
   */
  public BudgetCheckResult checkBudget(UUID tenantId) {
    requireDatabase("checkBudget");
    Optional<BudgetDto> budget = budgetRepository.readBudget(tenantId);
    if (budget.isEmpty()) {
      return BudgetCheckResult.allow();
    }
    BudgetDto limits = budget.get();
    for (BudgetMetric metric : BudgetMetric.values()) {
      BigDecimal consumed = budgetRepository.consumed(tenantId, metric);
      BigDecimal limit = limits.maxFor(metric);
      if (consumed.compareTo(limit) >= 0) {
        return BudgetCheckResult.reject(
            "budget exceeded: "
                + metric
                + " spent "
                + consumed
                + " of "
                + limit
                + " ("
                + budgetPeriodName(limits)
                + ")");
      }
    }
    return BudgetCheckResult.allow();
  }

  /**
   * Reserve the estimated amount of an operation before it starts, blocking when the reservation
   * would exceed the tenant's remaining budget. The reservation is checked against the limit under
   * a row lock on the tenant's budget row, so concurrent reservations are serialized per tenant.
   *
   * @param tenantId the owning tenant
   * @param runId the run the reservation is charged to (may be {@code null} for a run-agnostic
   *     budget)
   * @param metric the dimension to reserve against ({@link BudgetMetric#COST} or {@link
   *     BudgetMetric#TOKEN})
   * @param estimatedAmount the estimated amount (never negative)
   * @param reservationId the idempotency key (a repeat with the same id is a no-op)
   * @return the reservation, settled later with {@link #settle}
   * @throws BudgetExhaustedException when the reservation would exceed the remaining budget, so the
   *     operation is blocked before it starts
   */
  @Transactional
  public BudgetReservation reserve(
      UUID tenantId,
      UUID runId,
      BudgetMetric metric,
      BigDecimal estimatedAmount,
      UUID reservationId) {
    return reserve(tenantId, runId, metric, estimatedAmount, reservationId, null);
  }

  /**
   * Reserve the estimated amount of an operation before it starts, keyed on the operation attempt
   * for idempotency (S12-06). The {@code reservation_id} is derived deterministically from the
   * (tenant, run, attempt, metric) tuple, so a duplicate attempt of the same operation reuses the
   * same idempotency key and is a no-op on the {@code ON CONFLICT (reservation_id)} primary key -
   * never a second charge (invariant 3, "use attempt ID for idempotency"); callers that already
   * have an explicit id use {@link #reserve(UUID, UUID, BudgetMetric, BigDecimal, UUID)}.
   *
   * @param tenantId the owning tenant
   * @param runId the run the reservation is charged to (may be {@code null} for a run-agnostic
   *     budget)
   * @param metric the dimension to reserve against ({@link BudgetMetric#COST} or {@link
   *     BudgetMetric#TOKEN})
   * @param estimatedAmount the estimated amount (never negative)
   * @param attempt the operation attempt the idempotency key is derived from
   * @return the reservation, settled later with {@link #settle}
   * @throws BudgetExhaustedException when the reservation would exceed the remaining budget, so the
   *     operation is blocked before it starts
   */
  public BudgetReservation reserve(
      UUID tenantId, UUID runId, BudgetMetric metric, BigDecimal estimatedAmount, Long attempt) {
    UUID reservationId =
        BudgetReservation.reservationIdForAttempt(tenantId, runId, attempt, metric);
    return reserve(tenantId, runId, metric, estimatedAmount, reservationId, attempt);
  }

  /**
   * Reserve the estimated amount of an operation before it starts, blocking when the reservation
   * would exceed the tenant's remaining budget. The reservation is checked against the limit under
   * a row lock on the tenant's budget row, so concurrent reservations are serialized per tenant.
   *
   * @param tenantId the owning tenant
   * @param runId the run the reservation is charged to (may be {@code null} for a run-agnostic
   *     budget)
   * @param metric the dimension to reserve against ({@link BudgetMetric#COST} or {@link
   *     BudgetMetric#TOKEN})
   * @param estimatedAmount the estimated amount (never negative)
   * @param reservationId the idempotency key (a repeat with the same id is a no-op)
   * @param attempt the operation attempt the reservation is keyed on, or {@code null} for a
   *     reservation keyed only by the explicit {@code reservationId}
   * @return the reservation, settled later with {@link #settle}
   * @throws BudgetExhaustedException when the reservation would exceed the remaining budget, so the
   *     operation is blocked before it starts
   */
  @Transactional
  private BudgetReservation reserve(
      UUID tenantId,
      UUID runId,
      BudgetMetric metric,
      BigDecimal estimatedAmount,
      UUID reservationId,
      Long attempt) {
    requireDatabase("reserve");
    budgetRepository.lockBudgetRow(tenantId);
    // Idempotency (invariant 3): a duplicate reservation with the same id is returned as-is - never
    // re-checked or re-inserted. A duplicate delivery or a crash-retry of the same operation never
    // double-charges, so the idempotency key is checked before the headroom comparison below.
    Optional<BudgetReservation> existing = budgetRepository.findReservation(reservationId);
    if (existing.isPresent()) {
      return existing.get();
    }
    Optional<BudgetDto> budget = budgetRepository.readBudget(tenantId);
    if (budget.isEmpty()) {
      // No budget configured: nothing to reserve against. Return a no-backup reservation the
      // caller can ignore; settle(null) on it is a no-op.
      return new BudgetReservation(
          reservationId, tenantId, runId, metric, estimatedAmount, null, attempt);
    }
    BudgetDto limits = budget.get();
    BigDecimal limit = limits.maxFor(metric);
    BigDecimal consumed = budgetRepository.consumed(tenantId, metric);
    BigDecimal remaining = limit.subtract(consumed);
    if (estimatedAmount.compareTo(remaining) > 0) {
      throw new BudgetExhaustedException(
          metric,
          remaining.signum() < 0 ? BigDecimal.ZERO : remaining,
          limit,
          "budget exceeded: "
              + metric
              + " reservation of "
              + estimatedAmount
              + " exceeds the remaining "
              + (remaining.signum() < 0 ? BigDecimal.ZERO : remaining)
              + " of "
              + limit);
    }
    int inserted =
        budgetRepository.insertReservation(
            reservationId, tenantId, runId, metric, estimatedAmount, attempt);
    if (inserted == 0) {
      // A duplicate reservation with the same id already exists (idempotent repeat) - return the
      // existing row, so the tenant is charged exactly once.
      return budgetRepository.findReservation(reservationId).orElseThrow();
    }
    return new BudgetReservation(
        reservationId, tenantId, runId, metric, estimatedAmount, null, attempt);
  }

  /**
   * Reconcile the unsettled reservations a crash stranded (S12-06): every reservation of the tenant
   * whose {@code settled_at} is still {@code null} and that was created before {@code before} is
   * settled at its best-known amount (the reserved estimate, when no actual cost is known) and
   * marked {@code reconciled} in the same statement, so a second reconcile of the same row is a
   * no-op (invariant 3). This is the recovery of a reservation left floating when the worker died
   * mid-operation or a provider call was ambiguous (S12-14) before it could settle.
   *
   * <p>The reconciliation writes the reservation's actual usage (the settled amount counts toward
   * {@link #consumed} exactly once) and frees the stranded reservation from its unsettled state -
   * "write actual/estimated usage and release the unused reservation". The scan is bounded to
   * {@code created_at < before} (a reservation only becomes stranded once some time has passed), so
   * a still-in-flight reservation is never mistaken for a stale one.
   *
   * @param tenantId the owning tenant
   * @param before only reconcile reservations created before this instant (invariant 10: pass the
   *     injected {@link java.time.Clock} time so time-dependent tests are deterministic)
   * @return the number of reservations reconciled ({@code 0} when none were stranded)
   */
  @Transactional
  public int reconcileUnsettled(UUID tenantId, OffsetDateTime before) {
    requireDatabase("reconcileUnsettled");
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    return budgetRepository.reconcileUnsettled(tenantId, before);
  }

  /**
   * Settle a reservation against its actual cost, once. Re-settling the same reservation is a no-op
   * (idempotent - invariant 3): the reservation's recorded amount moves from the estimated {@code
   * reserved_amount} to {@code actual_cost}, which is what {@link #consumed} sums.
   *
   * @param tenantId the owning tenant
   * @param reservationId the reservation to settle
   * @param actualCost the actual cost, or {@code null} to release the reservation unchanged
   */
  @Transactional
  public void settle(UUID tenantId, UUID reservationId, BigDecimal actualCost) {
    requireDatabase("settle");
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(reservationId, "reservationId must not be null");
    // A null actual cost releases the reservation unchanged (the estimate stands). The UPDATE still
    // runs so a repeat is idempotent (settled_at IS NULL guard) - it is a no-op after the first.
    budgetRepository.settleReservation(reservationId, actualCost);
  }

  private static String budgetPeriodName(BudgetDto limits) {
    return limits.period() == null ? "unknown" : limits.period().name().toLowerCase();
  }
}
