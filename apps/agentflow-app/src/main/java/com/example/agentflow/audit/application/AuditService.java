package com.example.agentflow.audit.application;

import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditEvent;
import com.example.agentflow.audit.domain.AuditOutcome;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.audit.persistence.AuditRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Application service that writes the immutable, append-only audit trail (glossary §10).
 *
 * <p>Owned by the {@code audit} module; the {@code workflowdefinition} and {@code toolexecution}
 * modules record their audited actions through this service (module-map edge 4 and the new {@code
 * workflowdefinition → audit} edge, ADR-010). Every audited action becomes exactly one {@link
 * AuditEvent} row.
 *
 * <p>The write is transactional with the business change: the caller runs inside a single Spring
 * transaction (for example {@code WorkflowDefinitionService#createDefinition} is
 * {@code @Transactional}), so the audit row and the state it describe commit together and roll back
 * together — audit can never diverge from the state it records (invariant 3 / `ADR-004`). This
 * service therefore has no transaction of its own; it joins the caller's ambient transaction.
 *
 * <p>The bean is always registered by Spring; the {@link AuditRepository} it depends on is only
 * present with a {@code DataSource}, so {@link #record} fails loudly (not a silent no-op) when no
 * database is configured. The caller already reports a {@code 503} for the missing database before
 * it reaches this path.
 */
@Service
public class AuditService {

  private final AuditRepository repository;

  public AuditService(@Autowired(required = false) AuditRepository repository) {
    this.repository = repository;
  }

  /**
   * Append one audit record for a definition mutation.
   *
   * <p>The {@code id}, {@link AuditOutcome#SUCCEEDED} outcome and {@code createdAt} instant are
   * assigned inside {@link AuditEvent#created}, so the caller only names the actor, the action and
   * the mutated resource. The record carries the actor (never a token) and the tenant, so a
   * mutation can be linked to who performed it without leaking credentials (S04-12).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param actorSubject the actor that performed the mutation (never a token)
   * @param action the audited verb ({@link AuditAction})
   * @param resourceType the entity that was mutated ({@link AuditResourceType})
   * @param resourceId the affected resource id (null when the action has no single resource)
   * @throws IllegalStateException if no database is configured (the caller reports {@code 503}
   *     first)
   */
  public void record(
      UUID tenantId,
      String actorSubject,
      AuditAction action,
      AuditResourceType resourceType,
      UUID resourceId) {
    if (repository == null) {
      throw new IllegalStateException(
          "audit events unavailable: no database connection is configured");
    }
    repository.insert(AuditEvent.created(tenantId, actorSubject, action, resourceType, resourceId));
  }
}
