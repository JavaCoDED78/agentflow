package com.example.agentflow.usage.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.usage.domain.BudgetPeriod;
import com.example.agentflow.usage.domain.QuotaCheckResult;
import com.example.agentflow.usage.persistence.BudgetRepository;
import com.example.agentflow.usage.persistence.RunSlotRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Real-PostgreSQL integration test for the durable per-tenant parallel-run quota (S12-05 / {@code
 * V3_3_27}). The migration is applied against a real {@code pgvector/pgvector:0.8.6-pg18} container
 * and the {@link RunSlotRepository} + {@link QuotaService} are exercised on it, with the S12-05
 * acceptance front and center: <b>a race of concurrent run creations cannot bypass the tenant
 * quota.</b> Because every acquisition counts in PostgreSQL under the tenant's {@code
 * tenant_budgets} row lock ({@code SELECT ... FOR UPDATE}), the count a contender reads always
 * includes every committed concurrent acquisition - never a stale Redis-side value. No H2 is used
 * as a stand-in for PostgreSQL (S03-08).
 *
 * <p>The race spawns one thread per contender, each owning its own connection and transaction (a
 * {@code DriverManagerDataSource} makes a fresh connection per call, so the threads genuinely
 * contend on the row lock), and all threads are released at the same instant to maximize the
 * overlap. With a limit of 3 and 8 contenders exactly 3 acquisitions commit and 5 are refused.
 *
 * <p>The {@code QuotaService} methods are {@code @Transactional}; because they are called directly
 * (not through a Spring proxy), the transaction is managed here by a {@link TransactionTemplate}
 * per contender connection, so the real repository runs in a real transaction.
 */
@Testcontainers
@DisplayName("QuotaService (integration, real PostgreSQL)")
class QuotaServiceIT {

  @Container
  static PostgreSQLContainer pg =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.6-pg18"))
          .withDatabaseName("agentflow");

  private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private static JdbcTemplate jdbc;
  private static TransactionTemplate tx;
  private static QuotaService service;

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
    service = new QuotaService(new RunSlotRepository(jdbc), new BudgetRepository(jdbc));
  }

  // The container (and therefore the tables) is shared across every test method, so start each test
  // from a clean slate.
  @BeforeEach
  void cleanState() {
    jdbc.execute("TRUNCATE tenant_run_slots, budget_reservations, tenant_budgets");
  }

  @Test
  @DisplayName("the race acceptance: concurrent acquisitions never bypass the tenant quota")
  void concurrent_acquisitions_cannot_bypass_the_tenant_quota() throws Exception {
    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 3, 1_000, new BigDecimal("100"));

    int contenders = 8;
    ExecutorService pool = Executors.newFixedThreadPool(contenders);
    CountDownLatch ready = new CountDownLatch(contenders);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < contenders; i++) {
      UUID runId = UUID.randomUUID();
      results.add(
          pool.submit(
              () -> {
                // One transaction per contender over its own connection: the threads genuinely
                // contend on the tenant's tenant_budgets row lock.
                ready.countDown();
                start.await(30, TimeUnit.SECONDS);
                return inTx(() -> service.acquireRunSlot(TENANT_A, runId).allowed());
              }));
    }
    assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
    start.countDown();

    int allowed = 0;
    for (Future<Boolean> result : results) {
      if (result.get(60, TimeUnit.SECONDS)) {
        allowed++;
      }
    }
    pool.shutdownNow();

    // Exactly the limit wins: the row-lock-serialized count refuses every contender past it.
    assertThat(allowed).isEqualTo(3);
    assertThat(countSlots(TENANT_A)).isEqualTo(3);
  }

  @Test
  @DisplayName("a slot round-trips: acquire counts, release frees, re-acquire succeeds")
  void acquire_counts_and_release_frees() {
    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 1, 1_000, new BigDecimal("100"));
    UUID run = UUID.randomUUID();

    assertThat(inTx(() -> service.acquireRunSlot(TENANT_A, run).allowed())).isTrue();
    assertThat(countSlots(TENANT_A)).isEqualTo(1);

    // At the limit: a second run is refused and leaves no row.
    QuotaCheckResult refused = inTx(() -> service.acquireRunSlot(TENANT_A, UUID.randomUUID()));
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.reason()).contains("1 of 1");
    assertThat(countSlots(TENANT_A)).isEqualTo(1);

    inTxRun(() -> service.releaseRunSlot(TENANT_A, run));
    assertThat(countSlots(TENANT_A)).isZero();
    assertThat(inTx(() -> service.acquireRunSlot(TENANT_A, UUID.randomUUID()).allowed())).isTrue();
  }

  @Test
  @DisplayName("a duplicate acquisition for the same run never counts twice")
  void duplicate_acquisition_is_idempotent() {
    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 1, 1_000, new BigDecimal("100"));
    UUID run = UUID.randomUUID();

    assertThat(inTx(() -> service.acquireRunSlot(TENANT_A, run).allowed())).isTrue();
    // The same run re-acquiring (a duplicate delivery / an idempotent replay) is allowed, never
    // counted a second time.
    assertThat(inTx(() -> service.acquireRunSlot(TENANT_A, run).allowed())).isTrue();
    assertThat(countSlots(TENANT_A)).isEqualTo(1);
    // A different run is refused at the limit.
    assertThat(inTx(() -> service.acquireRunSlot(TENANT_A, UUID.randomUUID()).allowed())).isFalse();
    assertThat(countSlots(TENANT_A)).isEqualTo(1);
  }

  @Test
  @DisplayName("a tenant without a budget is unlimited but still tracked; tenants are isolated")
  void unlimited_tenant_is_tracked_and_isolated() {
    // TENANT_B has no budget row: never blocked.
    for (int i = 0; i < 5; i++) {
      assertThat(inTx(() -> service.acquireRunSlot(TENANT_B, UUID.randomUUID()).allowed()))
          .isTrue();
    }
    assertThat(countSlots(TENANT_B)).isEqualTo(5);

    // TENANT_A's quota is unaffected by TENANT_B's slots.
    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 1, 1_000, new BigDecimal("100"));
    assertThat(inTx(() -> service.acquireRunSlot(TENANT_A, UUID.randomUUID()).allowed())).isTrue();
    assertThat(countSlots(TENANT_A)).isEqualTo(1);
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

  private static long countSlots(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM tenant_run_slots WHERE tenant_id = ?", Long.class, tenant);
  }
}
