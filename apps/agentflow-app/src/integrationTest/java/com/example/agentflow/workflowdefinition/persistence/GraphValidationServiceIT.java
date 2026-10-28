package com.example.agentflow.workflowdefinition.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.workflowdefinition.application.GraphValidationResult;
import com.example.agentflow.workflowdefinition.application.GraphValidationService;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL integration suite for the {@link GraphValidationService} validation use case (S05-13).
 *
 * <p>The DB-free tests ({@code GraphValidationServiceTest}, {@code
 * WorkflowDefinitionRepositoryTest}) assert the tenant / structural guards against mocked objects:
 * the service test stubs the repository and the repository test asserts the SQL as <em>text</em>
 * against a mocked {@link JdbcTemplate} &mdash; both prove the code <em>emits</em> a tenant-scoped
 * predicate, but a mocked template never <em>runs</em> the SQL. This suite runs the real SQL
 * against a real {@code pgvector/pgvector:0.8.6-pg18} (the same image as Compose, per {@code
 * AGENTS.md}: never {@code H2} as a PostgreSQL stand-in), proving that the database itself enforces
 * the guards. It covers the four things no other layer can:
 *
 * <ul>
 *   <li>a {@code validate} of a well-formed graph commits nothing &mdash; the endpoint is a
 *       read-only preview (S05-13), so the definition count is unchanged after the call;
 *   <li>a malformed graph returns the structured violation with its JSON path, produced by the real
 *       parser + domain gate over real rows;
 *   <li>a cross-tenant id is refused by the real {@code WHERE id = ? AND tenant_id = ?} as a {@code
 *       404} (invariant 1);
 *   <li>a missing id is also a {@code 404}.
 * </ul>
 *
 * <p>Every id is test-generated; each test is self-contained with no shared mutable state.
 */
@Testcontainers
class GraphValidationServiceIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateAndWire() {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:migrations")
        .load()
        .migrate();
    DataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(dataSource);
  }

  private static final String VALID_GRAPH =
      "{\"schemaVersion\":\"1.0.0\",\"nodes\":[{\"id\":\"finalize-1\",\"type\":\"FINALIZE\"}],\"edges\":[]}";

  private static final String INVALID_GRAPH =
      "{\"schemaVersion\":\"1.0.0\",\"nodes\":[{\"id\":\"s1\",\"type\":\"SCRIPT\"}]}";

  @Test
  void validate_a_valid_graph_returns_valid_and_writes_nothing() {
    UUID tenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);
    WorkflowDefinitionRepository repo = new WorkflowDefinitionRepository(jdbc);
    GraphValidationService service = new GraphValidationService(repo);

    GraphValidationResult result = service.validateGraph(tenant, definition, VALID_GRAPH);

    assertThat(result.valid()).isTrue();
    assertThat(result.violations()).isEmpty();
    // Read-only: a validate call must not create any definition row (invariant for S05-13).
    assertThat(repo.countByTenant(tenant)).isEqualTo(1);
  }

  @Test
  void validate_an_invalid_graph_returns_a_structured_violation() {
    UUID tenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);
    GraphValidationService service =
        new GraphValidationService(new WorkflowDefinitionRepository(jdbc));

    GraphValidationResult result = service.validateGraph(tenant, definition, INVALID_GRAPH);

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).hasSize(1);
    GraphValidationResult.Violation v = result.violations().get(0);
    assertThat(v.path()).isEqualTo("nodes[0]");
    assertThat(v.message()).isEqualTo("unknown workflow node type: SCRIPT");
  }

  @Test
  void validate_a_cross_tenant_definition_returns_not_found() {
    UUID tenant = UUID.randomUUID();
    UUID foreignTenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);

    // The tenant-scoped lookup runs on the real database: a foreign tenant's id is
    // indistinguishable
    // from a missing one (invariant 1) - the WHERE clause refuses it, so validation reports 404.
    GraphValidationService service =
        new GraphValidationService(new WorkflowDefinitionRepository(jdbc));

    assertThatThrownBy(() -> service.validateGraph(foreignTenant, definition, VALID_GRAPH))
        .extracting(GraphValidationServiceIT::statusOf)
        .isEqualTo(404);
  }

  @Test
  void validate_a_missing_definition_returns_not_found() {
    UUID tenant = UUID.randomUUID();
    UUID missing = UUID.randomUUID();

    GraphValidationService service =
        new GraphValidationService(new WorkflowDefinitionRepository(jdbc));

    assertThatThrownBy(() -> service.validateGraph(tenant, missing, VALID_GRAPH))
        .extracting(GraphValidationServiceIT::statusOf)
        .isEqualTo(404);
  }

  // --- helpers -------------------------------------------------------------

  private static void seedDefinition(UUID tenant, UUID definition) {
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, tenant);
          ps.setString(2, "tenant-" + tenant);
        });
    jdbc.update(
        "INSERT INTO workflow_definitions (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, definition);
          ps.setObject(2, tenant);
          ps.setString(3, "wf-" + definition);
        });
  }

  /**
   * @param t a {@code ResponseStatusException} wrapped by an assertion
   * @return the HTTP status code it carries, read without importing {@code HttpStatus}
   *     (spring-web's {@code HttpStatus} is not guaranteed on the integrationTest compile path,
   *     though {@code org.springframework.web.server.ResponseStatusException} is)
   */
  private static int statusOf(Throwable t) {
    return ((org.springframework.web.server.ResponseStatusException) t).getStatusCode().value();
  }
}
