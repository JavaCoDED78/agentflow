package com.example.agentflow.workflowdefinition.persistence;

import com.example.agentflow.workflowdefinition.domain.WorkflowVersionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence of {@code workflow_definition_versions} rows (glossary §10).
 *
 * <p>This repository is the <em>only</em> owner of the version table. Every method is tenant
 * scoped: the tenant filter is part of the SQL itself (invariant — tenant isolation), reached
 * through a join on {@code workflow_definitions.id = workflow_definition_versions.workflow_id}, so
 * another tenant's version is indistinguishable from a missing one. A version has no tenant of its
 * own; its tenant is inherited from its parent definition.
 *
 * <p>Write guards make the version lifecycle a single transition service:
 *
 * <ul>
 *   <li>{@link #create} only inserts when the parent {@code workflow_definitions} row exists for
 *       the tenant (a cross-tenant insert inserts zero rows);
 *   <li>{@link #activate} is a conditional update that transitions {@code DRAFT → ACTIVATED}
 *       <em>only</em> when the pinned {@code checksum} matches and the row is still a draft, so an
 *       activated version can never be changed again (the immutability guarantee).
 * </ul>
 *
 * <p>The bean is only registered when a {@link DataSource} exists (see {@link ConditionalOnBean}),
 * so the application still boots without a database in the profiles that have no datasource
 * configured (S01-02). The {@code workflowdefinition} application layer treats a {@code null} bean
 * as "no database" and answers {@code 503}.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class WorkflowVersionRepository {

  private static final String COLUMN_LIST =
      "v.id, v.workflow_id, v.version, v.graph_json, v.checksum, v.status, v.created_by, v.created_at";

  private final JdbcTemplate template;

  @Autowired
  public WorkflowVersionRepository(JdbcTemplate template) {
    this.template = template;
  }

  /**
   * Inserts a new {@link WorkflowVersionStatus#DRAFT} version of a definition (S05-12).
   *
   * <p>The insert is tenant scoped: it runs <em>only</em> when the parent {@code
   * workflow_definitions} row exists for {@code tenantId} (the {@code WHERE EXISTS} makes a
   * cross-tenant or missing definition insert zero rows). The {@code version} number is chosen by
   * the application layer ({@link #nextVersionNumber}); the {@code status} is always {@code DRAFT}
   * on creation; the {@code id} is generated here.
   *
   * @param tenantId the owning tenant (from the trusted security context)
   * @param workflowId the parent definition id; the row must exist for {@code tenantId}
   * @param version the next version number ({@code MAX(version) + 1} for the workflow)
   * @param graphJson the serialized, size-bounded graph
   * @param checksum the checksum pinned on creation (see {@link #activate})
   * @param createdBy the actor, e.g. an email
   * @return the created version, or {@link Optional#empty()} if the definition does not exist for
   *     the tenant (cross-tenant or deleted) — the application maps this to a conflict
   */
  @Transactional
  public Optional<WorkflowVersion> create(
      UUID tenantId,
      UUID workflowId,
      Integer version,
      String graphJson,
      String checksum,
      String createdBy) {
    final UUID id = UUID.randomUUID();
    int updated =
        template.update(
            "INSERT INTO workflow_definition_versions "
                + "(id, workflow_id, version, graph_json, checksum, status, created_by) "
                + "SELECT ?, ?, ?, ?, ?, ?, ? "
                + "WHERE EXISTS (SELECT 1 FROM workflow_definitions WHERE id = ? AND tenant_id = ?)",
            ps -> {
              ps.setObject(1, id);
              ps.setObject(2, workflowId);
              ps.setInt(3, version);
              ps.setObject(4, graphJson, java.sql.Types.OTHER);
              ps.setString(5, checksum);
              ps.setString(6, WorkflowVersionStatus.DRAFT);
              ps.setString(7, createdBy);
              ps.setObject(8, workflowId);
              ps.setObject(9, tenantId);
            });
    return updated == 0 ? Optional.empty() : get(tenantId, id);
  }

  /**
   * Activates a draft version, pinning it as immutable (S05-12).
   *
   * <p>A conditional update that transitions {@code DRAFT → ACTIVATED} <em>only</em> when the
   * pinned {@code checksum} matches and the row is still a draft, scoped to the tenant through the
   * parent. It never rewrites the graph, checksum, or version number — those are frozen at
   * creation. The {@code checksum} is required on the call so the guard rejects a tampered graph
   * before it is activated.
   *
   * @param tenantId the owning tenant
   * @param versionId the version id to activate
   * @param expectedChecksum the checksum pinned on creation; activation fails if it does not match
   * @return the number of rows changed: {@code 1} if the version is now {@link
   *     WorkflowVersionStatus#ACTIVATED}, {@code 0} if it was already activated, the checksum did
   *     not match, or it is not owned by the tenant
   */
  @Transactional
  public int activate(UUID tenantId, UUID versionId, String expectedChecksum) {
    return template.update(
        "UPDATE workflow_definition_versions v "
            + "SET status = ? "
            + "FROM workflow_definitions d "
            + "WHERE v.workflow_id = d.id "
            + "AND v.id = ? "
            + "AND d.tenant_id = ? "
            + "AND v.status = ? "
            + "AND v.checksum = ?",
        ps -> {
          ps.setString(1, WorkflowVersionStatus.ACTIVATED);
          ps.setObject(2, versionId);
          ps.setObject(3, tenantId);
          ps.setString(4, WorkflowVersionStatus.DRAFT);
          ps.setString(5, expectedChecksum);
        });
  }

  /**
   * Reads a version by id, tenant scoped through the parent definition (S05-12).
   *
   * @param tenantId the owning tenant
   * @param versionId the version id
   * @return the version, or {@link Optional#empty()} if it does not exist or is not owned by the
   *     tenant — the application maps an empty result to a 404
   */
  @Transactional(readOnly = true)
  public Optional<WorkflowVersion> get(UUID tenantId, UUID versionId) {
    List<WorkflowVersion> rows =
        template.query(
            "SELECT "
                + COLUMN_LIST
                + " FROM workflow_definition_versions v "
                + "JOIN workflow_definitions d ON v.workflow_id = d.id "
                + "WHERE v.id = ? AND d.tenant_id = ?",
            ps -> {
              ps.setObject(1, versionId);
              ps.setObject(2, tenantId);
            },
            new WorkflowVersionRowMapper());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * The next version number for a workflow, tenant scoped through the parent definition (S05-12):
   * {@code MAX(version) + 1}. The tenant is verified through the parent so a cross-tenant workflow
   * is never read.
   *
   * @param tenantId the owning tenant
   * @param workflowId the parent definition id
   * @return the next version number (starts at {@code 1} for an empty workflow)
   */
  @Transactional(readOnly = true)
  public Integer nextVersionNumber(UUID tenantId, UUID workflowId) {
    return template.queryForObject(
        "SELECT COALESCE(MAX(v.version), 0) + 1 "
            + "FROM workflow_definition_versions v "
            + "JOIN workflow_definitions d ON v.workflow_id = d.id "
            + "WHERE v.workflow_id = ? AND d.tenant_id = ?",
        Integer.class,
        workflowId,
        tenantId);
  }

  /**
   * The tenant's single active version of a workflow (S06-05), the version a new run is pinned to.
   *
   * <p>Verified tenant-scoped through the parent {@code workflow_definitions.tenant_id} (a cross
   * tenant, or a workflow with no activated version, is {@link Optional#empty()} &mdash; the
   * application maps both to a 404, so a run can never pin to a missing or foreign version,
   * invariant 1), and filtered to {@code ACTIVATED} so a run is created only against the immutable
   * execution contract, never a draft (invariant 13). When more than one version happens to be
   * activated, the highest version number wins.
   *
   * @param tenantId the owning tenant
   * @param workflowId the parent workflow id
   * @return the active version, or {@link Optional#empty()} if none is activated for the tenant
   */
  @Transactional(readOnly = true)
  public Optional<WorkflowVersion> findActiveVersion(UUID tenantId, UUID workflowId) {
    List<WorkflowVersion> rows =
        template.query(
            "SELECT "
                + COLUMN_LIST
                + " FROM workflow_definition_versions v "
                + "JOIN workflow_definitions d ON v.workflow_id = d.id "
                + "WHERE v.workflow_id = ? AND d.tenant_id = ? AND v.status = ? "
                + "ORDER BY v.version DESC NULLS LAST, v.created_at DESC NULLS LAST "
                + "LIMIT 1",
            ps -> {
              ps.setObject(1, workflowId);
              ps.setObject(2, tenantId);
              ps.setString(3, WorkflowVersionStatus.ACTIVATED);
            },
            new WorkflowVersionRowMapper());
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }
}
