package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.workflowexecution.persistence.IdempotentRunView;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the create-run idempotency service (S06-06), DB-free.
 *
 * <p>Proves the idempotency decision this service owns at the application boundary: the lookup is
 * delegated to {@link WorkflowRunRepository#findByIdempotencyKey}, a matching payload fingerprint
 * is replayed as the original {@link RunDto}, and a mismatching fingerprint under the same key is a
 * permanent {@code 409 conflict} (never a silent overwrite). The repository is Mockito-mocked, so
 * the real PostgreSQL behaviour — the atomic lookup, the concurrent-duplicate resolution and the
 * commit boundary — stays in {@code RunServiceAtomicIT}.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyKeyServiceTest {

  @Mock private WorkflowRunRepository runRepository;

  private IdempotencyKeyService service;

  private final UUID tenant = UUID.randomUUID();
  private final UUID key = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new IdempotencyKeyService(runRepository);
  }

  @Test
  void findByKey_delegates_to_the_repository() {
    IdempotentRunView stored = storedRun("fp");
    when(runRepository.findByIdempotencyKey(tenant, key)).thenReturn(Optional.of(stored));

    Optional<IdempotentRunView> result = service.findByIdempotencyKey(tenant, key);

    assertThat(result).contains(stored);
    verify(runRepository).findByIdempotencyKey(tenant, key);
  }

  @Test
  void answerReplay_with_a_matching_fingerprint_returns_the_original_run() {
    IdempotentRunView stored = storedRun("fp");

    RunDto result = service.answerReplay(stored, "fp", key);

    assertThat(result.id()).isEqualTo(stored.runId());
    assertThat(result.workflowVersionId()).isEqualTo(stored.workflowVersionId());
    assertThat(result.status()).isEqualTo(stored.status());
    assertThat(result.attempt()).isEqualTo(stored.attempt());
    assertThat(result.createdAt()).isEqualTo(stored.createdAt());
  }

  @Test
  void answerReplay_with_a_mismatching_fingerprint_is_a_conflict_not_an_overwrite() {
    IdempotentRunView stored = storedRun("stored-fp");

    assertThatThrownBy(() -> service.answerReplay(stored, "incoming-fp", key))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    // The conflict message names the reused key, so an operator can trace the collision.
  }

  @Test
  void findByKey_without_a_database_reports_unavailable() {
    IdempotencyKeyService noDb = new IdempotencyKeyService(null);

    assertThatThrownBy(() -> noDb.findByIdempotencyKey(tenant, key))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    // Nothing is read when there is no database — the run and its event both fail loudly (invariant
    // 3); a keyed create with no database never silently creates.
    verify(runRepository, never()).findByIdempotencyKey(eq(tenant), eq(key));
  }

  /** Builds the persistence view a keyed replay compares against, over {@code fingerprint}. */
  private static IdempotentRunView storedRun(String fingerprint) {
    return new IdempotentRunView(
        UUID.randomUUID(), UUID.randomUUID(), "QUEUED", 1, OffsetDateTime.now(), fingerprint);
  }
}
