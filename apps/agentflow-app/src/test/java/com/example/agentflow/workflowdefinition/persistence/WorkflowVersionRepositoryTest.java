package com.example.agentflow.workflowdefinition.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.workflowdefinition.domain.WorkflowVersionStatus;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

/**
 * Verifies the "mapping rules" of the version repository (S05-12): every version write/read is
 * tenant scoped in the SQL itself (invariant 1), the create insert only fires for a definition that
 * exists for the tenant (a cross-tenant insert is zero rows), and the activate transition is a
 * conditional {@code DRAFT → ACTIVATED} guarded by the pinned checksum (the immutability
 * guarantee). DB-free: the {@link JdbcTemplate} is a mock, so the SQL and bound arguments are
 * asserted directly.
 */
class WorkflowVersionRepositoryTest {

  private static final String COLUMN_LIST =
      "v.id, v.workflow_id, v.version, v.graph_json, v.checksum, v.status, v.created_by, v.created_at";

  private WorkflowVersion sample(UUID tenant, UUID workflow) {
    return new WorkflowVersion(
        UUID.randomUUID(),
        workflow,
        1,
        null,
        "checksum-value",
        WorkflowVersionStatus.DRAFT,
        "alice",
        Instant.now());
  }

  @Test
  @SuppressWarnings("unchecked")
  void create_binds_a_tenant_scoped_insert_and_returns_the_version() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    WorkflowVersion stored = sample(tenant, workflow);
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);

    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);
    when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenReturn(Collections.singletonList(stored));

    Optional<WorkflowVersion> created =
        repo.create(
            tenant, workflow, 1, "{\"schemaVersion\":\"1.0.0\"}", "checksum-value", "alice");

    verify(jdbc).update(sql.capture(), setter.capture());
    // the insert only runs for a definition that exists for the tenant: the tenant filter is in the
    // WHERE EXISTS, so a cross-tenant insert inserts zero rows (invariant 1)
    assertThat(sql.getValue())
        .contains("SELECT ?, ?, ?, ?, ?, ?, ?")
        .contains(
            "WHERE EXISTS (SELECT 1 FROM workflow_definitions WHERE id = ? AND tenant_id = ?)");
    setter.getValue().setValues(ps);
    // INSERT columns (id, workflow_id, version, graph_json, checksum, status, created_by)
    verify(ps).setInt(3, 1);
    verify(ps).setObject(4, "{\"schemaVersion\":\"1.0.0\"}", java.sql.Types.OTHER);
    verify(ps).setString(5, "checksum-value");
    verify(ps).setString(6, WorkflowVersionStatus.DRAFT);
    verify(ps).setString(7, "alice");
    // WHERE EXISTS (id, tenant_id) — the write is scoped to the writer's tenant
    verify(ps).setObject(8, workflow);
    verify(ps).setObject(9, tenant);
    assertThat(created).hasValue(stored);
  }

  @Test
  void create_zero_rows_returns_empty_for_a_cross_tenant_or_missing_definition() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

    Optional<WorkflowVersion> created =
        repo.create(tenant, workflow, 1, "graph", "checksum-value", "alice");

    assertThat(created).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void activate_scopes_to_tenant_and_is_guarded_by_status_and_checksum() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

    int changed = repo.activate(tenant, versionId, "checksum-value");

    verify(jdbc).update(sql.capture(), setter.capture());
    // only a draft with its pinned checksum is activated; the parent join scopes to the tenant, so
    // an already-activated, tampered or cross-tenant version affects zero rows (immutability)
    assertThat(sql.getValue())
        .contains("SET status = ?")
        .contains("FROM workflow_definitions d")
        .contains("WHERE v.workflow_id = d.id")
        .contains("AND v.id = ?")
        .contains("AND d.tenant_id = ?")
        .contains("AND v.status = ?")
        .contains("AND v.checksum = ?");
    setter.getValue().setValues(ps);
    verify(ps).setString(1, WorkflowVersionStatus.ACTIVATED);
    verify(ps).setObject(2, versionId);
    verify(ps).setObject(3, tenant);
    verify(ps).setString(4, WorkflowVersionStatus.DRAFT);
    verify(ps).setString(5, "checksum-value");
    assertThat(changed).isEqualTo(1);
  }

  @Test
  void activate_zero_rows_when_already_activated_or_checksum_mismatch() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

    int changed = repo.activate(tenant, versionId, "checksum-value");

    assertThat(changed).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("unchecked")
  void get_scopes_by_tenant_and_returns_the_version() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    WorkflowVersion stored = sample(tenant, UUID.randomUUID());
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenReturn(Collections.singletonList(stored));

    Optional<WorkflowVersion> found = repo.get(tenant, versionId);

    verify(jdbc).query(sql.capture(), setter.capture(), any(RowMapper.class));
    // the tenant predicate is part of the JOIN query, so another tenant's version is a 404
    assertThat(sql.getValue())
        .contains(COLUMN_LIST)
        .contains("JOIN workflow_definitions d ON v.workflow_id = d.id")
        .contains("WHERE v.id = ? AND d.tenant_id = ?");
    setter.getValue().setValues(ps);
    verify(ps).setObject(1, versionId);
    verify(ps).setObject(2, tenant);
    assertThat(found).hasValue(stored);
  }

  @Test
  void get_cross_tenant_returns_empty() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenReturn(Collections.emptyList());

    Optional<WorkflowVersion> found = repo.get(tenant, versionId);

    assertThat(found).isEmpty();
  }

  @Test
  void nextVersionNumber_returns_max_plus_one_tenant_scoped() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(UUID.class), any(UUID.class)))
        .thenReturn(2);

    Integer next = repo.nextVersionNumber(tenant, workflow);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForObject(sql.capture(), eq(Integer.class), any(UUID.class), any(UUID.class));
    assertThat(sql.getValue())
        .contains("COALESCE(MAX(v.version), 0) + 1")
        .contains("JOIN workflow_definitions d ON v.workflow_id = d.id")
        .contains("AND d.tenant_id = ?");
    assertThat(next).isEqualTo(2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void findActiveVersion_scopes_by_tenant_and_filters_to_the_active_version() throws SQLException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    WorkflowVersion stored = sample(tenant, workflow);
    PreparedStatement ps = mock(PreparedStatement.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<PreparedStatementSetter> setter =
        ArgumentCaptor.forClass(PreparedStatementSetter.class);
    when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenReturn(Collections.singletonList(stored));

    Optional<WorkflowVersion> found = repo.findActiveVersion(tenant, workflow);

    verify(jdbc).query(sql.capture(), setter.capture(), any(RowMapper.class));
    // scoped to the tenant through the parent join AND filtered to the single active version, so a
    // draft or a foreign version is never pinned (invariant 13 / invariant 1)
    assertThat(sql.getValue())
        .contains(COLUMN_LIST)
        .contains("JOIN workflow_definitions d ON v.workflow_id = d.id")
        .contains("AND d.tenant_id = ?")
        .contains("AND v.status = ?")
        .contains("ORDER BY v.version DESC NULLS LAST, v.created_at DESC NULLS LAST");
    setter.getValue().setValues(ps);
    verify(ps).setObject(1, workflow);
    verify(ps).setObject(2, tenant);
    verify(ps).setString(3, WorkflowVersionStatus.ACTIVATED);
    assertThat(found).hasValue(stored);
  }

  @Test
  void findActiveVersion_returns_empty_when_no_version_is_active() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    WorkflowVersionRepository repo = new WorkflowVersionRepository(jdbc);
    when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenReturn(Collections.emptyList());

    assertThat(repo.findActiveVersion(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
  }
}
