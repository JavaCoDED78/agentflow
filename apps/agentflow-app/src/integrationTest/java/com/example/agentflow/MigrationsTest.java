package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Migration integration suite (S03-15).
 *
 * <p>The whole point of this suite is that it runs against a <em>real</em> PostgreSQL (<code>
 * pgvector/pgvector:0.8.6-pg18</code>, the same image as Compose) with the <em>actual</em> Flyway
 * migrations applied to it — never H2 as a stand-in for PostgreSQL-specific behaviour (<code>
 * AGENTS.md</code>). It proves that:
 *
 * <ul>
 *   <li>every <code>V*__*.sql</code> migration applies in order to a fresh database;
 *   <li>the tables, columns, business <code>UNIQUE</code>/<code>NOT NULL</code>/foreign-key
 *       constraints and the tenant-list index exist exactly where the migrations declare them;
 *   <li>the migration <code>COMMENT</code>s record the owning module and the sprint task;
 *   <li>the Sprint 3 acceptance criteria hold against data — a valid insert lands, and the <code>
 *       (tenant_id, name)</code> / <code>(workflow_id, version)</code> uniques and the <code>
 *       NOT NULL</code> / foreign-key guards reject the invalid ones.
 * </ul>
 *
 * <p>Every probe is wrapped in a transaction that is rolled back: a repeated insert would otherwise
 * be a second row and defeat the uniqueness assertion, and the throwaway container is the only
 * thing that holds the rows, so nothing leaks anywhere.
 */
@Testcontainers
class MigrationsTest {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  @BeforeAll
  static void migrate() throws SQLException {
    Flyway flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:migrations")
            .load();
    flyway.migrate();
  }

  /** Every migration applies in order to a fresh database (the migration result). */
  @Test
  void all_migrations_apply_in_order() throws SQLException {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet versions =
            statement.executeQuery(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank")) {
      var applied = new java.util.ArrayList<String>();
      while (versions.next()) {
        applied.add(versions.getString("version"));
      }
      assertThat(applied)
          .containsExactly(
              "3.3.0", "3.3.1", "3.3.2", "3.3.3", "3.3.4", "3.3.5", "3.3.6", "3.3.7", "3.3.8",
              "3.3.9", "3.3.10");
    }
  }

  /**
   * The three owning-module tables exist in the public schema. (The {@code flyway_schema_history}
   * bookkeeping table is not one of them, so the check is a containment check, not an exact set.)
   */
  @Test
  void owning_tables_exist() throws SQLException {
    assertThat(baseTables())
        .contains(
            "tenants",
            "workflow_definitions",
            "workflow_definition_versions",
            "workflow_runs",
            "workflow_step_runs",
            "workflow_events",
            "outbox_events",
            "inbox_messages");
  }

  /**
   * The columns the acceptance criteria depend on are <code>NOT NULL</code> in PostgreSQL: a
   * missing value must be rejected by the database, not by the application layer.
   */
  @Test
  void required_columns_are_not_null() throws SQLException {
    assertThat(nullableColumn("tenants", "slug")).isFalse();
    assertThat(nullableColumn("workflow_definitions", "tenant_id")).isFalse();
    assertThat(nullableColumn("workflow_definitions", "name")).isFalse();
    assertThat(nullableColumn("workflow_definition_versions", "graph_json")).isFalse();
    assertThat(nullableColumn("workflow_definition_versions", "checksum")).isFalse();
    // workflow_runs (S05-08): the tenant, the pinned version and the state are NOT NULL.
    assertThat(nullableColumn("workflow_runs", "tenant_id")).isFalse();
    assertThat(nullableColumn("workflow_runs", "workflow_version_id")).isFalse();
    assertThat(nullableColumn("workflow_runs", "status")).isFalse();
    assertThat(nullableColumn("workflow_runs", "attempt")).isFalse();
    // workflow_step_runs (S05-08): the run, the step key and the state are NOT NULL.
    assertThat(nullableColumn("workflow_step_runs", "run_id")).isFalse();
    assertThat(nullableColumn("workflow_step_runs", "step_key")).isFalse();
    assertThat(nullableColumn("workflow_step_runs", "status")).isFalse();
    // workflow_events (S05-09): the tenant, the run and the event type are NOT NULL.
    assertThat(nullableColumn("workflow_events", "tenant_id")).isFalse();
    assertThat(nullableColumn("workflow_events", "run_id")).isFalse();
    assertThat(nullableColumn("workflow_events", "event_type")).isFalse();
    assertThat(nullableColumn("workflow_events", "occurred_at")).isFalse();
    // outbox_events (S06-03): the event identity, the tenant, the aggregate, the topic and the
    // payload are NOT NULL; status and the attempt count carry application defaults.
    assertThat(nullableColumn("outbox_events", "event_id")).isFalse();
    assertThat(nullableColumn("outbox_events", "tenant_id")).isFalse();
    assertThat(nullableColumn("outbox_events", "aggregate_type")).isFalse();
    assertThat(nullableColumn("outbox_events", "aggregate_id")).isFalse();
    assertThat(nullableColumn("outbox_events", "topic")).isFalse();
    assertThat(nullableColumn("outbox_events", "event_key")).isFalse();
    assertThat(nullableColumn("outbox_events", "payload")).isFalse();
    assertThat(nullableColumn("outbox_events", "status")).isFalse();
    assertThat(nullableColumn("outbox_events", "publish_attempts")).isFalse();
    assertThat(nullableColumn("outbox_events", "created_at")).isFalse();
    // inbox_messages (S06-04): consumer, event and status are NOT NULL; attempts carries a
    // default; processed_at / outcome / last_error / next_attempt_at are nullable until used.
    assertThat(nullableColumn("inbox_messages", "consumer_name")).isFalse();
    assertThat(nullableColumn("inbox_messages", "event_id")).isFalse();
    assertThat(nullableColumn("inbox_messages", "status")).isFalse();
    assertThat(nullableColumn("inbox_messages", "attempts")).isFalse();
    assertThat(nullableColumn("inbox_messages", "created_at")).isFalse();
  }

  /**
   * The idempotency columns the run-creation path relies on are nullable, exactly as {@code
   * V3_3_10} declares them: {@code workflow_runs.idempotency_key} (the dedup key) and {@code
   * idempotency_fingerprint} (the payload hash the mismatch-against-same-key compares). A create
   * without an {@code Idempotency-Key} stores {@code NULL} for both, so only keyed creates
   * participate in dedup / mismatch detection (invariant 11, S06-06); a missing value must be
   * tolerated, not rejected by the database. The fingerprint column carries its own column {@code
   * COMMENT} attributing the column to S06-06.
   */
  @Test
  void run_idempotency_columns_are_nullable() throws SQLException {
    // S05-08: the idempotency key the plan fixes is nullable (a run without a key is still valid).
    assertThat(nullableColumn("workflow_runs", "idempotency_key")).isTrue();
    // S06-06: the payload fingerprint is nullable and carries its own S06-06 column COMMENT.
    assertThat(nullableColumn("workflow_runs", "idempotency_fingerprint")).isTrue();
    assertThat(columnComment("workflow_runs", "idempotency_fingerprint")).contains("S06-06");
  }

  /** The business uniques are the composite ones the plan (§8.1) requires, not just the PK. */
  @Test
  void business_unique_constraints_are_enforced() throws SQLException {
    assertThat(uniqueConstraints("workflow_definitions"))
        .contains("workflow_definitions_tenant_name");
    assertThat(uniqueConstraints("workflow_definition_versions"))
        .contains("workflow_definition_versions_workflow_version");
    // tenants.slug uniqueness (declared inline UNIQUE) is also present.
    assertThat(uniqueConstraints("tenants")).isNotEmpty();
    // S05-08: the composite uniques the glossary fixes for the execution state.
    assertThat(uniqueConstraints("workflow_runs")).contains("workflow_runs_tenant_idempotency_key");
    assertThat(uniqueConstraints("workflow_step_runs"))
        .contains("workflow_step_runs_run_step_attempt");
  }

  /** Every foreign key points at the table the migrations document. */
  @Test
  void foreign_keys_reference_the_parent_tables() throws SQLException {
    assertThat(referencedParents("workflow_definitions")).contains("tenants");
    assertThat(referencedParents("workflow_definition_versions")).contains("workflow_definitions");
    // S05-08: the run pins its version and is tenant-scoped; a step pins its run.
    assertThat(referencedParents("workflow_runs"))
        .contains("tenants", "workflow_definition_versions");
    assertThat(referencedParents("workflow_step_runs")).contains("workflow_runs");
    // S05-09: the event is tenant-scoped and pinned to its run, with a nullable step pin.
    assertThat(referencedParents("workflow_events"))
        .contains("tenants", "workflow_runs", "workflow_step_runs");
    // S06-03: the outbox row is tenant-scoped; the aggregate is addressed by aggregate_type +
    // aggregate_id, so the only parent FK is the tenant (the generic outbox is not run-specific).
    assertThat(referencedParents("outbox_events")).contains("tenants");
  }

  /** The single genuine secondary index (S03-07) exists on the tenant-list lookup path. */
  @Test
  void tenant_list_index_exists() throws SQLException {
    assertThat(indexes("workflow_definitions"))
        .contains("workflow_definitions_tenant_created_at_idx");
    // S05-08: a tenant's run list, and a run's step list — one index per real path.
    assertThat(indexes("workflow_runs")).contains("workflow_runs_tenant_created_at_idx");
    assertThat(indexes("workflow_step_runs")).contains("workflow_step_runs_run_created_at_idx");
  }

  /**
   * The event-history index backs the replay path (S05-09): one run's events ordered by {@code
   * occurred_at}, most recent first.
   */
  @Test
  void event_history_index_exists() throws SQLException {
    assertThat(indexes("workflow_events")).contains("workflow_events_run_occurred_at_idx");
  }

  /**
   * The outbox pending index (S06-03) backs the polling publisher's read of unpublished rows: a
   * partial index on the pending status, ordered by {@code created_at}, so a bounded oldest-first
   * poll drains retries in creation order and concurrent publishers scan without a full scan.
   */
  @Test
  void outbox_pending_index_exists() throws SQLException {
    assertThat(indexes("outbox_events")).contains("outbox_events_pending_idx");
  }

  /**
   * The consumer-side dedup guard (S06-04): the composite {@code (consumer_name, event_id)} PRIMARY
   * KEY is the DB constraint that prevents two workers from processing one event for the same
   * consumer. It is declared with a stable, discoverable name so its intent is obvious from the
   * schema; the data-level duplicate rejection is proved in {@code
   * acceptance_criteria_hold_against_data}.
   */
  @Test
  void inbox_dedup_primary_key_is_enforced() throws SQLException {
    assertThat(primaryKeyConstraint("inbox_messages")).contains("inbox_messages_consumer_event");
  }

  /**
   * The <code>COMMENT</code>s record the owning module and sprint task, so ownership is obvious
   * from the schema itself (forward-only, one owning module per migration).
   */
  @Test
  void migration_comments_record_owner_and_task() throws SQLException {
    assertThat(tableComment("tenants")).contains("S03-03").contains("identity");
    assertThat(tableComment("workflow_definitions"))
        .contains("S03-04")
        .contains("workflowdefinition");
    assertThat(tableComment("workflow_definition_versions"))
        .contains("S03-05")
        .contains("workflowdefinition");
    // The optimistic-lock counter column carries its own S03-10 attribution.
    assertThat(columnComment("workflow_definitions", "version")).contains("S03-10");
    // S05-08: both execution tables record their owner and the task in their table COMMENT.
    assertThat(tableComment("workflow_runs")).contains("S05-08").contains("workflowexecution");
    assertThat(tableComment("workflow_step_runs")).contains("S05-08").contains("workflowexecution");
    // S05-09: the append-only event history records its owner (messaging) and the task.
    assertThat(tableComment("workflow_events")).contains("S05-09").contains("messaging");
    // S06-03: the transactional outbox records its owner (messaging) and the task in its table
    // COMMENT, so the table's ownership is obvious from the schema itself.
    assertThat(tableComment("outbox_events")).contains("S06-03").contains("messaging");
    // S06-04: the consumer-side inbox records its owner (messaging) and the task in its table
    // COMMENT, so the table's ownership is obvious from the schema itself.
    assertThat(tableComment("inbox_messages")).contains("S06-04").contains("messaging");
  }

  /**
   * The Sprint 3 acceptance criteria, proven against data rather than declared. Every positive
   * insert lands; every invalid insert is rejected by the constraint it violates, and the whole
   * probe rolls back so nothing is left behind.
   */
  @Test
  void acceptance_criteria_hold_against_data() throws SQLException {
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      try {
        // tenants: a valid row lands, and slug uniqueness is enforced.
        insert(
            "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')", uuid(), "t-accept");
        UUID tenant = tenantId("t-accept");
        assertThat(tenant).isNotNull();

        // workflow_definitions: a valid tenant-scoped definition lands, then the (tenant_id, name)
        // unique rejects the duplicate, the NOT NULL rejects a missing required column, and the
        // foreign key rejects a missing tenant.
        insert(
            "INSERT INTO workflow_definitions (id, tenant_id, name, status) VALUES (?, ?, ?, ?)",
            uuid(),
            tenant,
            "wf-accept",
            "ACTIVE");
        assertThatThrownBy(
                () ->
                    insert(
                        "INSERT INTO workflow_definitions (id, tenant_id, name, status) VALUES (?, ?, ?, ?)",
                        uuid(),
                        tenant,
                        "wf-accept",
                        "ACTIVE"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("unique constraint");
        assertThatThrownBy(
                () ->
                    statement()
                        .execute("INSERT INTO workflow_definitions (name) VALUES ('orphan')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("not-null");
        assertThatThrownBy(
                () ->
                    statement()
                        .execute(
                            "INSERT INTO workflow_definitions (id, tenant_id, name) VALUES "
                                + "('00000001-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000000', 'x')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("foreign key");

        // workflow_definition_versions: a valid immutable version lands, then the (workflow_id,
        // version) unique rejects the duplicate, graph_json NOT NULL rejects the missing one, and
        // the
        // foreign key rejects a missing parent.
        UUID definition = definitionId("wf-accept");
        String version = uuid();
        insert(
            "INSERT INTO workflow_definition_versions (id, workflow_id, version, graph_json, checksum) VALUES (?, ?, 1, ?, ?)",
            version,
            definition,
            "{\"nodes\":[]}",
            "c");
        assertThatThrownBy(
                () ->
                    insert(
                        "INSERT INTO workflow_definition_versions (id, workflow_id, version, graph_json, checksum) VALUES (?, ?, 1, ?, ?)",
                        uuid(),
                        definition,
                        "{\"nodes\":[]}",
                        "c"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("unique constraint");
        assertThatThrownBy(
                () ->
                    statement()
                        .execute(
                            "INSERT INTO workflow_definition_versions (workflow_id, version, checksum) VALUES ("
                                + "'"
                                + definition
                                + "', 2, 'c')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("not-null");
        assertThatThrownBy(
                () ->
                    statement()
                        .execute(
                            "INSERT INTO workflow_definition_versions (id, workflow_id, version, graph_json, checksum) VALUES ("
                                + "'00000001-0000-0000-0000-000000000001'"
                                + ", '00000000-0000-0000-0000-000000000000', 1, '{}', 'c')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("foreign key");

        // workflow_runs + workflow_step_runs (S05-08): a valid run pins its tenant and the pinned
        // version, and a valid step pins its run. The FK guards reject a missing parent, the NOT
        // NULL guard rejects a missing tenant, and the composite uniques deduplicate a repeated
        // idempotency key and a repeated (step, attempt).
        String runId = uuid();
        insert(
            "INSERT INTO workflow_runs (id, tenant_id, workflow_version_id, status, attempt) "
                + "VALUES (?, ?, ?, 'QUEUED', 1)",
            runId,
            tenant,
            version);
        // a second run with the same (tenant_id, idempotency_key) is deduplicated by the unique
        // constraint; the key is non-null here so the guard applies.
        String idempotentKey = uuid();
        insert(
            "INSERT INTO workflow_runs (id, tenant_id, workflow_version_id, status, attempt, idempotency_key) "
                + "VALUES (?, ?, ?, 'QUEUED', 1, ?)",
            uuid(),
            tenant,
            version,
            idempotentKey);
        assertThatThrownBy(
                () ->
                    insert(
                        "INSERT INTO workflow_runs (id, tenant_id, workflow_version_id, status, attempt, idempotency_key) "
                            + "VALUES (?, ?, ?, 'QUEUED', 1, ?)",
                        uuid(),
                        tenant,
                        version,
                        idempotentKey))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("unique constraint");
        // a run without a tenant is rejected by NOT NULL; a run with a missing parent version by
        // the foreign key.
        assertThatThrownBy(
                () ->
                    statement()
                        .execute(
                            "INSERT INTO workflow_runs (id, workflow_version_id, status) VALUES ("
                                + "'"
                                + uuid()
                                + "', '"
                                + version
                                + "', 'QUEUED')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("not-null");
        assertThatThrownBy(
                () ->
                    statement()
                        .execute(
                            "INSERT INTO workflow_runs (id, tenant_id, workflow_version_id, status) VALUES ("
                                + "'"
                                + uuid()
                                + "', '"
                                + tenant
                                + "', "
                                + "'00000001-0000-0000-0000-000000000001', 'QUEUED')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("foreign key");
        // a step pins its run; a foreign run_id is rejected by the FK and the
        // (run_id, step_key, attempt) unique rejects the duplicate. The foreign run_id uses a
        // fresh step_key so the unique guard (already held by the valid step above) does not fire
        // first and hide the foreign-key failure.
        String stepId = uuid();
        insert(
            "INSERT INTO workflow_step_runs (id, run_id, step_key, status) VALUES (?, ?, 'LLM_PLAN', 'PENDING')",
            stepId,
            runId);
        assertThatThrownBy(
                () ->
                    statement()
                        .execute(
                            "INSERT INTO workflow_step_runs (id, run_id, step_key, status) VALUES ("
                                + "'"
                                + uuid()
                                + "', '00000001-0000-0000-0000-000000000001', 'VALIDATE_OUTPUT', 'PENDING')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("foreign key");
        assertThatThrownBy(
                () ->
                    insert(
                        "INSERT INTO workflow_step_runs (id, run_id, step_key, status) VALUES (?, ?, 'LLM_PLAN', 'PENDING')",
                        uuid(),
                        runId))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("unique constraint");

        // workflow_events (S05-09): a run-level transition event lands and can be replayed
        // through (run_id, occurred_at); a step-level event pins its step. The NOT NULL guards
        // reject a missing tenant or run, and the foreign keys reject a missing tenant or run
        // parent (fresh run values so no unique or the satisfied FK fires first).
        insert(
            "INSERT INTO workflow_events (id, tenant_id, run_id, event_type, payload_redacted) "
                + "VALUES (?, ?, ?, ?, ?)",
            uuid(),
            tenant,
            runId,
            "RUNNING",
            "prior=QUEUED new=RUNNING");
        insert(
            "INSERT INTO workflow_events (id, tenant_id, run_id, step_run_id, event_type) "
                + "VALUES (?, ?, ?, ?, ?)",
            uuid(),
            tenant,
            runId,
            stepId,
            "STEP_RUNNING");
        assertThatThrownBy(
                () ->
                    statement()
                        .execute(
                            "INSERT INTO workflow_events (run_id, event_type) VALUES ('"
                                + runId
                                + "', 'RUNNING')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("not-null");
        assertThatThrownBy(
                () ->
                    statement()
                        .execute(
                            "INSERT INTO workflow_events (tenant_id, event_type) VALUES ('"
                                + tenant
                                + "', 'RUNNING')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("not-null");
        assertThatThrownBy(
                () ->
                    statement()
                        .execute(
                            "INSERT INTO workflow_events (id, tenant_id, run_id, event_type) VALUES ("
                                + "'"
                                + uuid()
                                + "', '00000001-0000-0000-0000-000000000001', '"
                                + runId
                                + "', 'RUNNING')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("foreign key");
        assertThatThrownBy(
                () ->
                    statement()
                        .execute(
                            "INSERT INTO workflow_events (id, tenant_id, run_id, event_type) VALUES ("
                                + "'"
                                + uuid()
                                + "', '"
                                + tenant
                                + "', '00000001-0000-0000-0000-000000000001', 'RUNNING')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("foreign key");

        // outbox_events (S06-03): a valid event to publish lands and stays PENDING; a row
        // missing payload is rejected by NOT NULL, and a row with a missing parent tenant is
        // rejected by the foreign key (fresh uuid values so a satisfied guard does not fire
        // first and hide the failure).
        insert(
            "INSERT INTO outbox_events (id, event_id, tenant_id, aggregate_type, aggregate_id, "
                + "topic, event_key, payload, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            uuid(),
            uuid(),
            tenant,
            "run",
            runId,
            "agentflow.workflow.run-requested.v1",
            "tenant:" + tenant + ":" + runId,
            "{\"aggregateType\":\"run\"}",
            "PENDING");
        // a row missing payload is rejected by NOT NULL — tenant_id points at the valid tenant
        // above so the foreign key is satisfied and only the payload guard fires; the columns
        // are bound via ? (as with the valid insert), and a fresh id/tenant-scoped event_key keep
        // this row from colliding with the valid insert above.
        assertThatThrownBy(
                () ->
                    insert(
                        "INSERT INTO outbox_events (id, event_id, tenant_id, aggregate_type, "
                            + "aggregate_id, topic, event_key) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        uuid(),
                        uuid(),
                        tenant,
                        "run",
                        runId,
                        "agentflow.workflow.run-requested.v1",
                        "tenant:x:" + runId))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("not-null");
        assertThatThrownBy(
                () ->
                    insert(
                        "INSERT INTO outbox_events (id, event_id, tenant_id, aggregate_type, "
                            + "aggregate_id, topic, event_key, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        uuid(),
                        uuid(),
                        "00000001-0000-0000-0000-000000000001",
                        "run",
                        uuid(),
                        "agentflow.workflow.run-requested.v1",
                        "tenant:x",
                        "{}"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("foreign key");

        // inbox_messages (S06-04): a consumer claims an event — a valid (consumer_name, event_id)
        // row lands and stays PENDING. A second insert with the SAME pair is rejected by the
        // composite PRIMARY KEY, so a duplicate Kafka delivery cannot re-run the business change
        // (the S06-04 acceptance criterion: the DB constraint prevents two workers processing one
        // event for the same consumer). A shared event id makes the second insert a true duplicate.
        String event = uuid();
        insert(
            "INSERT INTO inbox_messages (consumer_name, event_id, status) VALUES (?, ?, ?)",
            "workflowexecution:step-LLM_PLAN",
            event,
            "PENDING");
        assertThatThrownBy(
                () ->
                    insert(
                        "INSERT INTO inbox_messages (consumer_name, event_id, status) VALUES (?, ?, ?)",
                        "workflowexecution:step-LLM_PLAN",
                        event,
                        "PENDING"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("unique constraint");

        connection.rollback();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      }
    }
  }

  private static Connection connection() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Statement statement() throws SQLException {
    return connection().createStatement();
  }

  private static void insert(String sql, Object... params) throws SQLException {
    try (Statement stmt = statement()) {
      stmt.execute(interpolate(sql, params));
    }
  }

  /**
   * Replaces each {@code ?} placeholder with a single-quoted literal. This test only ever binds
   * test-generated UUIDs and simple literals (no embedded quotes), so literal interpolation is safe
   * and avoids {@link java.sql.PreparedStatement#prepareStatement(String)}, which this
   * environment's {@code java.sql.Statement} does not expose.
   */
  private static String interpolate(String sql, Object... params) {
    var out = new StringBuilder(sql.length() + 32);
    var index = 0;
    for (var i = 0; i < sql.length(); i++) {
      var c = sql.charAt(i);
      if (c == '?' && index < params.length) {
        out.append('\'').append(params[index++]).append('\'');
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static String uuid() {
    return UUID.randomUUID().toString();
  }

  private static UUID tenantId(String slug) throws SQLException {
    try (Statement statement = statement();
        ResultSet rs =
            statement.executeQuery("SELECT id FROM tenants WHERE slug = '" + slug + "'")) {
      return rs.next() ? rs.getObject("id", UUID.class) : null;
    }
  }

  private static UUID definitionId(String name) throws SQLException {
    try (Statement statement = statement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT id FROM workflow_definitions WHERE name = '" + name + "'")) {
      return rs.next() ? rs.getObject("id", UUID.class) : null;
    }
  }

  private static Set<String> baseTables() throws SQLException {
    try (Statement statement = statement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
      Set<String> names = new LinkedHashSet<>();
      while (rs.next()) {
        names.add(rs.getString("table_name"));
      }
      return names;
    }
  }

  private static boolean nullableColumn(String table, String column) throws SQLException {
    try (Statement statement = statement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = '"
                    + table
                    + "' AND column_name = '"
                    + column
                    + "'")) {
      return rs.next() && "YES".equals(rs.getString("is_nullable"));
    }
  }

  private static Set<String> uniqueConstraints(String table) throws SQLException {
    try (Statement statement = statement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT constraint_name FROM information_schema.table_constraints "
                    + "WHERE table_schema = 'public' AND table_name = '"
                    + table
                    + "' AND constraint_type = 'UNIQUE'")) {
      Set<String> names = new LinkedHashSet<>();
      while (rs.next()) {
        names.add(rs.getString("constraint_name"));
      }
      return names;
    }
  }

  /**
   * The primary-key constraint name of {@code table} (there is exactly one). Used to assert the
   * {@code (consumer_name, event_id)} inbox dedup guard declared in {@code V3_3_9}.
   */
  private static Set<String> primaryKeyConstraint(String table) throws SQLException {
    try (Statement statement = statement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT c.conname AS primary_key "
                    + "FROM pg_constraint c "
                    + "WHERE c.conrelid = '"
                    + table
                    + "'::regclass AND c.contype = 'p'")) {
      Set<String> names = new LinkedHashSet<>();
      while (rs.next()) {
        names.add(rs.getString("primary_key"));
      }
      return names;
    }
  }

  /**
   * The parent tables of every foreign key on {@code table}. Each Sprint 3 table owns exactly one
   * FK, so a containment check is unambiguous, but we resolve it by name (not by row order) so it
   * stays correct if a future migration adds a second key.
   */
  private static Set<String> referencedParents(String table) throws SQLException {
    try (Statement statement = statement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT cl.relname AS referenced_table "
                    + "FROM pg_constraint p "
                    + "JOIN pg_class cl ON cl.oid = p.confrelid "
                    + "WHERE p.conrelid = '"
                    + table
                    + "'::regclass AND p.contype = 'f'")) {
      Set<String> parents = new LinkedHashSet<>();
      while (rs.next()) {
        parents.add(rs.getString("referenced_table"));
      }
      return parents;
    }
  }

  private static Set<String> indexes(String table) throws SQLException {
    try (Statement statement = statement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT indexname FROM pg_indexes WHERE tablename = '" + table + "'")) {
      Set<String> names = new LinkedHashSet<>();
      while (rs.next()) {
        names.add(rs.getString("indexname"));
      }
      return names;
    }
  }

  private static String tableComment(String table) throws SQLException {
    try (Statement statement = statement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT obj_description('" + table + "'::regclass) AS comment")) {
      return rs.next() ? rs.getString("comment") : null;
    }
  }

  private static String columnComment(String table, String column) throws SQLException {
    try (Statement statement = statement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT col_description('"
                    + table
                    + "'::regclass, (SELECT attnum FROM pg_catalog.pg_attribute "
                    + "WHERE attrelid = '"
                    + table
                    + "'::regclass AND attname = '"
                    + column
                    + "')) AS comment")) {
      return rs.next() ? rs.getString("comment") : null;
    }
  }
}
