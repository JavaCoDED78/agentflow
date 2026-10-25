package com.example.agentflow.audit.persistence;

import com.example.agentflow.audit.domain.AuditEvent;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Write path for the {@code audit_events} table, owned by the {@code audit} module.
 *
 * <p>This is the single inserter for the append-only audit trail: each {@link #insert} adds exactly
 * one row. The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * (S01-02); the {@link com.example.agentflow.audit.application.AuditService} fails loudly when the
 * repository is absent.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class AuditRepository {

  private static final String TABLE = "audit_events";

  private final JdbcTemplate jdbc;
  private final AuditRowMapper mapper = new AuditRowMapper();

  public AuditRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Append one audit record and return its assigned id. The row is tenant-scoped (invariant 1) and
   * the insert is the sole write path, so audit stays append-only — there is no update/delete API
   * for history.
   *
   * @param event the audit record to append
   * @return the persisted event id
   */
  public UUID insert(AuditEvent event) {
    UUID id = event.id();
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, tenant_id, actor_subject, action, resource_type, resource_id, outcome, correlation_id, metadata_redacted, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, event.tenantId());
          ps.setString(3, event.actorSubject());
          ps.setString(4, event.action().name());
          ps.setString(5, event.resourceType().name());
          ps.setObject(6, event.resourceId());
          ps.setString(7, event.outcome().name());
          ps.setObject(8, event.correlationId());
          ps.setString(9, event.metadataRedacted());
          ps.setObject(10, event.createdAt());
        });
    return id;
  }

  /**
   * Read an audit record by id. The {@code tenant_id} predicate is part of the {@code WHERE}
   * clause, so a cross-tenant id is indistinguishable from a missing one (the 403/404 policy,
   * invariant 1).
   *
   * @param tenantId the owning tenant
   * @param id the audit record id
   */
  public AuditEvent findByTenantAndId(UUID tenantId, UUID id) {
    return jdbc.queryForObject(
        "SELECT id, tenant_id, actor_subject, action, resource_type, resource_id, outcome, correlation_id, metadata_redacted, created_at "
            + "FROM "
            + TABLE
            + " WHERE id = ? AND tenant_id = ?",
        mapper,
        id,
        tenantId);
  }
}
