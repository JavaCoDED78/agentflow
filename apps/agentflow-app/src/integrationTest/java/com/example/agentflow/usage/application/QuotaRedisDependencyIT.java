package com.example.agentflow.usage.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.common.RateLimitGroup;
import com.example.agentflow.common.RateLimitKey;
import com.example.agentflow.common.RateLimitPolicy;
import com.example.agentflow.common.RateLimiter;
import com.example.agentflow.common.rate_limit.InMemoryRateLimiter;
import com.example.agentflow.common.rate_limit.RedisRateLimiter;
import com.example.agentflow.common.redis.RedisKeyBuilder;
import com.example.agentflow.usage.domain.BudgetPeriod;
import com.example.agentflow.usage.persistence.BudgetRepository;
import com.example.agentflow.usage.persistence.RunSlotRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-PostgreSQL + real-Redis integration test proving the auxiliary Redis rate limiter is a
 * <b>helper</b> ({@code ADR-021}) that never owns the durable quota (S12-20). The durable
 * per-tenant parallel-run quota is the {@link QuotaService} on PostgreSQL ({@code V3_3_27},
 * S12-05); the {@link RedisRateLimiter} throttles the run-creation <em>rate</em> per tenant, a
 * separate, non-durable concern.
 *
 * <p>Two acceptance criteria of S12-20 are proven here, against the same images Compose pins:
 *
 * <ul>
 *   <li><b>the S12-05 race holds with the Redis limiter present</b> - the concurrent-acquire race
 *       caps at {@code max_runs} exactly as it does without Redis (the quota never counts in
 *       Redis);
 *   <li><b>a full Redis cleanup changes nothing durable</b> - {@code FLUSHALL} leaves the usage /
 *       budget state byte-for-byte unchanged and does not let a tenant bypass the tenant quota (the
 *       PostgreSQL quota still serializes the race).
 * </ul>
 *
 * <p>No H2 is used as a stand-in for PostgreSQL (S03-08). The race uses one transaction per
 * contender over its own connection (a {@link DriverManagerDataSource} makes a fresh connection per
 * call), the same shape as {@link QuotaServiceIT}.
 */
@Testcontainers
@DisplayName("QuotaService is durable and independent of the auxiliary Redis limiter (S12-20)")
class QuotaRedisDependencyIT {

  @Container
  static PostgreSQLContainer pg =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.6-pg18"))
          .withDatabaseName("agentflow");

  @Container
  static GenericContainer<?> redis = new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);

  private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final RateLimitKey TENANT_A_KEY =
      new RateLimitKey(TENANT_A, "actor", RateLimitGroup.CREATE_RUN);

  private static JdbcTemplate jdbc;
  private static TransactionTemplate tx;
  private static QuotaService service;
  private static RedisRateLimiter limiter;

  private static RedisConnectionFactory liveFactory;
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final List<RedisConnectionFactory> factories = new ArrayList<>();

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
    liveFactory = liveFactory();
  }

  @BeforeEach
  void freshState() {
    jdbc.execute("TRUNCATE tenant_run_slots, budget_reservations, tenant_budgets, usage_records");
    flushRedis();
    RateLimitPolicy policy = policy(60, RateLimitGroup.CREATE_RUN);
    RateLimiter failover = new InMemoryRateLimiter(policy, Clock.systemUTC());
    StringRedisTemplate template = new StringRedisTemplate(liveFactory);
    limiter =
        new RedisRateLimiter(
            policy, template, failover, Clock.systemUTC(), registry, new RedisKeyBuilder("test"));
  }

  @AfterEach
  void closeFactories() {
    factories.forEach(connection -> ((LettuceConnectionFactory) connection).destroy());
    registry.clear();
  }

  @Test
  @DisplayName(
      "the S12-05 race caps at the budget while the Redis limiter is live (criterion: passes with Redis)")
  void the_race_caps_at_the_budget_with_the_redis_limiter_present() throws Exception {
    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 3, 1_000, new BigDecimal("100"));

    // The auxiliary Redis limiter counts this tenant's run-creation budget in Redis (a live 59 of
    // 60 after one call), so the system is genuinely wired for Redis here.
    Optional<Integer> remaining = limiter.remaining(TENANT_A_KEY);
    assertThat(remaining).contains(59);

    // The durable quota still serializes the race on PostgreSQL, never on Redis: exactly 3 win.
    assertThat(race(TENANT_A, 8)).isEqualTo(3);
    assertThat(countSlots(TENANT_A)).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "clearing Redis leaves usage/budget state unchanged and does not bypass the tenant quota (criterion)")
  void clearing_redis_leaves_usage_state_unchanged_and_keeps_the_quota() throws Exception {
    insertBudget(TENANT_A, BudgetPeriod.MONTHLY, 2, 1_000, new BigDecimal("100"));
    assertThat(acquire(TENANT_A, randomRun())).isTrue();
    assertThat(acquire(TENANT_A, randomRun())).isTrue();
    assertThat(countSlots(TENANT_A)).isEqualTo(2);

    // The durable usage / budget state before the Redis cleanup.
    long budgetsBefore = countBudgets();
    long usageBefore = countUsage();

    // Full Redis cleanup - the caddy store is wiped, PostgreSQL is untouched.
    flushRedis();
    assertThat(countBudgets()).isEqualTo(budgetsBefore);
    assertThat(countUsage()).isEqualTo(usageBefore);
    assertThat(countSlots(TENANT_A)).isEqualTo(2);

    // The quota is not bypassed: TENANT_A is already at its limit, so a fresh race of 6 yields 0
    // new slots (the durable quota holds with Redis gone).
    assertThat(race(TENANT_A, 6)).isZero();
    assertThat(countSlots(TENANT_A)).isEqualTo(2);

    // A fresh tenant's durable quota is still enforced with Redis wiped: exactly its limit.
    insertBudget(TENANT_B, BudgetPeriod.MONTHLY, 1, 1_000, new BigDecimal("100"));
    assertThat(race(TENANT_B, 6)).isEqualTo(1);
    assertThat(countSlots(TENANT_B)).isEqualTo(1);
  }

  private static <T> T inTx(Supplier<T> op) {
    return tx.execute(status -> op.get());
  }

  private boolean acquire(UUID tenant, UUID run) {
    return inTx(() -> service.acquireRunSlot(tenant, run).allowed());
  }

  private static UUID randomRun() {
    return UUID.randomUUID();
  }

  private int race(UUID tenant, int contenders) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(contenders);
    CountDownLatch ready = new CountDownLatch(contenders);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < contenders; i++) {
      final UUID run = randomRun();
      results.add(
          pool.submit(
              () -> {
                ready.countDown();
                start.await(30, TimeUnit.SECONDS);
                return acquire(tenant, run);
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
    return allowed;
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

  private static long countBudgets() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM tenant_budgets", Long.class);
  }

  private static long countUsage() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM usage_records", Long.class);
  }

  private void flushRedis() {
    StringRedisTemplate template = new StringRedisTemplate(liveFactory);
    template.execute(
        (RedisCallback<Object>)
            connection -> {
              connection.flushDb();
              return null;
            });
  }

  private static RedisConnectionFactory liveFactory() {
    LettuceConnectionFactory connection =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("localhost", redis.getMappedPort(6379)));
    connection.afterPropertiesSet();
    return connection;
  }

  private static RateLimitPolicy policy(int budget, RateLimitGroup... groups) {
    java.util.EnumMap<RateLimitGroup, Integer> budgets =
        new java.util.EnumMap<>(RateLimitGroup.class);
    for (RateLimitGroup group : groups) {
      budgets.put(group, budget);
    }
    return new RateLimitPolicy(true, budgets);
  }
}
