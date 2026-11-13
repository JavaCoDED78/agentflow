package com.example.agentflow.knowledgebase.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * DB-free tests for the {@code documents} write path ({@link DocumentRepository}), asserting the
 * SQL and the bound arguments directly (the mocked {@link JdbcTemplate} convention, same as {@code
 * workflowexecution} repository tests). The real conditional updates run over a real PostgreSQL row
 * in a Testcontainers integration test — a documented follow-up: see AGENTS.md §Tests.
 *
 * <p>These pin the {@code S10-19} facts: the duplicate lookup is tenant-scoped and keyed on {@code
 * (tenant_id, sha256)}; the manual retry is guarded by {@code status = 'FAILED'}; the indexing
 * completion records the {@code pipeline_version} (the reindex trigger); and the conditional claim
 * is guarded by {@code status IN (...)} and the tenant predicate (invariants 1/4).
 */
class DocumentRepositoryTest {

  private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555566667777");
  private static final UUID DOC = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID KB = UUID.fromString("ffffffff-0000-1111-2222-333344445555");

  private static final DocumentUploadStatus UPLOADED = DocumentUploadStatus.UPLOADED;
  private static final DocumentUploadStatus QUEUED = DocumentUploadStatus.QUEUED;
  private static final DocumentUploadStatus INDEXED = DocumentUploadStatus.INDEXED;

  @Test
  @SuppressWarnings("unchecked")
  void mark_indexed_records_the_pipeline_version_and_page_count_guarded_by_processing() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DocumentRepository repo = new DocumentRepository(jdbc);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThat(repo.markIndexed(TENANT, DOC, 4, "v2")).isTrue();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(sql.capture(), args.capture());

    String query = sql.getValue();
    assertThat(query).contains("SET status = 'INDEXED'");
    assertThat(query).contains("version = version + 1");
    assertThat(query).contains("page_count = COALESCE(?, page_count)");
    assertThat(query).contains("pipeline_version = ?");
    assertThat(query).contains("WHERE tenant_id = ? AND id = ? AND status = 'PROCESSING'");

    Object[] bound = args.getValue();
    assertThat(bound[0]).isEqualTo(4);
    assertThat(bound[1]).isEqualTo("v2");
    assertThat(bound[2]).isEqualTo(TENANT);
    assertThat(bound[3]).isEqualTo(DOC);
  }

  /**
   * The conditional claim is a single guarded update out of the claimable states — exactly one
   * worker moves the row (invariant 4), and the states are bound as enum names (never caller-typed
   * SQL). The tenant predicate is part of the WHERE (invariant 1).
   */
  @Test
  @SuppressWarnings("unchecked")
  void claim_for_indexing_updates_processings_for_the_claimable_states() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DocumentRepository repo = new DocumentRepository(jdbc);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThat(repo.claimForIndexing(TENANT, DOC, Set.of(UPLOADED, QUEUED))).isTrue();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(sql.capture(), args.capture());

    String query = sql.getValue();
    assertThat(query).contains("SET status = 'PROCESSING'");
    assertThat(query).contains("version = version + 1");
    assertThat(query).contains("status IN (?, ?)");
    assertThat(query).contains("WHERE tenant_id = ? AND id = ?");

    Object[] bound = args.getValue();
    assertThat(bound[0]).isEqualTo(TENANT);
    assertThat(bound[1]).isEqualTo(DOC);
    // The claimable states are bound in the set's iteration order, which is not specified, so
    // assert
    // both names are present without pinning their position in the IN (...) clause.
    assertThat(new Object[] {bound[2], bound[3]})
        .containsExactlyInAnyOrder(UPLOADED.name(), QUEUED.name());
  }

  /** An empty claimable-set is a programming error, not a valid transition. */
  @Test
  void claim_for_indexing_rejects_empty_claimable_states() {
    DocumentRepository repo = new DocumentRepository(mock(JdbcTemplate.class));

    assertThatThrownBy(() -> repo.claimForIndexing(TENANT, DOC, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The duplicate-upload lookup is keyed on {@code (tenant_id, sha256)} and is tenant-scoped
   * (invariant 1): another tenant's content-identical payload is indistinguishable from a missing
   * one, so a cross-tenant lookup returns empty.
   */
  @Test
  @SuppressWarnings("unchecked")
  void find_by_tenant_and_sha256_is_tenant_scoped() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DocumentRepository repo = new DocumentRepository(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(Collections.singletonList(new DocumentLookupRow(DOC, KB, QUEUED)));

    Optional<DocumentLookupRow> row = repo.findByTenantAndSha256(TENANT, "deadbeef");

    assertThat(row).isPresent();
    assertThat(row.get().documentId()).isEqualTo(DOC);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());

    assertThat(sql.getValue()).contains("WHERE tenant_id = ? AND sha256 = ?");
    Object[] bound = args.getValue();
    assertThat(bound[0]).isEqualTo(TENANT);
    assertThat(bound[1]).isEqualTo("deadbeef");
  }

  /** A missing duplicate lookup is empty — a fresh upload, never a hit (invariant 1). */
  @Test
  @SuppressWarnings("unchecked")
  void find_by_tenant_and_sha256_is_empty_when_no_row_matches() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DocumentRepository repo = new DocumentRepository(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(Collections.emptyList());

    assertThat(repo.findByTenantAndSha256(TENANT, "deadbeef")).isEmpty();
  }

  /**
   * The manual-retry existence check is keyed on {@code (tenant_id, id)} and is tenant-scoped: a
   * forged or cross-tenant id is a no-op (empty), so the retry reports {@code 404}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void find_by_tenant_and_id_is_tenant_scoped() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DocumentRepository repo = new DocumentRepository(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(Collections.singletonList(new DocumentLookupRow(DOC, KB, INDEXED)));

    Optional<DocumentLookupRow> row = repo.findByTenantAndId(TENANT, DOC);

    assertThat(row).isPresent();
    assertThat(row.get().knowledgeBaseId()).isEqualTo(KB);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());

    assertThat(sql.getValue()).contains("WHERE tenant_id = ? AND id = ?");
    Object[] bound = args.getValue();
    assertThat(bound[0]).isEqualTo(TENANT);
    assertThat(bound[1]).isEqualTo(DOC);
  }

  /**
   * The manual-retry release is guarded by {@code status = 'FAILED'} and clears the stale
   * diagnostic so a fresh pass records a new one.
   */
  @Test
  @SuppressWarnings("unchecked")
  void requeue_failed_to_queued_is_guarded_by_failed_status() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DocumentRepository repo = new DocumentRepository(jdbc);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThat(repo.requeueFailedToQueued(TENANT, DOC)).isTrue();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(sql.capture(), args.capture());

    String query = sql.getValue();
    assertThat(query).contains("SET status = 'QUEUED'");
    assertThat(query).contains("version = version + 1");
    assertThat(query).contains("error_code = NULL, error_summary = NULL");
    assertThat(query).contains("WHERE tenant_id = ? AND id = ? AND status = 'FAILED'");

    Object[] bound = args.getValue();
    assertThat(bound[0]).isEqualTo(TENANT);
    assertThat(bound[1]).isEqualTo(DOC);
  }

  /**
   * {@code markFailed} records the safe, machine-readable code and summary, guarded by {@code
   * status = 'PROCESSING'}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void mark_failed_binds_the_safe_error_code_and_summary() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DocumentRepository repo = new DocumentRepository(jdbc);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThat(repo.markFailed(TENANT, DOC, "EXTRACTION_01", "corrupt")).isTrue();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(sql.capture(), args.capture());

    assertThat(sql.getValue()).contains("SET status = 'FAILED'");
    assertThat(sql.getValue()).contains("error_code = ?, error_summary = ?");
    assertThat(sql.getValue()).contains("WHERE tenant_id = ? AND id = ? AND status = 'PROCESSING'");

    Object[] bound = args.getValue();
    assertThat(bound[0]).isEqualTo("EXTRACTION_01");
    assertThat(bound[1]).isEqualTo("corrupt");
    assertThat(bound[2]).isEqualTo(TENANT);
    assertThat(bound[3]).isEqualTo(DOC);
  }
}
