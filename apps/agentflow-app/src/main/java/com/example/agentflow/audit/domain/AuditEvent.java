package com.example.agentflow.audit.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable, append-only audit record (glossary §10, `audit_events`).
 *
 * <p>One row per audited action. The record ties a mutation to the {@code actorSubject} that
 * performed it and the {@code tenantId} it belongs to, without carrying any credential or secret:
 * there is no token field, and {@code metadataRedacted} holds redacted (never raw) free text. The
 * {@code id}, {@code outcome} ({@link AuditOutcome#SUCCEEDED}) and {@code createdAt} instant are
 * assigned by {@link #created}; {@code correlationId} and {@code metadataRedacted} stay {@code
 * null} until a later task threads them from the trace.
 *
 * <p>Owned by the {@code audit} module; {@link
 * com.example.agentflow.audit.application.AuditService} is the only writer, and the repository maps
 * this record to the single {@code audit_events} row. It is a public value type: the {@code
 * workflowdefinition} module builds it (via the service) and crosses the module boundary, so it
 * lives in the public {@code domain} package.
 */
public record AuditEvent(
    UUID id,
    UUID tenantId,
    String actorSubject,
    AuditAction action,
    AuditResourceType resourceType,
    UUID resourceId,
    AuditOutcome outcome,
    UUID correlationId,
    String metadataRedacted,
    Instant createdAt) {

  /**
   * Build a successful audit record for a definition mutation. The {@code id}, {@link
   * AuditOutcome#SUCCEEDED} outcome and {@code createdAt} instant are assigned here; {@code
   * correlationId} and {@code metadataRedacted} remain {@code null} until a later task threads
   * them.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param actorSubject the actor that performed the mutation (never a token)
   * @param action the audited verb ({@link AuditAction})
   * @param resourceType the entity that was mutated ({@link AuditResourceType})
   * @param resourceId the affected resource id (null when the action has no single resource)
   * @return the audit record ready to persist
   */
  public static AuditEvent created(
      UUID tenantId,
      String actorSubject,
      AuditAction action,
      AuditResourceType resourceType,
      UUID resourceId) {
    return new AuditEvent(
        UUID.randomUUID(),
        tenantId,
        actorSubject,
        action,
        resourceType,
        resourceId,
        AuditOutcome.SUCCEEDED,
        null,
        null,
        Instant.now());
  }
}
