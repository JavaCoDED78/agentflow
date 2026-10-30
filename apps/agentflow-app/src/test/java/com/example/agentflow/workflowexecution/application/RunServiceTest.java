package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionDto;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowexecution.persistence.IdempotentRunView;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the create-run application service (S06-05), DB-free.
 *
 * <p>The acceptance criterion is atomic create + outbox-append and the tenant/version guards. These
 * tests exercise {@link RunService#createRun} at the application boundary with the repositories and
 * services mocked (matching the DB-free {@code messaging} / {@code workflowexecution} suite
 * convention): the pin to the active version, the run insert, the outbox append and the returned
 * {@link RunDto} are asserted directly. They prove the code <em>emits</em> the right calls in the
 * right order, but a mocked transaction never <em>commits</em> — atomicity across the run row and
 * the outbox event is proven by {@code RunServiceAtomicIT} against a real PostgreSQL.
 */
class RunServiceTest {

  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final IdempotencyKeyService idempotencyKeyService =
      new IdempotencyKeyService(runRepository);
  private final OutboxService outboxService = mock(OutboxService.class);
  private final WorkflowVersionService versionService = mock(WorkflowVersionService.class);

  private RunService service;

  private final UUID tenant = UUID.randomUUID();
  private final UUID workflow = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();
  private final UUID key = UUID.randomUUID();
  private final String payload = "{\"workflowId\":\"" + workflow + "\"}";

  @BeforeEach
  void setUp() {
    service = new RunService(runRepository, idempotencyKeyService, outboxService, versionService);
  }

  @Test
  void create_run_pins_to_the_active_version_creates_a_run_and_enqueues_the_event() {
    when(versionService.getActiveVersion(tenant, workflow))
        .thenReturn(new WorkflowVersionDto(versionId, workflow, 1, "{}", "c", "ACTIVATED"));

    RunDto result = service.createRun(tenant, "alice", new CreateRunRequest(workflow, null), null);

    // Pinned to the active version, QUEUED, attempt 1.
    assertThat(result.workflowVersionId()).isEqualTo(versionId);
    assertThat(result.status()).isEqualTo("QUEUED");
    assertThat(result.attempt()).isEqualTo(1);
    // The run was created against the pinned version and the tenant (no idempotency key, no
    // fingerprint — those columns are written NULL).
    verify(runRepository).create(any(UUID.class), eq(tenant), eq(versionId), any(), any(), any());
    // The request event was appended to the outbox.
    verify(outboxService).enqueue(any(OutboxEvent.class));
  }

  @Test
  void create_run_without_a_database_reports_unavailable() {
    RunService noDb =
        new RunService(null, new IdempotencyKeyService(null), outboxService, versionService);

    assertThatThrownBy(
            () -> noDb.createRun(tenant, "alice", new CreateRunRequest(workflow, null), null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    // Nothing is created or appended: the run and its event both fail loudly (invariant 3).
    verify(runRepository, never()).create(any(), any(), any(), any(), any(), any());
    verify(outboxService, never()).enqueue(any());
  }

  @Test
  void create_run_without_an_active_version_is_not_found() {
    // No active version: getActiveVersion reports 404 (a run can never pin to a missing version).
    doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
        .when(versionService)
        .getActiveVersion(tenant, workflow);

    assertThatThrownBy(
            () -> service.createRun(tenant, "alice", new CreateRunRequest(workflow, null), null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    // No run is created and no event is appended when there is nothing to pin to.
    verify(runRepository, never()).create(any(), any(), any(), any(), any(), any());
    verify(outboxService, never()).enqueue(any());
  }

  @Test
  void a_keyed_create_stores_the_key_and_fingerprint_and_returns_a_run() {
    when(versionService.getActiveVersion(tenant, workflow))
        .thenReturn(new WorkflowVersionDto(versionId, workflow, 1, "{}", "c", "ACTIVATED"));
    // No run yet under the key: the keyed create falls through to the normal create.
    when(runRepository.findByIdempotencyKey(tenant, key)).thenReturn(Optional.empty());

    RunDto result =
        service.createRun(tenant, "alice", new CreateRunRequest(workflow, key), payload);

    // The run row is created with the idempotency key and the payload fingerprint alongside it, and
    // its event is appended — exactly one keyed create path (invariant 6, no silent duplicate).
    verify(runRepository)
        .create(any(UUID.class), eq(tenant), eq(versionId), eq(key), eq(payload), anyString());
    verify(outboxService).enqueue(any(OutboxEvent.class));
    assertThat(result.id()).isNotNull();
  }

  @Test
  void a_keyed_repeat_with_the_same_payload_returns_the_original_run_without_a_second_create() {
    IdempotentRunView existing = existingRun(payload);
    when(runRepository.findByIdempotencyKey(tenant, key)).thenReturn(Optional.of(existing));

    RunDto result =
        service.createRun(tenant, "alice", new CreateRunRequest(workflow, key), payload);

    // The replayed run is returned, and neither a second run nor a second event is produced: the
    // client that retried a lost response succeeds cleanly (idempotent, invariant 6).
    assertThat(result.id()).isEqualTo(existing.runId());
    verify(runRepository, never()).create(any(), any(), any(), any(), any(), any());
    verify(outboxService, never()).enqueue(any());
  }

  @Test
  void a_keyed_repeat_with_a_different_payload_is_a_conflict_not_an_overwrite() {
    // A stored run under the key carries a different payload fingerprint.
    IdempotentRunView existing = existingRun("different");
    when(runRepository.findByIdempotencyKey(tenant, key)).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () -> service.createRun(tenant, "alice", new CreateRunRequest(workflow, key), payload))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    // Nothing new is created and nothing is appended: a reused key claims the payload too (a
    // different payload under the same key is a permanent conflict, not a silent overwrite).
    verify(runRepository, never()).create(any(), any(), any(), any(), any(), any());
    verify(outboxService, never()).enqueue(any());
  }

  @Test
  void a_concurrent_duplicate_create_resolves_the_winner_instead_of_surfacing_a_database_error() {
    // The first (lost) create collides on the unique (tenant_id, idempotency_key) constraint...
    when(runRepository.findByIdempotencyKey(tenant, key))
        .thenReturn(Optional.empty())
        .thenThrow(new DataIntegrityViolationException("unique violation"))
        .thenReturn(Optional.of(existingRun(payload)));
    when(versionService.getActiveVersion(tenant, workflow))
        .thenReturn(new WorkflowVersionDto(versionId, workflow, 1, "{}", "c", "ACTIVATED"));

    // ...so the service re-resolves the winner: the committed run matches the payload, so it is
    // returned as an idempotent replay rather than the raw constraint error.
    RunDto result =
        service.createRun(tenant, "alice", new CreateRunRequest(workflow, key), payload);

    assertThat(result.id()).isNotNull();
  }

  /** Builds the persistence view a keyed replay compares against, over {@code payload}. */
  private IdempotentRunView existingRun(String payload) {
    return new IdempotentRunView(
        UUID.randomUUID(),
        versionId,
        "QUEUED",
        1,
        OffsetDateTime.now(),
        payload == null ? null : payload);
  }
}
