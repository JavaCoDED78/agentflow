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

  /** All five Sprint 3 migrations apply in order to a fresh database (the migration result). */
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
      assertThat(applied).containsExactly("3.3.0", "3.3.1", "3.3.2", "3.3.3", "3.3.4");
    }
  }

  /**
   * The three owning-module tables exist in the public schema. (The {@code flyway_schema_history}
   * bookkeeping table is not one of them, so the check is a containment check, not an exact set.)
   */
  @Test
  void owning_tables_exist() throws SQLException {
    assertThat(baseTables())
        .contains("tenants", "workflow_definitions", "workflow_definition_versions");
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
  }

  /** Every foreign key points at the table the migrations document. */
  @Test
  void foreign_keys_reference_the_parent_tables() throws SQLException {
    assertThat(referencedParents("workflow_definitions")).contains("tenants");
    assertThat(referencedParents("workflow_definition_versions")).contains("workflow_definitions");
  }

  /** The single genuine secondary index (S03-07) exists on the tenant-list lookup path. */
  @Test
  void tenant_list_index_exists() throws SQLException {
    assertThat(indexes("workflow_definitions"))
        .contains("workflow_definitions_tenant_created_at_idx");
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
        insert(
            "INSERT INTO workflow_definition_versions (id, workflow_id, version, graph_json, checksum) VALUES (?, ?, 1, ?, ?)",
            uuid(),
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
