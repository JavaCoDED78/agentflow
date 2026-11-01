package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Load / latency benchmark for the polling outbox publisher (S06-07, S06-15), run against a <b>real
 * PostgreSQL</b> (Testcontainers) — never {@code H2}, per {@code AGENTS.md}.
 *
 * <p>The assertions are deliberately <b>structural</b>, not timing-based, so the suite is
 * deterministic on any machine: the empty poll is backed by the partial {@code
 * outbox_events_pending_idx} (never a full table scan), draining a batch delivers every row exactly
 * once, and the empty-poll cost stays flat while the table grows. The wall-clock numbers are
 * <b>printed</b> as {@code MEASUREMENT ...} lines for the benchmark note ({@code
 * docs/architecture/outbox-publisher-latency-load.md}); they are observations, not guarantees, and
 * vary with the environment (CPU, disk, Postgres version).
 *
 * <p>Three faces of the two operational costs the task names:
 *
 * <ul>
 *   <li><b>Poll load (steady state).</b> {@link
 *       #poll_load_is_backed_by_the_partial_index_not_a_full_scan()} times a zero-row {@link
 *       OutboxPublisherService#pollOnce()} and proves the due-scan uses the partial index. This is
 *       the query cost a fixed-interval poll pays once per interval; the queries-per-second at a
 *       given interval {@code T} is {@code 1/T}.
 *   <li><b>Poll load does not grow with table size.</b> {@link
 *       #empty_poll_cost_is_flat_regardless_of_total_table_size()} fills the table with
 *       already-processed rows and shows the empty poll still scans only the partial index, so the
 *       steady-state poll load is independent of how many rows are already {@code SENT}/{@code
 *       PUBLISHED}.
 *   <li><b>Publish (drain) latency.</b> {@link #drain_throughput_and_per_poll_latency()} seeds a
 *       batch of pending rows, drains them with real polls, and reports the per-batch drain time —
 *       the {@code publish_delay ≈ poll_interval + drain_time} contribution beyond the poll
 *       interval.
 * </ul>
 */
@Testcontainers
class OutboxPublisherBenchmarkIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static final int BATCH_SIZE = 50;
  private static final int DRAIN_ROWS = 500;
  private static final int FULL_TABLE_ROWS = 10_000;
  private static final int EMPTY_POLL_ITERATIONS = 200;

  private static final String COLUMNS =
      "id, event_id, tenant_id, aggregate_type, aggregate_id, topic, event_key, "
          + "payload, headers, status, publish_attempts, next_attempt_at, last_error, "
          + "created_at, published_at";

  private JdbcTemplate jdbc;
  private HikariDataSource ds;
  private OutboxEventRepository repository;
  private MessageDeliverer deliverer;
  private Map<UUID, Integer> deliveries;

  @BeforeEach
  void bootRealPostgresAndPublisher() {
    org.flywaydb.core.Flyway flyway =
        org.flywaydb.core.Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:migrations")
            .load();
    flyway.migrate();

    // A pooled DataSource (HikariCP), like the running app, so the per-statement cost reflects a
    // pooled checkout, not a fresh connection. Pool size is small on purpose: it does not change
    // the structural findings (index scan, N+1 outcome writes per poll), only their absolute cost.
    ds = new HikariDataSource();
    ds.setJdbcUrl(postgres.getJdbcUrl());
    ds.setUsername(postgres.getUsername());
    ds.setPassword(postgres.getPassword());
    ds.setPoolName("bench-pool");
    ds.setMaximumPoolSize(4);
    jdbc = new JdbcTemplate(ds);
    repository = new OutboxEventRepository(jdbc);
    jdbc.update(
        "INSERT INTO tenants (id, slug) VALUES (?, ?) ON CONFLICT (slug) DO NOTHING",
        UUID.randomUUID(),
        "tenant-bench");

    deliveries = new ConcurrentHashMap<>();
    deliverer =
        event -> {
          deliveries.merge(event.eventId(), 1, Integer::sum);
          return MessageDeliverer.DeliverResult.DELIVERED;
        };
  }

  @AfterEach
  void closePool() {
    if (ds != null) {
      ds.close();
    }
  }

  /** The default publisher tuning: 50-row batch, bounded retry. */
  private AgentFlowProperties props() {
    return new AgentFlowProperties();
  }

  @Test
  void poll_load_is_backed_by_the_partial_index_not_a_full_scan() {
    OutboxPublisherService publisher =
        new OutboxPublisherService(repository, deliverer, null, props());

    // Steady state: nothing is due. Each poll is one due-scan over the partial index.
    long start = System.nanoTime();
    for (int i = 0; i < EMPTY_POLL_ITERATIONS; i++) {
      assertThat(publisher.pollOnce()).as("an empty poll delivers nothing").isZero();
    }
    long elapsedNanos = System.nanoTime() - start;
    double elapsedMillis = elapsedNanos / 1_000_000.0;

    // The due-scan must use the partial index, never a full table scan (S06-07 invariant).
    String plan =
        explainSql(
            "EXPLAIN (ANALYZE, BUFFERS) SELECT "
                + COLUMNS
                + " FROM outbox_events WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now()) ORDER BY created_at ASC LIMIT "
                + BATCH_SIZE);
    assertThat(plan)
        .as("the due scan uses the partial outbox index, not a full table scan")
        .contains("outbox_events_pending_idx");
    assertThat(plan).doesNotContain("Seq Scan on outbox_events");

    double meanMillis = elapsedMillis / EMPTY_POLL_ITERATIONS;
    System.out.println(
        "MEASUREMENT poll_load: empty pollOnce() mean = "
            + round(meanMillis)
            + " ms over "
            + EMPTY_POLL_ITERATIONS
            + " polls ("
            + round(elapsedMillis)
            + " ms total); partial index outbox_events_pending_idx is used (no seq scan).");
    System.out.println(
        "MEASUREMENT poll_load: at a poll interval T (seconds), steady-state poll load = 1/T "
            + "due-scan queries/sec, each ~"
            + round(meanMillis)
            + " ms of index scan.");
  }

  @Test
  void empty_poll_cost_is_flat_regardless_of_total_table_size() {
    // Fill the table with already-processed rows so the pending set is empty but the table is not.
    UUID tenant = jdbc.queryForObject("SELECT id FROM tenants", UUID.class);
    long fillStart = System.nanoTime();
    for (int i = 0; i < FULL_TABLE_ROWS; i++) {
      UUID id = UUID.randomUUID();
      repository.insert(
          OutboxEvent.pending(
              id,
              id,
              "workflow.run.requested.v1",
              "agentflow.workflow.run-requested.v1",
              "workflow_run",
              id,
              tenant,
              "{\"n\":" + i + "}",
              null));
    }
    // Mark every seeded row SENT (delivered) so the pending set stays empty while the table grows.
    jdbc.update(
        "UPDATE outbox_events SET status = 'SENT', published_at = now() WHERE tenant_id = ?",
        tenant);
    double fillMillis = (System.nanoTime() - fillStart) / 1_000_000.0;

    OutboxPublisherService publisher =
        new OutboxPublisherService(repository, deliverer, null, props());
    long start = System.nanoTime();
    for (int i = 0; i < EMPTY_POLL_ITERATIONS; i++) {
      assertThat(publisher.pollOnce()).isZero();
    }
    double elapsedMillis = (System.nanoTime() - start) / 1_000_000.0;

    // Even with 10k processed rows, the pending scan stays an index scan and the poll is empty.
    String plan =
        explainSql(
            "EXPLAIN (ANALYZE, BUFFERS) SELECT "
                + COLUMNS
                + " FROM outbox_events WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now()) ORDER BY created_at ASC LIMIT "
                + BATCH_SIZE);
    assertThat(plan).contains("outbox_events_pending_idx");
    assertThat(plan).doesNotContain("Seq Scan on outbox_events");

    double meanMillis = elapsedMillis / EMPTY_POLL_ITERATIONS;
    System.out.println(
        "MEASUREMENT poll_load_table_growth: after inserting "
            + FULL_TABLE_ROWS
            + " processed rows ("
            + round(fillMillis)
            + " ms), the empty pollOnce() mean is still "
            + round(meanMillis)
            + " ms (partial index; no seq scan) — poll load is independent of total table size.");
  }

  @Test
  void drain_throughput_and_per_poll_latency() throws InterruptedException {
    UUID tenant = jdbc.queryForObject("SELECT id FROM tenants", UUID.class);
    for (int i = 0; i < DRAIN_ROWS; i++) {
      UUID id = UUID.randomUUID();
      repository.insert(
          OutboxEvent.pending(
              id,
              id,
              "workflow.run.requested.v1",
              "agentflow.workflow.run-requested.v1",
              "workflow_run",
              id,
              tenant,
              "{\"n\":" + i + "}",
              null));
    }

    OutboxPublisherService publisher =
        new OutboxPublisherService(repository, deliverer, null, props());

    long start = System.nanoTime();
    int polls = 0;
    int rows = 0;
    do {
      long pollStart = System.nanoTime();
      int published = publisher.pollOnce();
      double pollMillis = (System.nanoTime() - pollStart) / 1_000_000.0;
      polls++;
      rows += published;
      if (published > 0) {
        System.out.println(
            "MEASUREMENT drain_poll #"
                + polls
                + ": published "
                + published
                + " rows in "
                + round(pollMillis)
                + " ms");
      }
      // Safety stop in case a row were somehow stuck; the assertions below catch a real stall.
      if (polls > 1_000) {
        break;
      }
      Thread.sleep(1);
    } while (rows < DRAIN_ROWS);
    double drainMillis = (System.nanoTime() - start) / 1_000_000.0;

    // Every seeded row delivered exactly once — no loss, no duplicate (the S06-07 invariant).
    assertThat(deliveries).hasSize(DRAIN_ROWS);
    long total = 0;
    for (int count : deliveries.values()) {
      assertThat(count).as("each event delivered exactly once").isEqualTo(1);
      total += count;
    }
    assertThat(total).isEqualTo(DRAIN_ROWS);
    assertThat(repository.selectForClaim(1000)).isEmpty();

    double eps = DRAIN_ROWS / drainMillis * 1_000.0;
    double perBatchMillis = drainMillis / polls;
    System.out.println(
        "MEASUREMENT drain: "
            + DRAIN_ROWS
            + " rows drained in "
            + round(drainMillis)
            + " ms across "
            + polls
            + " polls ("
            + round(perBatchMillis)
            + " ms/poll incl. poll gap); throughput = "
            + round(eps)
            + " events/sec; publish_drain_latency ≈ poll_interval + "
            + round(perBatchMillis)
            + " ms/batch.");
  }

  private static double round(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  /**
   * Run {@code EXPLAIN} and return the plan as a single multi-line string. {@code EXPLAIN
   * (ANALYZE)} returns one row per plan node, so {@code queryForObject} (which expects exactly one
   * row) cannot be used here; the lines are joined to allow the index-name assertions below.
   */
  private String explainSql(String selectSql) {
    List<String> lines =
        jdbc.query(
            selectSql,
            new RowMapper<String>() {
              @Override
              public String mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
                return rs.getString(1);
              }
            });
    return String.join("\n", lines);
  }
}
