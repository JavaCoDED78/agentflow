package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
import com.example.agentflow.knowledgebase.persistence.DocumentRepository;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DB-free tests for the {@code documents.status} single transition service ({@link
 * DocumentTransitionService}), {@code S10-19} retry + reindex + {@code S10-01} vocabulary.
 *
 * <p>The {@link DocumentRepository} is mocked, so no {@code DataSource} is needed. The tests pin
 * the facts that must hold before any database exists:
 *
 * <ul>
 *   <li>{@code claimForIndexing} maps the reindex flag onto the allowed from-states (the {@code
 *       UPDATE} redelivery claims from {@code INDEXED} too, {@code SKIP} does not);
 *   <li>{@code markIndexed} records the {@code pipeline_version} (the reindex trigger) and rejects
 *       a blank one; and
 *   <li>every transition fails loudly with {@link IllegalStateException} when no database is
 *       configured ({@code S01-02}).
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DocumentTransitionServiceTest {

  private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555566667777");
  private static final UUID DOC = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

  private static final DocumentUploadStatus UPLOADED = DocumentUploadStatus.UPLOADED;
  private static final DocumentUploadStatus QUEUED = DocumentUploadStatus.QUEUED;
  private static final DocumentUploadStatus INDEXED = DocumentUploadStatus.INDEXED;

  @Mock private DocumentRepository documentRepository;

  private DocumentTransitionService transition;

  @BeforeEach
  void setUp() {
    transition = new DocumentTransitionService(documentRepository);
  }

  /**
   * The reindex flag selects the claimable states: with reindex disabled the claim moves only from
   * the fresh states ({@code UPLOADED}/{@code QUEUED}); with reindex enabled it also claims from
   * {@code INDEXED} (the {@code UPDATE} redelivery policy re-embeds in place).
   */
  @Test
  void claim_for_indexing_reindex_flag_selects_the_claimable_states() {
    when(documentRepository.claimForIndexing(TENANT, DOC, Set.of(UPLOADED, QUEUED)))
        .thenReturn(true);
    when(documentRepository.claimForIndexing(TENANT, DOC, Set.of(UPLOADED, QUEUED, INDEXED)))
        .thenReturn(true);

    assertThat(transition.claimForIndexing(TENANT, DOC, false)).isTrue();
    assertThat(transition.claimForIndexing(TENANT, DOC, true)).isTrue();
  }

  /**
   * A claim that finds the document in another state affects zero rows and reports {@code false}.
   */
  @Test
  void claim_for_indexing_returns_false_when_no_row_transitions() {
    when(documentRepository.claimForIndexing(TENANT, DOC, Set.of(UPLOADED, QUEUED)))
        .thenReturn(false);

    assertThat(transition.claimForIndexing(TENANT, DOC, false)).isFalse();
  }

  /**
   * {@code markIndexed} records the pipeline version and page count so the document reflects the
   * version backing it (the reindex trigger, S10-19).
   */
  @Test
  void mark_indexed_forwards_the_pipeline_version_and_page_count() {
    when(documentRepository.markIndexed(TENANT, DOC, 4, "v2")).thenReturn(true);
    assertThat(transition.markIndexed(TENANT, DOC, 4, "v2")).isTrue();
    verify(documentRepository).markIndexed(TENANT, DOC, 4, "v2");
  }

  /**
   * The pipeline version is the reindex trigger, so it must never be blank (a stale index would be
   * indistinguishable from a fresh one).
   */
  @Test
  void mark_indexed_rejects_blank_pipeline_version() {
    assertThrows(IllegalArgumentException.class, () -> transition.markIndexed(TENANT, DOC, 4, ""));
    assertThrows(
        IllegalArgumentException.class, () -> transition.markIndexed(TENANT, DOC, 4, null));
  }

  /** {@code releaseFailedToQueued} drives the {@code FAILED -> QUEUED} retry edge. */
  @Test
  void release_failed_to_queued_delegates_to_the_queue_failed_transition() {
    when(documentRepository.requeueFailedToQueued(TENANT, DOC)).thenReturn(true);

    assertThat(transition.releaseFailedToQueued(TENANT, DOC)).isTrue();
    verify(documentRepository).requeueFailedToQueued(TENANT, DOC);
  }

  /** {@code releaseToQueued} is the transient {@code PROCESSING -> QUEUED} retry edge. */
  @Test
  void release_to_queued_delegates_to_the_queued_transition() {
    when(documentRepository.releaseToQueued(TENANT, DOC)).thenReturn(true);

    assertThat(transition.releaseToQueued(TENANT, DOC)).isTrue();
    verify(documentRepository).releaseToQueued(TENANT, DOC);
  }

  /** {@code markFailed} records the safe error code and summary. */
  @Test
  void mark_failed_forwards_the_error_code_and_summary() {
    when(documentRepository.markFailed(TENANT, DOC, "EXTRACTION_01", "corrupt")).thenReturn(true);
    assertThat(transition.markFailed(TENANT, DOC, "EXTRACTION_01", "corrupt")).isTrue();
    verify(documentRepository).markFailed(TENANT, DOC, "EXTRACTION_01", "corrupt");
  }

  /**
   * Every working transition fails loudly with {@link IllegalStateException} when no database
   * connection is configured ({@code S01-02}) — a deployment fault, never a silent no-op.
   */
  @Test
  void transitions_fail_loudly_without_database() {
    DocumentTransitionService noDb = new DocumentTransitionService(null);

    assertThrows(IllegalStateException.class, () -> noDb.claimForIndexing(TENANT, DOC, false));
    assertThrows(IllegalStateException.class, () -> noDb.markIndexed(TENANT, DOC, 4, "v2"));
    assertThrows(IllegalStateException.class, () -> noDb.markFailed(TENANT, DOC, "X", "x"));
    assertThrows(IllegalStateException.class, () -> noDb.releaseToQueued(TENANT, DOC));
    assertThrows(IllegalStateException.class, () -> noDb.releaseFailedToQueued(TENANT, DOC));
  }
}
