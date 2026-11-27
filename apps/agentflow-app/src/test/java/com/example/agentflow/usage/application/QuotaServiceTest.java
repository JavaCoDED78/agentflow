package com.example.agentflow.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.usage.domain.BudgetDto;
import com.example.agentflow.usage.domain.BudgetPeriod;
import com.example.agentflow.usage.persistence.BudgetRepository;
import com.example.agentflow.usage.persistence.RunSlotRepository;
import com.example.agentflow.workflowexecution.domain.QuotaCheckResult;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the durable per-tenant parallel-run quota {@link QuotaService} (S12-05): the
 * acquire-before-the-run-create / release-at-the-terminal-transition lifecycle and its idempotency.
 * Both repositories are small in-memory fakes so the pure service logic - the limit comparison, the
 * unlimited-tenant tracking, the duplicate-acquisition no-op and the idempotent release - is
 * exercised without a database.
 *
 * <p>The fakes model the real store's semantics: the composite {@code (tenant_id, run_id)} key
 * makes a repeated acquisition an {@code ON CONFLICT DO NOTHING} no-op, and a release is a {@code
 * DELETE} of an absent row when the run holds no slot.
 */
@DisplayName("QuotaService (unit)")
class QuotaServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID OTHER_TENANT = UUID.randomUUID();
  private static final UUID RUN = UUID.randomUUID();

  private final FakeRunSlotRepository slots = new FakeRunSlotRepository();
  private final FakeBudgetRepository budgets = new FakeBudgetRepository();
  private final QuotaService service = new QuotaService(slots, budgets);

  @Test
  @DisplayName("acquire allows when the tenant has no budget configured, still tracking the slot")
  void acquire_allows_and_tracks_without_budget() {
    QuotaCheckResult result = service.acquireRunSlot(TENANT, RUN);

    assertThat(result.allowed()).isTrue();
    assertThat(slots.countActive(TENANT)).isEqualTo(1);
  }

  @Test
  @DisplayName("acquire allows up to the configured parallel-runs limit then rejects")
  void acquire_allows_up_to_the_limit_then_rejects() {
    budgets.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 2, 1_000, new BigDecimal("100")));

    assertThat(service.acquireRunSlot(TENANT, UUID.randomUUID()).allowed()).isTrue();
    assertThat(service.acquireRunSlot(TENANT, UUID.randomUUID()).allowed()).isTrue();

    QuotaCheckResult third = service.acquireRunSlot(TENANT, UUID.randomUUID());
    assertThat(third.allowed()).isFalse();
    assertThat(third.reason()).contains("2 of 2");
    // A rejected acquisition never inserts: only the two successful slots exist.
    assertThat(slots.countActive(TENANT)).isEqualTo(2);
  }

  @Test
  @DisplayName("a limit of zero permits no run")
  void zero_limit_permits_no_run() {
    budgets.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 0, 1_000, new BigDecimal("100")));

    QuotaCheckResult result = service.acquireRunSlot(TENANT, RUN);

    assertThat(result.allowed()).isFalse();
    assertThat(slots.countActive(TENANT)).isZero();
  }

  @Test
  @DisplayName("a duplicate acquisition for the same run is a no-op, never a second count")
  void duplicate_acquisition_is_idempotent() {
    budgets.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 1, 1_000, new BigDecimal("100")));

    assertThat(service.acquireRunSlot(TENANT, RUN).allowed()).isTrue();
    // The same run re-acquiring (a duplicate delivery / an idempotent replay) is allowed, not
    // counted against the limit a second time.
    assertThat(service.acquireRunSlot(TENANT, RUN).allowed()).isTrue();
    assertThat(slots.countActive(TENANT)).isEqualTo(1);
  }

  @Test
  @DisplayName("release frees the slot so another run may acquire it")
  void release_frees_the_slot() {
    budgets.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 1, 1_000, new BigDecimal("100")));
    service.acquireRunSlot(TENANT, RUN);
    assertThat(service.acquireRunSlot(TENANT, UUID.randomUUID()).allowed()).isFalse();

    service.releaseRunSlot(TENANT, RUN);

    assertThat(slots.countActive(TENANT)).isZero();
    assertThat(service.acquireRunSlot(TENANT, UUID.randomUUID()).allowed()).isTrue();
  }

  @Test
  @DisplayName("release of a run that holds no slot is an idempotent no-op")
  void release_without_a_slot_is_a_no_op() {
    service.releaseRunSlot(TENANT, RUN);

    assertThat(slots.countActive(TENANT)).isZero();
  }

  @Test
  @DisplayName("tenant slots are isolated - one tenant's count never charges another")
  void tenant_slots_are_isolated() {
    budgets.putBudget(new BudgetDto(TENANT, BudgetPeriod.MONTHLY, 1, 1_000, new BigDecimal("100")));
    service.acquireRunSlot(TENANT, RUN);
    // TENANT is at its limit; the OTHER_TENANT (no budget) is unaffected.
    assertThat(service.acquireRunSlot(TENANT, UUID.randomUUID()).allowed()).isFalse();
    assertThat(service.acquireRunSlot(OTHER_TENANT, UUID.randomUUID()).allowed()).isTrue();
    assertThat(slots.countActive(OTHER_TENANT)).isEqualTo(1);
    assertThat(slots.countActive(TENANT)).isEqualTo(1);
  }

  @Test
  @DisplayName("acquire without a database fails loudly")
  void acquire_without_a_database_fails_loudly() {
    QuotaService noDb = new QuotaService(null, null);

    assertThatThrownBy(() -> noDb.acquireRunSlot(TENANT, RUN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no database connection is configured");
  }

  /** An in-memory {@link RunSlotRepository} fake: no JDBC, so the {@code JdbcTemplate} is null. */
  static final class FakeRunSlotRepository extends RunSlotRepository {

    private final Map<UUID, Set<UUID>> slotsByTenant = new HashMap<>();

    FakeRunSlotRepository() {
      super(null);
    }

    @Override
    public boolean holdsSlot(UUID tenantId, UUID runId) {
      return slotsByTenant.getOrDefault(tenantId, Set.of()).contains(runId);
    }

    @Override
    public long countActive(UUID tenantId) {
      return slotsByTenant.getOrDefault(tenantId, Set.of()).size();
    }

    @Override
    public int insertSlot(UUID tenantId, UUID runId) {
      Set<UUID> slots = slotsByTenant.computeIfAbsent(tenantId, key -> new HashSet<>());
      if (!slots.add(runId)) {
        // The composite key already exists: the ON CONFLICT DO NOTHING is a no-op (return 0).
        return 0;
      }
      return 1;
    }

    @Override
    public int deleteSlot(UUID tenantId, UUID runId) {
      Set<UUID> slots = slotsByTenant.get(tenantId);
      if (slots == null || !slots.remove(runId)) {
        return 0;
      }
      return 1;
    }
  }

  /** An in-memory {@link BudgetRepository} fake: only the limit read the quota service needs. */
  static final class FakeBudgetRepository extends BudgetRepository {

    private final Map<UUID, BudgetDto> budgets = new HashMap<>();

    FakeBudgetRepository() {
      super(null);
    }

    void putBudget(BudgetDto budget) {
      budgets.put(budget.tenantId(), budget);
    }

    @Override
    public Optional<BudgetDto> readBudget(UUID tenantId) {
      return Optional.ofNullable(budgets.get(tenantId));
    }

    @Override
    public void lockBudgetRow(UUID tenantId) {
      // No lock needed in the in-memory fake (the real serialization lives in QuotaServiceIT).
    }
  }
}
