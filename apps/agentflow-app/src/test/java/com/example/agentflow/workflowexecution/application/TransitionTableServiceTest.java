package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.RunStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.StepStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Table-driven tests for the single run/step transition service (S05-15, the {@code
 * RunStateMachineService}).
 *
 * <p>S05-10 already proved the pure enums ({@link WorkflowStateMachineTest}) match the transition
 * tables for every ordered pair. This suite proves the same tables hold <em>through the single
 * transition service</em> &mdash; the only boundary that mutates state (invariant 4) &mdash; for
 * each documented transition named by its trigger event in {@code run-state-machine.md} (S05-01)
 * and {@code step-state-machine.md} (S05-02): an allowed edge returns {@code to} and appends
 * exactly one {@code workflow_events} row with the {@code prior=&gt;new} payload, and a forbidden
 * edge (a terminal state refusing any move, or a no-edge pair) is a permanent {@code 400} with
 * nothing appended. The two repositories and the {@link WorkflowEventService} are mocked, so the
 * read, the domain gate, the conditional write and the event append are asserted directly at the
 * application boundary (the same DB-free convention as {@link RunStateMachineServiceTest}).
 *
 * <p>Three shapes make the acceptance criterion concrete: {@link
 * #allowedRunTransitions_applies_and_appends_event} and {@link
 * #allowedStepTransitions_applies_and_appends_event} drive every edge in the tables; the named
 * scenarios from the task &mdash; terminal states, retry, cancel and the approval pause/resume
 * &mdash; are asserted as multi-step sequences; and the forbidden-edge table asserts the {@code
 * 400} no-op.
 */
class TransitionTableServiceTest {

  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final WorkflowStepRunRepository stepRepository = mock(WorkflowStepRunRepository.class);
  private final WorkflowEventService eventService = mock(WorkflowEventService.class);

  private RunStateMachineService service;

  @BeforeEach
  void setUp() {
    service = new RunStateMachineService(runRepository, stepRepository, eventService);
  }

  // ---------------------------------------------------------------------------
  // Run transitions: every documented edge, applied through the service
  // ---------------------------------------------------------------------------

  record RunEdge(int guard, WorkflowRunState from, WorkflowRunState to, String event) {}

  private static Stream<RunEdge> allowedRunEdges() {
    return Stream.of(
        new RunEdge(1, WorkflowRunState.QUEUED, WorkflowRunState.RUNNING, "run.started"),
        new RunEdge(
            2,
            WorkflowRunState.RUNNING,
            WorkflowRunState.WAITING_FOR_APPROVAL,
            "step.approval-needed"),
        new RunEdge(
            3, WorkflowRunState.WAITING_FOR_APPROVAL, WorkflowRunState.RUNNING, "run.approved"),
        new RunEdge(
            4, WorkflowRunState.WAITING_FOR_APPROVAL, WorkflowRunState.FAILED, "run.rejected"),
        new RunEdge(5, WorkflowRunState.RUNNING, WorkflowRunState.RETRY_WAIT, "step.failed"),
        new RunEdge(6, WorkflowRunState.RETRY_WAIT, WorkflowRunState.RUNNING, "run.retry-due"),
        new RunEdge(7, WorkflowRunState.RUNNING, WorkflowRunState.SUCCEEDED, "run.finished"),
        new RunEdge(8, WorkflowRunState.RUNNING, WorkflowRunState.FAILED, "run.failed"),
        new RunEdge(9, WorkflowRunState.QUEUED, WorkflowRunState.CANCEL_REQUESTED, "run.cancel"),
        new RunEdge(10, WorkflowRunState.RUNNING, WorkflowRunState.CANCEL_REQUESTED, "run.cancel"),
        new RunEdge(
            11,
            WorkflowRunState.WAITING_FOR_APPROVAL,
            WorkflowRunState.CANCEL_REQUESTED,
            "run.cancel"),
        new RunEdge(
            12, WorkflowRunState.RETRY_WAIT, WorkflowRunState.CANCEL_REQUESTED, "run.cancel"),
        new RunEdge(
            13, WorkflowRunState.CANCEL_REQUESTED, WorkflowRunState.CANCELLED, "run.cancelled"));
  }

  @ParameterizedTest(name = "run guard {0}: {1} -> {2} via {3}")
  @MethodSource("allowedRunEdges")
  @DisplayName("run: every documented edge applies and appends one prior->new event")
  void allowedRunTransitions_applies_and_appends_event(RunEdge edge) {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(edge.from(), 0)));

    WorkflowRunState result = service.transitionRun(tenant, runId, edge.to(), edge.event(), null);

    assertThat(result).isEqualTo(edge.to());
    verify(runRepository).transition(eq(runId), eq(tenant), eq(edge.from()), eq(0), eq(edge.to()));
    verify(eventService)
        .append(
            any(),
            any(),
            any(),
            eq(edge.event()),
            eq("prior=" + edge.from() + " new=" + edge.to()));
  }

  @ParameterizedTest(name = "run guard {0}: {1} -> {2} (no edge) must be refused")
  @MethodSource("forbiddenRunEdges")
  @DisplayName("run: a forbidden edge is a 400 and appends nothing")
  void forbiddenRunTransition_is_a_400_and_appends_nothing(RunEdge edge) {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(edge.from(), 0)));

    ResponseStatusException ex =
        expectException(() -> service.transitionRun(tenant, runId, edge.to(), edge.event(), null));

    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verify(runRepository, never()).transition(any(), any(), any(), anyInt(), any());
    verify(eventService, never()).append(any(), any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Step transitions: every documented edge, applied through the service
  // ---------------------------------------------------------------------------

  record StepEdge(int guard, WorkflowStepState from, WorkflowStepState to, String event) {}

  private static Stream<StepEdge> allowedStepEdges() {
    return Stream.of(
        new StepEdge(1, WorkflowStepState.PENDING, WorkflowStepState.READY, "step.schedulable"),
        new StepEdge(2, WorkflowStepState.READY, WorkflowStepState.RUNNING, "step.started"),
        new StepEdge(3, WorkflowStepState.RUNNING, WorkflowStepState.SUCCEEDED, "step.succeeded"),
        new StepEdge(4, WorkflowStepState.RUNNING, WorkflowStepState.RETRY_WAIT, "step.failed"),
        new StepEdge(5, WorkflowStepState.RETRY_WAIT, WorkflowStepState.RUNNING, "run.retry-due"),
        new StepEdge(6, WorkflowStepState.RUNNING, WorkflowStepState.FAILED, "step.failed"),
        new StepEdge(7, WorkflowStepState.PENDING, WorkflowStepState.SKIPPED, "step.skipped"),
        new StepEdge(8, WorkflowStepState.READY, WorkflowStepState.SKIPPED, "step.skipped"),
        new StepEdge(9, WorkflowStepState.PENDING, WorkflowStepState.CANCELLED, "run.cancel"),
        new StepEdge(10, WorkflowStepState.READY, WorkflowStepState.CANCELLED, "run.cancel"),
        new StepEdge(11, WorkflowStepState.RUNNING, WorkflowStepState.CANCELLED, "run.cancelled"),
        new StepEdge(
            12,
            WorkflowStepState.RUNNING,
            WorkflowStepState.WAITING_FOR_APPROVAL,
            "step.approval-needed"),
        new StepEdge(
            13, WorkflowStepState.WAITING_FOR_APPROVAL, WorkflowStepState.RUNNING, "run.approved"),
        new StepEdge(
            14, WorkflowStepState.WAITING_FOR_APPROVAL, WorkflowStepState.FAILED, "run.rejected"),
        new StepEdge(
            15,
            WorkflowStepState.WAITING_FOR_APPROVAL,
            WorkflowStepState.CANCELLED,
            "run.cancelled"));
  }

  @ParameterizedTest(name = "step guard {0}: {1} -> {2} via {3}")
  @MethodSource("allowedStepEdges")
  @DisplayName("step: every documented edge applies and appends one scoped prior->new event")
  void allowedStepTransitions_applies_and_appends_event(StepEdge edge) {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID stepId = UUID.randomUUID();
    String stepKey = "node";
    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(Optional.of(new StepStateSnapshot(stepId.toString(), edge.from(), 0)));

    WorkflowStepState result =
        service.transitionStep(tenant, runId, stepKey, edge.to(), edge.event(), null);

    assertThat(result).isEqualTo(edge.to());
    verify(stepRepository)
        .transition(eq(runId), eq(tenant), eq(stepKey), eq(edge.from()), eq(0), eq(edge.to()));
    verify(eventService)
        .append(
            any(),
            any(),
            eq(stepId),
            eq(edge.event()),
            eq("step=" + stepKey + " prior=" + edge.from() + " new=" + edge.to()));
  }

  @ParameterizedTest(name = "step guard {0}: {1} -> {2} (no edge) must be refused")
  @MethodSource("forbiddenStepEdges")
  @DisplayName("step: a forbidden edge is a 400 and appends nothing")
  void forbiddenStepTransition_is_a_400_and_appends_nothing(StepEdge edge) {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    String stepKey = "node";
    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(
            Optional.of(new StepStateSnapshot(UUID.randomUUID().toString(), edge.from(), 0)));

    ResponseStatusException ex =
        expectException(
            () -> service.transitionStep(tenant, runId, stepKey, edge.to(), edge.event(), null));

    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verify(stepRepository, never()).transition(any(), any(), any(), any(), anyInt(), any());
    verify(eventService, never()).append(any(), any(), any(), any(), any());
  }

  private static Stream<RunEdge> forbiddenRunEdges() {
    return Stream.of(
        // terminal states absorb: a run never leaves SUCCEEDED / FAILED / CANCELLED, not even to
        // itself, so a re-delivered event on a terminal run is refused (invariant 4).
        new RunEdge(-1, WorkflowRunState.SUCCEEDED, WorkflowRunState.RUNNING, "run.started"),
        new RunEdge(-2, WorkflowRunState.FAILED, WorkflowRunState.SUCCEEDED, "run.finished"),
        new RunEdge(-3, WorkflowRunState.CANCELLED, WorkflowRunState.CANCELLED, "run.cancelled"),
        // the run cancel is two-phase: an approval-paused run goes to CANCEL_REQUESTED first, not
        // straight to the terminal CANCELLED.
        new RunEdge(
            -4, WorkflowRunState.WAITING_FOR_APPROVAL, WorkflowRunState.CANCELLED, "run.cancelled"),
        // a QUEUED run must be claimed (RUNNING) before it can finish; no shortcut edges exist.
        new RunEdge(-5, WorkflowRunState.QUEUED, WorkflowRunState.SUCCEEDED, "run.finished"),
        new RunEdge(-6, WorkflowRunState.RUNNING, WorkflowRunState.QUEUED, "run.started"),
        new RunEdge(-7, WorkflowRunState.RETRY_WAIT, WorkflowRunState.SUCCEEDED, "run.finished"));
  }

  private static Stream<StepEdge> forbiddenStepEdges() {
    return Stream.of(
        // terminal states absorb.
        new StepEdge(-1, WorkflowStepState.SUCCEEDED, WorkflowStepState.RUNNING, "step.started"),
        new StepEdge(-2, WorkflowStepState.SKIPPED, WorkflowStepState.RUNNING, "step.started"),
        new StepEdge(
            -3, WorkflowStepState.CANCELLED, WorkflowStepState.SUCCEEDED, "step.succeeded"),
        // a step must be READY before a worker claims it; no edge skips READY.
        new StepEdge(-4, WorkflowStepState.PENDING, WorkflowStepState.RUNNING, "step.started"),
        new StepEdge(-5, WorkflowStepState.READY, WorkflowStepState.SUCCEEDED, "step.succeeded"),
        new StepEdge(-6, WorkflowStepState.RUNNING, WorkflowStepState.PENDING, "step.schedulable"),
        new StepEdge(-7, WorkflowStepState.RETRY_WAIT, WorkflowStepState.SUCCEEDED, "run.finished"),
        new StepEdge(
            -8, WorkflowStepState.WAITING_FOR_APPROVAL, WorkflowStepState.SKIPPED, "step.skipped"));
  }

  // ---------------------------------------------------------------------------
  // Named scenarios: the state-machine behaviors the task calls out by name
  // (terminal, retry round-trip, two-phase cancel, approval pause and resume)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("run: terminal states refuse any transition through the service (absorbing)")
  void run_terminal_states_are_absorbing_through_the_service() {
    for (WorkflowRunState terminal : runTerminalStates()) {
      UUID tenant = UUID.randomUUID();
      UUID runId = UUID.randomUUID();
      when(runRepository.findState(tenant, runId))
          .thenReturn(Optional.of(new RunStateSnapshot(terminal, 0)));

      ResponseStatusException ex =
          expectException(
              () -> service.transitionRun(tenant, runId, WorkflowRunState.RUNNING, "x", null));

      assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      verify(runRepository, never()).transition(any(), any(), any(), anyInt(), any());
      verify(eventService, never()).append(any(), any(), any(), any(), any());
    }
  }

  @Test
  @DisplayName("run: retry is a round-trip RUNNING -> RETRY_WAIT -> RUNNING")
  void run_retry_is_a_round_trip() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.RUNNING, 1)));
    assertThat(
            service.transitionRun(tenant, runId, WorkflowRunState.RETRY_WAIT, "step.failed", null))
        .isEqualTo(WorkflowRunState.RETRY_WAIT);

    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.RETRY_WAIT, 2)));
    assertThat(
            service.transitionRun(tenant, runId, WorkflowRunState.RUNNING, "run.retry-due", null))
        .isEqualTo(WorkflowRunState.RUNNING);
  }

  @Test
  @DisplayName("run: cancel is two-phase, QUEUED/RUNNING -> CANCEL_REQUESTED -> CANCELLED")
  void run_cancel_is_two_phase() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    // QUEUED -> CANCEL_REQUESTED -> CANCELLED
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.QUEUED, 0)));
    assertThat(
            service.transitionRun(
                tenant, runId, WorkflowRunState.CANCEL_REQUESTED, "run.cancel", null))
        .isEqualTo(WorkflowRunState.CANCEL_REQUESTED);

    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.CANCEL_REQUESTED, 1)));
    assertThat(
            service.transitionRun(tenant, runId, WorkflowRunState.CANCELLED, "run.cancelled", null))
        .isEqualTo(WorkflowRunState.CANCELLED);

    // RUNNING -> CANCEL_REQUESTED -> CANCELLED (the in-flight run drains before it is cancelled)
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.RUNNING, 2)));
    assertThat(
            service.transitionRun(
                tenant, runId, WorkflowRunState.CANCEL_REQUESTED, "run.cancel", null))
        .isEqualTo(WorkflowRunState.CANCEL_REQUESTED);

    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.CANCEL_REQUESTED, 3)));
    assertThat(
            service.transitionRun(tenant, runId, WorkflowRunState.CANCELLED, "run.cancelled", null))
        .isEqualTo(WorkflowRunState.CANCELLED);
  }

  @Test
  @DisplayName("run: approval pauses (WAITING_FOR_APPROVAL) and resumes (RUNNING)")
  void run_approval_pauses_and_resumes() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.RUNNING, 1)));
    assertThat(
            service.transitionRun(
                tenant, runId, WorkflowRunState.WAITING_FOR_APPROVAL, "step.approval-needed", null))
        .isEqualTo(WorkflowRunState.WAITING_FOR_APPROVAL);

    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.WAITING_FOR_APPROVAL, 2)));
    assertThat(service.transitionRun(tenant, runId, WorkflowRunState.RUNNING, "run.approved", null))
        .isEqualTo(WorkflowRunState.RUNNING);
  }

  @Test
  @DisplayName("step: retry is a round-trip RUNNING -> RETRY_WAIT -> RUNNING")
  void step_retry_is_a_round_trip() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    String stepKey = "node";

    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(UUID.randomUUID().toString(), WorkflowStepState.RUNNING, 1)));
    assertThat(
            service.transitionStep(
                tenant, runId, stepKey, WorkflowStepState.RETRY_WAIT, "step.failed", null))
        .isEqualTo(WorkflowStepState.RETRY_WAIT);

    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.RETRY_WAIT, 2)));
    assertThat(
            service.transitionStep(
                tenant, runId, stepKey, WorkflowStepState.RUNNING, "run.retry-due", null))
        .isEqualTo(WorkflowStepState.RUNNING);
  }

  @Test
  @DisplayName("step: cancel drains a RUNNING step straight to CANCELLED")
  void step_cancel_after_draining() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    String stepKey = "node";

    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(UUID.randomUUID().toString(), WorkflowStepState.RUNNING, 1)));
    assertThat(
            service.transitionStep(
                tenant, runId, stepKey, WorkflowStepState.CANCELLED, "run.cancelled", null))
        .isEqualTo(WorkflowStepState.CANCELLED);
  }

  @Test
  @DisplayName("step: approval pauses (WAITING_FOR_APPROVAL) and resumes (RUNNING)")
  void step_approval_pauses_and_resumes() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    String stepKey = "node";

    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(UUID.randomUUID().toString(), WorkflowStepState.RUNNING, 1)));
    assertThat(
            service.transitionStep(
                tenant,
                runId,
                stepKey,
                WorkflowStepState.WAITING_FOR_APPROVAL,
                "step.approval-needed",
                null))
        .isEqualTo(WorkflowStepState.WAITING_FOR_APPROVAL);

    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 2)));
    assertThat(
            service.transitionStep(
                tenant, runId, stepKey, WorkflowStepState.RUNNING, "run.approved", null))
        .isEqualTo(WorkflowStepState.RUNNING);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static java.util.Set<WorkflowRunState> runTerminalStates() {
    java.util.Set<WorkflowRunState> terminal = new java.util.LinkedHashSet<>();
    for (WorkflowRunState state : WorkflowRunState.values()) {
      if (state.isTerminal()) {
        terminal.add(state);
      }
    }
    return terminal;
  }

  // Runs the transition and returns the thrown ResponseStatusException, or fails if none is thrown.
  private static ResponseStatusException expectException(Runnable action) {
    try {
      action.run();
    } catch (ResponseStatusException ex) {
      return ex;
    }
    throw new AssertionError("Expected a ResponseStatusException, but none was thrown");
  }
}
