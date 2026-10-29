package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
  private final OutboxService outboxService = mock(OutboxService.class);
  private final WorkflowVersionService versionService = mock(WorkflowVersionService.class);

  private RunService service;

  private final UUID tenant = UUID.randomUUID();
  private final UUID workflow = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new RunService(runRepository, outboxService, versionService);
  }

  @Test
  void create_run_pins_to_the_active_version_creates_a_run_and_enqueues_the_event() {
    when(versionService.getActiveVersion(tenant, workflow))
        .thenReturn(new WorkflowVersionDto(versionId, workflow, 1, "{}", "c", "ACTIVATED"));

    RunDto result = service.createRun(tenant, workflow, "alice");

    // Pinned to the active version, QUEUED, attempt 1.
    assertThat(result.workflowVersionId()).isEqualTo(versionId);
    assertThat(result.status()).isEqualTo("QUEUED");
    assertThat(result.attempt()).isEqualTo(1);
    // The run was created against the pinned version and the tenant.
    verify(runRepository).create(any(UUID.class), eq(tenant), eq(versionId));
    // The request event was appended to the outbox.
    verify(outboxService).enqueue(any(OutboxEvent.class));
  }

  @Test
  void create_run_without_an_active_version_is_not_found() {
    // No active version: getActiveVersion reports 404 (a run can never pin to a missing version).
    doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
        .when(versionService)
        .getActiveVersion(tenant, workflow);

    assertThatThrownBy(() -> service.createRun(tenant, workflow, "alice"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    // No run is created and no event is appended when there is nothing to pin to.
    verify(runRepository, never()).create(any(), any(), any());
    verify(outboxService, never()).enqueue(any());
  }

  @Test
  void create_run_without_a_database_reports_unavailable() {
    RunService noDb = new RunService(null, outboxService, versionService);

    assertThatThrownBy(() -> noDb.createRun(tenant, workflow, "alice"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    // Nothing is created or appended: the run and its event both fail loudly (invariant 3).
    verify(runRepository, never()).create(any(), any(), any());
    verify(outboxService, never()).enqueue(any());
  }
}
