package com.example.agentflow.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.usage.domain.BudgetCheckResult;
import com.example.agentflow.usage.domain.BudgetMetric;
import com.example.agentflow.usage.domain.BudgetPeriod;
import com.example.agentflow.usage.domain.BudgetReservation;
import com.example.agentflow.usage.persistence.BudgetRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-PostgreSQL integration test for the durable per-tenant budget (S12-04 / {@code V3_3_26}).
 * The migration is applied against a real {@code pgvector/pgvector:0.8.6-pg18} container, and the
 * {@link BudgetRepository} + {@link BudgetService} are exercised on it: a budget round-trips, a
 * reserve blocks the moment it would exceed the limit, settlement reconciles the reservation to its
 * actual cost idempotently, and another tenant's budget is indistinguishable from a missing one
 * (invariant 1). No H2 is used as a stand-in for PostgreSQL (S03-08).
 *
 * <p>The {@code BudgetService} methods are {@code @Transactional}; because they are called directly
 * (not through a Spring proxy), the transaction is managed here by a {@link TransactionTemplate}
 * over the container's {@link javax.sql.DataSource}, so the real repository runs in a real
 * transaction.
 */
@Testcontainers
@DisplayName("BudgetService (integration, real PostgreSQL)")
class BudgetServiceIT {

  @Container
  static PostgreSQLContainer pg =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.6-pg18"))
          .withDatabaseName("agentflow");

  private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final BigDecimal LIMIT = new BigDecimal("100");
  private static final UUID RUN = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private static JdbcTemplate jdbc;
  private static TransactionTemplate tx;
  private static BudgetService service;

  @BeforeAll
  static void migrate() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
            .locations("classpath:migrations")
            .load();
    flyway.migrate();
    javax.sql.DataSource ds =
        new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    service = new BudgetService(new BudgetRepository(jdbc));
  }

  // The container (and therefore the tables) is shared across every test method, so start each test
  // from a clean slate - otherwise a second test's insertBudget(TENANT_A) would hit the primary key
  // that the first test committed.
  @BeforeEach
  void cleanState() {
    jdbc.execute("TRUNCATE budget_reservations, tenant_budgets");
  }

  @Test
  @DisplayName("a budget round-trips through the store")
  void a_budget_round_trips_through_the_store() {
    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 5, 1_000, LIMIT);

    Optional<com.example.agentflow.usage.domain.BudgetDto> budget =
        new BudgetRepository(jdbc).readBudget(TENANT_A);

    assertThat(budget).isPresent();
    com.example.agentflow.usage.domain.BudgetDto dto = budget.get();
    assertThat(dto.tenantId()).isEqualTo(TENANT_A);
    assertThat(dto.period()).isEqualTo(BudgetPeriod.MONTHLY);
    assertThat(dto.maxRuns()).isEqualTo(5);
    assertThat(dto.maxTokens()).isEqualTo(1_000);
    assertThat(dto.maxCost()).isEqualByComparingTo(LIMIT);
  }

  @Test
  @DisplayName("reserve blocks the moment the reservation would exceed the remaining budget")
  void reserve_blocks_when_it_would_exceed_the_remaining() {
    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 5, 1_000, LIMIT);

    BudgetReservation first =
        inTx(() -> service.reserve(TENANT_A, RUN, BudgetMetric.COST, new BigDecimal("60"), uuid()));
    assertThat(first.reservedAmount()).isEqualByComparingTo(new BigDecimal("60"));

    assertThatThrownBy(
            () ->
                inTx(
                    () ->
                        service.reserve(
                            TENANT_A, RUN, BudgetMetric.COST, new BigDecimal("60"), uuid())))
        .isInstanceOf(BudgetExhaustedException.class)
        .satisfies(
            ex -> {
              BudgetExhaustedException e = (BudgetExhaustedException) ex;
              assertThat(e.metric()).isEqualTo(BudgetMetric.COST);
              assertThat(e.limit()).isEqualByComparingTo(LIMIT);
            });
    // The blocked reservation left no row (rolled back); only the first successful one remains.
    assertThat(countReservations(TENANT_A)).isEqualTo(1);
  }

  @Test
  @DisplayName("settle reconciles the reservation to its actual cost, idempotently")
  void settle_reconciles_cost_idempotently() {
    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 5, 1_000, LIMIT);
    UUID id = uuid();

    inTx(() -> service.reserve(TENANT_A, RUN, BudgetMetric.COST, new BigDecimal("60"), id));
    assertThat(consumed(TENANT_A, BudgetMetric.COST)).isEqualByComparingTo(new BigDecimal("60"));

    // Settle to the actual (lower) cost; the settled row still counts, now at its actual cost.
    inTxRun(() -> service.settle(TENANT_A, id, new BigDecimal("50")));
    assertThat(consumed(TENANT_A, BudgetMetric.COST)).isEqualByComparingTo(new BigDecimal("50"));

    // Re-settling is an idempotent no-op: the stored actual cost does not change.
    inTxRun(() -> service.settle(TENANT_A, id, new BigDecimal("50")));
    assertThat(consumed(TENANT_A, BudgetMetric.COST)).isEqualByComparingTo(new BigDecimal("50"));
  }

  @Test
  @DisplayName("settle with a null actual cost leaves the estimate unchanged")
  void settle_null_actual_cost_keeps_estimate() {
    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 5, 1_000, LIMIT);
    UUID id = uuid();

    inTx(() -> service.reserve(TENANT_A, RUN, BudgetMetric.COST, new BigDecimal("60"), id));
    inTxRun(() -> service.settle(TENANT_A, id, null));

    assertThat(consumed(TENANT_A, BudgetMetric.COST)).isEqualByComparingTo(new BigDecimal("60"));
  }

  @Test
  @DisplayName("checkBudget allows without a budget and denies once exhausted")
  void checkBudget_allows_without_budget_and_denies_when_exhausted() {
    // No budget for TENANT_A yet: allowed (unlimited).
    assertThat(service.checkBudget(TENANT_A).allowed()).isTrue();

    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 5, 1_000, LIMIT);
    inTx(() -> service.reserve(TENANT_A, RUN, BudgetMetric.COST, LIMIT, uuid()));

    BudgetCheckResult denied = service.checkBudget(TENANT_A);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.reason()).contains("COST");
  }

  @Test
  @DisplayName("tenant budgets are isolated - one tenant's exhaustion does not affect another")
  void tenant_budgets_are_isolated() {
    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 5, 1_000, LIMIT);
    inTx(() -> service.reserve(TENANT_A, RUN, BudgetMetric.COST, LIMIT, uuid()));

    // TENANT_A is exhausted; TENANT_B has no budget and is allowed.
    assertThat(service.checkBudget(TENANT_A).allowed()).isFalse();
    assertThat(service.checkBudget(TENANT_B).allowed()).isTrue();

    // TENANT_B's consumption is not charged with TENANT_A's reservation.
    assertThat(consumed(TENANT_B, BudgetMetric.COST)).isZero();
    // TENANT_B can still reserve.
    BudgetReservation reserved =
        inTx(() -> service.reserve(TENANT_B, RUN, BudgetMetric.COST, new BigDecimal("40"), uuid()));
    assertThat(reserved.reservedAmount()).isEqualByComparingTo(new BigDecimal("40"));
  }

  private static UUID uuid() {
    return UUID.randomUUID();
  }

  private static <T> T inTx(Supplier<T> op) {
    return tx.execute(
        status -> {
          try {
            return op.get();
          } catch (RuntimeException e) {
            throw e;
          }
        });
  }

  private static void inTxRun(Runnable op) {
    tx.execute(
        status -> {
          op.run();
          return null;
        });
  }

  private static void insertBudget(
      UUID tenant, BudgetPeriod period, long runs, long tokens, BigDecimal cost) {
    jdbc.update(
        "INSERT INTO tenant_budgets (tenant_id, period, max_runs, max_tokens, max_cost) "
            + "VALUES (?, ?, ?, ?, ?)",
        tenant,
        period.name(),
        runs,
        tokens,
        cost);
  }

  private static BigDecimal consumed(UUID tenant, BudgetMetric metric) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(COALESCE(actual_cost, reserved_amount)), 0) FROM budget_reservations "
            + "WHERE tenant_id = ? AND metric = ?",
        BigDecimal.class,
        tenant,
        metric.name());
  }

  private static long countReservations(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM budget_reservations WHERE tenant_id = ?", Long.class, tenant);
  }
}
