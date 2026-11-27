package com.example.agentflow.usage.application;

import com.example.agentflow.usage.domain.BudgetDto;
import com.example.agentflow.usage.persistence.BudgetRepository;
import com.example.agentflow.usage.persistence.RunSlotRepository;
import com.example.agentflow.workflowexecution.domain.QuotaCheckResult;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable per-tenant parallel-run quota (S12-05 / {@code V3_3_27}). It bounds how many runs a
 * tenant may hold in a non-terminal state at once: a run acquires its slot when it is created and
 * releases it when the run reaches a terminal state, so a tenant can never run (or queue) more than
 * its {@code tenant_budgets.max_runs} limit allows.
 *
 * <p>This is the {@code usage}-module owner of the quota the domain glossary "Quota" names. The
 * limit is not duplicated here: it stays the {@code max_runs} column of {@code tenant_budgets}
 * ({@code V3_3_26}, S12-04), which this service enforces. The usage dimensions (tokens / cost) are
 * bounded separately by the durable {@link BudgetService} (S12-04); together they are the
 * per-tenant limits Sprint 12 requires.
 *
 * <p><b>Tenant isolation (invariant 1).</b> Every read and write is keyed by {@code (tenantId,
 * runId)}, and the predicate is part of the query itself, so another tenant's slots are
 * indistinguishable from missing ones.
 *
 * <p><b>Durability (invariant 2).</b> PostgreSQL is the source of truth: the slots are plain rows
 * in {@code tenant_run_slots}, counted in PostgreSQL under a row lock - never in Redis, which stays
 * available only for auxiliary rate limiting / caching.
 *
 * <p><b>Concurrency (the S12-05 acceptance).</b> {@link #acquireRunSlot} locks the tenant's {@code
 * tenant_budgets} row ({@link BudgetRepository#lockBudgetRow}, the same {@code SELECT ... FOR
 * UPDATE} serialization {@link BudgetService#reserve} uses), then counts the tenant's active slots
 * and inserts the new one while still holding the lock. Two concurrent run creations therefore
 * cannot both pass the headroom check: the second blocks on the row lock, re-reads the count after
 * the first commits, and is refused.
 *
 * <p><b>At-least-once + idempotency (invariant 3).</b> The composite {@code (tenant_id, run_id)}
 * primary key makes a repeated acquisition an {@code ON CONFLICT DO NOTHING} no-op (the same run is
 * never counted twice), and a repeated release is a {@code DELETE} of an already-absent row.
 * Exactly-once is never promised.
 */
@Service
public class QuotaService {

  private final RunSlotRepository runSlotRepository;
  private final BudgetRepository budgetRepository;

  @Autowired
  public QuotaService(
      @Autowired(required = false) RunSlotRepository runSlotRepository,
      @Autowired(required = false) BudgetRepository budgetRepository) {
    this.runSlotRepository = runSlotRepository;
    this.budgetRepository = budgetRepository;
  }

  private void requireDatabase(String op) {
    if (runSlotRepository == null || budgetRepository == null) {
      throw new IllegalStateException(
          "run quota unavailable: no database connection is configured (" + op + ")");
    }
  }

  /**
   * Acquire the run's quota slot before the run is created. A tenant with no {@code tenant_budgets}
   * row is unlimited (never blocked) but its runs are still tracked, so the count stays truthful if
   * a limit is configured later. A run whose slot already exists (a duplicate delivery, an
   * idempotent replay) is allowed without being counted again.
   *
   * @param tenantId the owning tenant
   * @param runId the run acquiring the slot
   * @return {@link QuotaCheckResult#allow()} when the slot was acquired (or already existed),
   *     {@link QuotaCheckResult#reject(String)} with the exhausted limit otherwise
   */
  @Transactional
  public QuotaCheckResult acquireRunSlot(UUID tenantId, UUID runId) {
    requireDatabase("acquireRunSlot");
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(runId, "runId must not be null");
    // Idempotency (invariant 3): the same run re-acquiring its slot is a no-op - never re-checked
    // against the limit, never counted twice.
    if (runSlotRepository.holdsSlot(tenantId, runId)) {
      return QuotaCheckResult.allow();
    }
    Optional<BudgetDto> budget = budgetRepository.readBudget(tenantId);
    if (budget.isPresent()) {
      // Serialize concurrent acquisitions per tenant: the row lock is held until this transaction
      // commits, so the count below cannot race a concurrent acquisition of the same tenant.
      budgetRepository.lockBudgetRow(tenantId);
      long active = runSlotRepository.countActive(tenantId);
      long maxRuns = budget.get().maxRuns();
      if (active >= maxRuns) {
        return QuotaCheckResult.reject(
            "tenant quota exceeded: parallel runs "
                + active
                + " of "
                + maxRuns
                + " already active");
      }
    }
    int inserted = runSlotRepository.insertSlot(tenantId, runId);
    if (inserted == 0) {
      // A concurrent transaction inserted the same (tenant, run) slot first (the run is being
      // created exactly once; a duplicate delivery or a crash-retry is allowed, never charged
      // twice).
      return QuotaCheckResult.allow();
    }
    return QuotaCheckResult.allow();
  }

  /**
   * Release the run's quota slot at its terminal transition. Idempotent: releasing a run that holds
   * no slot (already terminal, never acquired, already released) changes nothing, so an at-least-
   * once repeat never corrupts the count (invariant 3).
   *
   * @param tenantId the owning tenant
   * @param runId the run releasing the slot
   */
  @Transactional
  public void releaseRunSlot(UUID tenantId, UUID runId) {
    requireDatabase("releaseRunSlot");
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(runId, "runId must not be null");
    runSlotRepository.deleteSlot(tenantId, runId);
  }
}
