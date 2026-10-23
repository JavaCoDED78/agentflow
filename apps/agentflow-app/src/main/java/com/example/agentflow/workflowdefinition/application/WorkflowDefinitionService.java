package com.example.agentflow.workflowdefinition.application;

import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Application service for workflow definitions (S03-13).
 *
 * <p>Owns the "create a DRAFT definition" use case: it builds the draft with the caller-supplied
 * tenant (tenant isolation, invariant 1 — the tenant is passed in explicitly, never read from the
 * request) and records it through the tenant-scoped repository.
 *
 * <p>The {@link WorkflowDefinitionRepository} bean is only registered when a {@link
 * org.springframework.jdbc.core.JdbcTemplate} (hence a {@link javax.sql.DataSource}) is available
 * (see {@code WorkflowDefinitionRepository}); the dependency is therefore {@link Autowired
 * optional} so the application still boots without a database (S01-02).
 */
@Service
public class WorkflowDefinitionService {

  private final WorkflowDefinitionRepository repository;

  public WorkflowDefinitionService(
      @Autowired(required = false) WorkflowDefinitionRepository repository) {
    this.repository = repository;
  }

  /**
   * Creates a DRAFT workflow definition owned by {@code tenantId}.
   *
   * @param tenantId the owning tenant, resolved from the trusted security context
   * @param actor the authenticated actor recorded as {@code created_by}
   * @param name the unique, per-tenant definition name
   * @param description optional free-text description
   * @return the persisted definition
   * @throws ResponseStatusException 503 if no database is configured, or 409 if a definition with
   *     the same {@code name} already exists for this tenant (the {@code (tenant_id, name)} unique
   *     constraint, ADR-009)
   */
  public WorkflowDefinition createDefinition(
      UUID tenantId, String actor, String name, String description) {
    if (repository == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "workflow definitions are unavailable: no database connection is configured");
    }
    WorkflowDefinition definition = WorkflowDefinition.draft(tenantId, name, description, actor);
    try {
      return repository.insert(definition);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "a workflow definition named '" + name + "' already exists for this tenant");
    }
  }
}
