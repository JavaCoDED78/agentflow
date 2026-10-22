package com.example.agentflow.workflowdefinition.persistence;

import com.example.agentflow.workflowdefinition.domain.ConflictException;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped repository for the {@code workflow_definitions} table, owned by the {@code
 * workflowdefinition} module.
 *
 * <p>This repository demonstrates the Spring Data JDBC approach decided in ADR-009. It is the
 * single write path for workflow definitions: every read is tenant-filtered in the SQL itself
 * (invariant 1), every write is a conditional update whose zero-rows outcome is surfaced as a
 * conflict (invariant 4 / ADR-009 mapping table).
 *
 * <p>The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * in the profiles that have no datasource configured (S01-02).
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class WorkflowDefinitionRepository {

  private static final String TABLE = "workflow_definitions";

  private final JdbcTemplate jdbc;
  private final WorkflowDefinitionMapper mapper = new WorkflowDefinitionMapper();

  public WorkflowDefinitionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Insert a new workflow definition. The id is generated in the domain (see {@link
   * WorkflowDefinition#draft}), so it is a plain tenant-filtered insert; the unique {@code
   * (tenant_id, name)} constraint guards duplicate names (ADR-009). The definition is returned as
   * written.
   */
  public WorkflowDefinition insert(WorkflowDefinition definition) {
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, tenant_id, name, description, status, created_by) VALUES (?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, definition.id());
          ps.setObject(2, definition.tenantId());
          ps.setString(3, definition.name());
          ps.setString(4, definition.description());
          ps.setString(5, definition.status());
          ps.setString(6, definition.createdBy());
        });
    return definition;
  }

  /**
   * Read a definition by its pair {@code (tenant_id, id)}. A cross-tenant id is indistinguishable
   * from a missing one: the tenant predicate is part of the {@code WHERE} clause, so the query
   * returns {@code Optional.empty()} for the wrong tenant (invariant 1).
   */
  public Optional<WorkflowDefinition> findByTenantAndId(UUID tenantId, UUID id) {
    List<WorkflowDefinition> results =
        jdbc.query(
            "SELECT id, tenant_id, name, description, status, created_by, created_at "
                + "FROM "
                + TABLE
                + " WHERE id = ? AND tenant_id = ?",
            ps -> {
              ps.setObject(1, id);
              ps.setObject(2, tenantId);
            },
            mapper);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /**
   * Update a definition's mutable attributes in place, guarded by the current status so the call is
   * a conditional update (invariant 4). A zero-rows update — the row is gone, the tenant is wrong,
   * or the status no longer matches — is mapped to a conflict (409), as is an optimistic version or
   * lease mismatch that will be layered on with the same WHERE shape (S03-10).
   */
  public void updateMetadata(UUID tenantId, UUID id, String expectedStatus, String description) {
    int affected =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " SET description = ?, status = ?"
                + " WHERE id = ? AND tenant_id = ? AND status = ?",
            ps -> {
              ps.setString(1, description);
              ps.setString(2, expectedStatus);
              ps.setObject(3, id);
              ps.setObject(4, tenantId);
              ps.setString(5, expectedStatus);
            });
    if (affected == 0) {
      throw new ConflictException(
          "Workflow definition " + id + " is not in status '" + expectedStatus + "'");
    }
  }
}
