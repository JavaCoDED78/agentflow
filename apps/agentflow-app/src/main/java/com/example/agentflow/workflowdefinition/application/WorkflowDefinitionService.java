package com.example.agentflow.workflowdefinition.application;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Application service for workflow definitions (S03-13).
 *
 * <p>Owns the create / list / get use cases for workflow definitions. Creating builds a DRAFT
 * definition with the caller-supplied tenant (tenant isolation, invariant 1 — the tenant is passed
 * in explicitly, never read from the request) and records it through the tenant-scoped repository.
 * Listing is tenant-filtered in the SQL itself and paginated (S03-14); getting resolves a
 * definition by its {@code (tenant_id, id)} pair and reports a cross-tenant id as a 404 (the 404 /
 * 403 policy).
 *
 * <p>The {@link WorkflowDefinitionRepository} bean is only registered when a {@link
 * org.springframework.jdbc.core.JdbcTemplate} (hence a {@link javax.sql.DataSource}) is available
 * (see {@code WorkflowDefinitionRepository}); the dependency is therefore {@link Autowired
 * optional} so the application still boots without a database (S01-02).
 */
@Service
public class WorkflowDefinitionService {

  /**
   * Hard cap on how many rows a single {@link #listDefinitions} page may return (S03-14). Larger
   * requests are clamped, never trusted as-is, so a client cannot pull the whole collection in one
   * call.
   */
  private static final int MAX_PAGE_SIZE = 100;

  private final WorkflowDefinitionRepository repository;
  private final AuditService auditService;

  public WorkflowDefinitionService(
      @Autowired(required = false) WorkflowDefinitionRepository repository,
      AuditService auditService) {
    this.repository = repository;
    this.auditService = auditService;
  }

  /**
   * Creates a DRAFT workflow definition owned by {@code tenantId}.
   *
   * <p>The definition insert and its audit record (see {@link AuditService}) are written in one
   * transaction: a definition is never created without its audit trail, and a failed audit write
   * rolls the definition back with it (S04-12, invariant 3). A successful create records an {@link
   * AuditAction#CREATE} event for the {@link AuditResourceType#WORKFLOW_DEFINITION} with the
   * calling {@code actor} — so the mutation links to an actor without carrying any credential.
   *
   * @param tenantId the owning tenant, resolved from the trusted security context
   * @param actor the authenticated actor recorded as {@code created_by} and in the audit trail
   * @param name the unique, per-tenant definition name
   * @param description optional free-text description
   * @return the persisted definition
   * @throws ResponseStatusException 503 if no database is configured, or 409 if a definition with
   *     the same {@code name} already exists for this tenant (the {@code (tenant_id, name)} unique
   *     constraint, ADR-009)
   */
  @Transactional
  public WorkflowDefinition createDefinition(
      UUID tenantId, String actor, String name, String description) {
    if (repository == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "workflow definitions are unavailable: no database connection is configured");
    }
    WorkflowDefinition definition = WorkflowDefinition.draft(tenantId, name, description, actor);
    try {
      WorkflowDefinition inserted = repository.insert(definition);
      auditService.record(
          tenantId,
          actor,
          AuditAction.CREATE,
          AuditResourceType.WORKFLOW_DEFINITION,
          inserted.id());
      return inserted;
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "a workflow definition named '" + name + "' already exists for this tenant");
    }
  }

  /**
   * Lists a tenant's definitions as one page, ordered by {@code created_at DESC, id DESC} (the
   * total, predictable order the S03-14 criterion requires — {@code created_at} is backed by the
   * {@code (tenant_id, created_at)} index and {@code id} is the unique tie-breaker). The list is
   * tenant-filtered in the SQL (invariant 1), so another tenant's definitions never appear.
   *
   * @param tenantId the owning tenant, resolved from the trusted security context
   * @param page zero-based page index
   * @param requestedSize the requested page size; larger values are clamped to {@link
   *     #MAX_PAGE_SIZE}
   * @return the page of {@link WorkflowDefinitionDto} plus pagination metadata; the {@code size} in
   *     the result is the (possibly clamped) effective size
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 400} if {@code
   *     page < 0} or {@code requestedSize < 1} (a malformed pagination request, not a silent empty
   *     page)
   */
  public WorkflowDefinitionPageDto listDefinitions(UUID tenantId, int page, int requestedSize) {
    requireRepository();
    if (page < 0 || requestedSize < 1) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "pagination must use page >= 0 and size >= 1");
    }
    int size = Math.min(requestedSize, MAX_PAGE_SIZE);
    int offset = page * size;
    List<WorkflowDefinition> definitions = repository.listByTenant(tenantId, size, offset);
    long totalElements = repository.countByTenant(tenantId);
    int totalPages = (int) Math.ceil((double) totalElements / size);
    return new WorkflowDefinitionPageDto(
        definitions.stream().map(WorkflowDefinitionDto::from).toList(),
        page,
        size,
        totalElements,
        totalPages,
        page + 1 < totalPages,
        page > 0);
  }

  /**
   * Gets a single definition by its pair {@code (tenant_id, id)}. A definition that belongs to
   * another tenant is indistinguishable from a missing one: the {@code tenant_id} predicate is in
   * the repository's {@code WHERE} clause, so the lookup returns {@link Optional#empty()} for the
   * wrong tenant and this maps to a {@code 404} (the 404 / 403 policy, invariant 1).
   *
   * @param tenantId the owning tenant, resolved from the trusted security context
   * @param workflowId the definition id
   * @return the definition as read from a tenant-scoped lookup
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 404} if no
   *     definition matches {@code (tenant_id, workflowId)}
   */
  public WorkflowDefinition getDefinition(UUID tenantId, UUID workflowId) {
    requireRepository();
    return repository
        .findByTenantAndId(tenantId, workflowId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "workflow definition " + workflowId + " was not found for this tenant"));
  }

  /**
   * @return the repository, or a {@code 503} when no database is configured (see {@code
   *     createDefinition}).
   */
  private WorkflowDefinitionRepository requireRepository() {
    if (repository == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "workflow definitions are unavailable: no database connection is configured");
    }
    return repository;
  }
}
