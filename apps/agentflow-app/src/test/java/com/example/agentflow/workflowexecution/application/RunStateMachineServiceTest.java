package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.RunStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.StepStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the single run/step transition service (S05-11, the {@code
 * RunStateMachineService}).
 *
 * <p>The acceptance criterion for S05-11 is that an ordinary application service cannot bypass the
 * state machine: the only method that changes a run or step status is {@code
 * RunStateMachineService}, and every call runs the domain gate before the conditional update and
 * appends the transition fact only after the update succeeds. These tests exercise that service end
 * to end at the application boundary, DB-free: the two repositories and the {@link
 * WorkflowEventService} are mocked, so the reads, the transition gate, the conditional write and
 * the event append are asserted directly (matching the DB-free {@code messaging} suite convention).
 *
 * <p>The service maps the domain and persistence outcomes to permanent HTTP errors: {@code 404} for
 * a run/step that does not match {@code (tenant, id)}, {@code 400} for a forbidden or {@code null}
 * transition (the state machine gate), {@code 409} for a conditional update that matched no row
 * (another writer won the transition), and {@code 503} when no database is configured.
 */
class RunStateMachineServiceTest {

  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final WorkflowStepRunRepository stepRepository = mock(WorkflowStepRunRepository.class);
  private final WorkflowEventService eventService = mock(WorkflowEventService.class);

  private RunStateMachineService service;

  @BeforeEach
  void setUp() {
    service = new RunStateMachineService(runRepository, stepRepository, eventService);
  }

  // ---------------------------------------------------------------------------
  // Run transitions: the run-level half of the single transition service
  // ---------------------------------------------------------------------------

  @Test
  void transitionRun_reads_asserts_updates_and_appends_event_in_order() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.QUEUED, 0)));

    WorkflowRunState result =
        service.transitionRun(tenant, runId, WorkflowRunState.RUNNING, "run.running", null);

    assertThat(result).isEqualTo(WorkflowRunState.RUNNING);

    // the gate runs, then the conditional update, then the event append — the event is the
    // durable fact of the transition, appended only after the conditional update is applied
    verify(runRepository).findState(tenant, runId);
    verify(runRepository)
        .transition(runId, tenant, WorkflowRunState.QUEUED, 0, WorkflowRunState.RUNNING);
    verify(eventService).append(tenant, runId, null, "run.running", "prior=QUEUED new=RUNNING");
  }

  @Test
  void transitionRun_appends_the_actor_in_the_event_payload_when_supplied() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.RUNNING, 3)));

    service.transitionRun(tenant, runId, WorkflowRunState.SUCCEEDED, "run.succeeded", "alice");

    verify(eventService)
        .append(tenant, runId, null, "run.succeeded", "prior=RUNNING new=SUCCEEDED (actor=alice)");
  }

  @Test
  void transitionRun_forbidden_transition_is_a_400_the_state_machine_cannot_be_bypassed() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    // SUCCEEDED is terminal: a re-delivered event must not move it again (the gate holds).
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.SUCCEEDED, 7)));

    ResponseStatusException ex =
        expectException(
            () ->
                service.transitionRun(
                    tenant, runId, WorkflowRunState.RUNNING, "run.running", null));

    // the conditional update is never reached: the domain gate refused before any write
    verify(runRepository, never()).transition(any(), any(), any(), anyInt(), any());
    verify(eventService, never()).append(any(), any(), any(), any(), any());
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void transitionRun_null_target_state_is_a_400() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.QUEUED, 0)));

    ResponseStatusException ex =
        expectException(() -> service.transitionRun(tenant, runId, null, "run.running", null));

    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void transitionRun_unknown_run_is_a_404_cross_tenant_is_indistinguishable_from_missing() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(runRepository.findState(tenant, runId)).thenReturn(Optional.empty());

    ResponseStatusException ex =
        expectException(
            () ->
                service.transitionRun(
                    tenant, runId, WorkflowRunState.RUNNING, "run.running", null));

    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void transitionRun_conditional_update_conflict_is_a_409() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.QUEUED, 0)));
    doThrow(new ConflictException("run " + runId + " is out of date"))
        .when(runRepository)
        .transition(any(), any(), any(), eq(0), any());

    ResponseStatusException ex =
        expectException(
            () ->
                service.transitionRun(
                    tenant, runId, WorkflowRunState.RUNNING, "run.running", null));

    // the event is not appended when the conditional update is refused — no divergent history
    verify(eventService, never()).append(any(), any(), any(), any(), any());
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void transitionRun_no_database_is_a_503_not_a_silent_no_op() {
    RunStateMachineService noDb = new RunStateMachineService(null, stepRepository, eventService);

    ResponseStatusException ex =
        expectException(
            () ->
                noDb.transitionRun(
                    UUID.randomUUID(), UUID.randomUUID(), WorkflowRunState.RUNNING, "x", null));

    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  // ---------------------------------------------------------------------------
  // Step transitions: the step-level half of the single transition service
  // ---------------------------------------------------------------------------

  @Test
  void transitionStep_reads_asserts_updates_and_appends_a_scoped_event_in_order() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID stepId = UUID.randomUUID();
    String stepKey = "llm_plan";
    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(
            Optional.of(new StepStateSnapshot(stepId.toString(), WorkflowStepState.PENDING, 2)));

    WorkflowStepState result =
        service.transitionStep(
            tenant, runId, stepKey, WorkflowStepState.READY, "step.running", null);

    assertThat(result).isEqualTo(WorkflowStepState.READY);

    verify(stepRepository).findState(tenant, runId, stepKey);
    verify(stepRepository)
        .transition(runId, tenant, stepKey, WorkflowStepState.PENDING, 2, WorkflowStepState.READY);
    // the event is scoped to the step own row (step_run_id set) and names the graph node
    verify(eventService)
        .append(tenant, runId, stepId, "step.running", "step=llm_plan prior=PENDING new=READY");
  }

  @Test
  void transitionStep_forbidden_transition_is_a_400() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID stepId = UUID.randomUUID();
    String stepKey = "tool_call";
    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(
            Optional.of(new StepStateSnapshot(stepId.toString(), WorkflowStepState.SUCCEEDED, 1)));

    ResponseStatusException ex =
        expectException(
            () ->
                service.transitionStep(
                    tenant, runId, stepKey, WorkflowStepState.RUNNING, "step.running", null));

    verify(stepRepository, never()).transition(any(), any(), any(), any(), anyInt(), any());
    verify(eventService, never()).append(any(), any(), any(), any(), any());
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void transitionStep_missing_step_is_a_404() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    String stepKey = "tool_call";
    when(stepRepository.findState(tenant, runId, stepKey)).thenReturn(Optional.empty());

    ResponseStatusException ex =
        expectException(
            () ->
                service.transitionStep(
                    tenant, runId, stepKey, WorkflowStepState.RUNNING, "step.running", null));

    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void transitionStep_conditional_update_conflict_is_a_409() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID stepId = UUID.randomUUID();
    String stepKey = "tool_call";
    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(
            Optional.of(new StepStateSnapshot(stepId.toString(), WorkflowStepState.RUNNING, 4)));
    doThrow(new ConflictException("step out of date"))
        .when(stepRepository)
        .transition(any(), any(), any(), any(), anyInt(), any());

    ResponseStatusException ex =
        expectException(
            () ->
                service.transitionStep(
                    tenant, runId, stepKey, WorkflowStepState.SUCCEEDED, "step.succeeded", null));

    verify(eventService, never()).append(any(), any(), any(), any(), any());
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void transitionStep_no_database_is_a_503() {
    RunStateMachineService noDb = new RunStateMachineService(runRepository, null, eventService);

    ResponseStatusException ex =
        expectException(
            () ->
                noDb.transitionStep(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "k",
                    WorkflowStepState.RUNNING,
                    "x",
                    null));

    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  // Runs the transition and returns the thrown ResponseStatusException, or fails if none is thrown.
  // A plain try/catch is used instead of AssertJ's extracting/get (which does not resolve on the
  // captured assert type in this AssertJ version) so the HTTP status is asserted on a real value.
  private static ResponseStatusException expectException(Runnable action) {
    try {
      action.run();
    } catch (ResponseStatusException ex) {
      return ex;
    }
    throw new AssertionError("Expected a ResponseStatusException, but none was thrown");
  }
}
