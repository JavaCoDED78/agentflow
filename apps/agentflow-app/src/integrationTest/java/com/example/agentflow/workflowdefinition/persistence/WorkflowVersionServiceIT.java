package com.example.agentflow.workflowdefinition.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.persistence.AuditRepository;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionDto;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowVersionStatus;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL integration suite for the {@link WorkflowVersionService#activateVersion} use case
 * (S05-14).
 *
 * <p>The DB-free tests ({@code WorkflowVersionServiceTest}) assert the activation logic against a
 * mocked repository &mdash; they prove the code <em>emits</em> the idempotent / guard transitions,
 * but a mocked repository never <em>runs</em> SQL nor <em>commits</em> the audit row. This suite
 * runs the real service against a real {@code pgvector/pgvector:0.8.6-pg18} (the same image as
 * Compose, per {@code AGENTS.md}: never {@code H2} as a PostgreSQL stand-in), proving what the real
 * database and the real transaction do. It covers the four things no other layer can:
 *
 * <ul>
 *   <li>activation commits the {@code DRAFT → ACTIVATED} transition <b>and</b> appends exactly one
 *       {@code ACTIVATE} audit row in the same call (atomic activation/audit, invariant 3);
 *   <li>a repeat is <b>idempotent</b>: the second call returns the same immutable version without a
 *       exception and without a second {@code ACTIVATE} audit row (S05-14 &mdash; a client that
 *       retries after a lost response gets a clean success, never a conflict and never a duplicate
 *       audit entry);
 *   <li>a cross-tenant version is refused by the real tenant-scoped lookup as a {@code 404},
 *       indistinguishable from a missing one (invariant 1);
 *   <li>a version addressed under the wrong parent workflow is a {@code 404} under that workflow
 *       (the resource hierarchy the endpoint enforces).
 * </ul>
 *
 * <p>Every id is test-generated; each test is self-contained with no shared mutable state. The
 * service, repositories and audit sink are constructed manually over the test {@link JdbcTemplate},
 * exactly as {@code GraphValidationServiceIT} does.
 */
@Testcontainers
class WorkflowVersionServiceIT {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;

  private static final String VALID_GRAPH =
      "{\"schemaVersion\":\"1.0.0\",\"nodes\":[{\"id\":\"n1\",\"type\":\"FINALIZE\"}],\"edges\":[]}";

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

  private static WorkflowVersionService service() {
    return new WorkflowVersionService(
        new WorkflowDefinitionRepository(jdbc),
        new WorkflowVersionRepository(jdbc),
        new AuditService(new AuditRepository(jdbc)),
        new AgentFlowProperties());
  }

  @Test
  void activate_a_draft_transitions_and_audits_once_atomically() {
    UUID tenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);
    WorkflowVersionService service = service();

    WorkflowVersionDto draft = service.createVersion(tenant, definition, "alice", VALID_GRAPH);
    assertThat(draft.status()).isEqualTo("DRAFT");

    WorkflowVersionDto activated = service.activateVersion(tenant, definition, draft.id(), "alice");

    // the draft is now immutable ...
    assertThat(activated.status()).isEqualTo(WorkflowVersionStatus.ACTIVATED);
    // ... and exactly one ACTIVATE audit row exists for it (the transition and the audit commit in
    // the same transaction, invariant 3).
    assertThat(activateAuditCount(tenant, draft.id())).isEqualTo(1);
  }

  @Test
  void re_activation_is_idempotent_without_a_second_audit_row() {
    UUID tenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);
    WorkflowVersionService service = service();

    WorkflowVersionDto draft = service.createVersion(tenant, definition, "alice", VALID_GRAPH);
    service.activateVersion(tenant, definition, draft.id(), "alice");
    long afterFirst = activateAuditCount(tenant, draft.id());

    // A second activation of the same immutable version is a no-op: it returns that same version
    // without error and records no second audit row.
    WorkflowVersionDto again = service.activateVersion(tenant, definition, draft.id(), "alice");

    assertThat(again.status()).isEqualTo(WorkflowVersionStatus.ACTIVATED);
    assertThat(again.id()).isEqualTo(draft.id());
    assertThat(afterFirst).isEqualTo(1);
    assertThat(activateAuditCount(tenant, draft.id())).isEqualTo(1);
  }

  @Test
  void activate_a_cross_tenant_version_returns_not_found() {
    UUID tenant = UUID.randomUUID();
    UUID foreignTenant = UUID.randomUUID();
    UUID definition = UUID.randomUUID();
    seedDefinition(tenant, definition);

    WorkflowVersionDto draft = service().createVersion(tenant, definition, "alice", VALID_GRAPH);

    // The tenant-scoped lookup runs on the real database: a foreign tenant's version is
    // indistinguishable from a missing one (invariant 1) - the WHERE clause refuses it, so the
    // activation reports 404.
    assertThatThrownBy(
            () -> service().activateVersion(foreignTenant, definition, draft.id(), "alice"))
        .extracting(WorkflowVersionServiceIT::statusOf)
        .isEqualTo(404);
  }

  @Test
  void activate_a_version_out_of_its_workflow_returns_not_found() {
    UUID tenant = UUID.randomUUID();
    // one tenant, two definitions under it; a version of definitionA cannot be activated under
    // definitionB
    seedTenant(tenant);
    UUID definitionA = insertWorkflow(tenant, UUID.randomUUID());
    UUID definitionB = insertWorkflow(tenant, UUID.randomUUID());

    WorkflowVersionDto draft = service().createVersion(tenant, definitionA, "alice", VALID_GRAPH);

    // The version exists for the tenant, but it belongs to definitionA, not definitionB. The
    // endpoint addresses a version through its parent workflow, so under definitionB it is a 404
    // (the resource hierarchy), never a silent activation of the wrong version.
    assertThatThrownBy(() -> service().activateVersion(tenant, definitionB, draft.id(), "alice"))
        .extracting(WorkflowVersionServiceIT::statusOf)
        .isEqualTo(404);
  }

  // --- helpers -------------------------------------------------------------

  /**
   * Inserts the tenant row only; the workflow(s) under it are inserted by {@link #insertWorkflow}.
   */
  private static void seedTenant(UUID tenant) {
    jdbc.update(
        "INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, tenant);
          ps.setString(2, "tenant-" + tenant);
        });
  }

  /** Inserts a single workflow-definition row under {@code tenant} and returns its id. */
  private static void seedDefinition(UUID tenant, UUID definition) {
    seedTenant(tenant);
    insertWorkflow(tenant, definition);
  }

  /** Inserts a single workflow-definition row under {@code tenant} and returns its id. */
  private static UUID insertWorkflow(UUID tenant, UUID definition) {
    jdbc.update(
        "INSERT INTO workflow_definitions (id, tenant_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
        ps -> {
          ps.setObject(1, definition);
          ps.setObject(2, tenant);
          ps.setString(3, "wf-" + definition);
        });
    return definition;
  }

  /**
   * The number of {@code ACTIVATE} audit rows already recorded for a version (its {@code
   * resource_id}).
   */
  private static long activateAuditCount(UUID tenant, UUID resourceId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND resource_id = ? AND action = ?",
        Long.class,
        tenant,
        resourceId,
        "ACTIVATE");
  }

  /**
   * @param t a {@code ResponseStatusException} wrapped by an assertion
   * @return the HTTP status code it carries, read without importing {@code HttpStatus}
   *     (spring-web's {@code HttpStatus} is not guaranteed on the integrationTest compile path,
   *     though {@code org.springframework.web.server.ResponseStatusException} is)
   */
  private static int statusOf(Throwable t) {
    return ((ResponseStatusException) t).getStatusCode().value();
  }
}
