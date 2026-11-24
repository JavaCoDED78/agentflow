package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.RunStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.RunView;
import com.example.agentflow.workflowexecution.persistence.StepStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The S13-11 transition-meter tests: every successful run transition increments {@code
 * agentflow.workflowexecution.run.total{state}} (target state, a bounded label) and terminal ones
 * additionally record {@code agentflow.workflowexecution.run.duration.seconds} (creation →
 * terminal, in seconds, the newest {@link Clock}); every step transition to CANCELLED increments
 * {@code agentflow.workflowexecution.step.cancelled.total}. Metering must never fail a transition.
 */
class RunStateMachineServiceMetricsTest {

  private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final WorkflowStepRunRepository stepRepository = mock(WorkflowStepRunRepository.class);
  private final WorkflowEventService eventService = mock(WorkflowEventService.class);

  private SimpleMeterRegistry registry;
  private RunStateMachineService service;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    service =
        new RunStateMachineService(
            runRepository,
            stepRepository,
            null,
            eventService,
            null,
            registry,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void run_transition_increments_the_run_total_counter_per_target_state() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.QUEUED, 5, 0)));

    service.transitionRun(tenant, runId, WorkflowRunState.RUNNING, "run.running", null);

    Counter running =
        registry.find(RunStateMachineService.RUN_TOTAL_METRIC).tag("state", "running").counter();
    assertThat(running).isNotNull();
    assertThat(running.count()).isEqualTo(1.0);
    // the non-terminal transition produced no duration sample
    assertThat(registry.find(RunStateMachineService.RUN_DURATION_METRIC).timer()).isNull();
  }

  @Test
  void terminal_transition_records_run_duration_from_creation_to_terminal_state() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.RUNNING, 9, 0)));
    when(runRepository.findRun(tenant, runId))
        .thenReturn(
            Optional.of(
                new RunView(
                    runId,
                    UUID.randomUUID(),
                    WorkflowRunState.SUCCEEDED,
                    1,
                    OffsetDateTime.of(2026, 11, 20, 11, 58, 0, 0, ZoneOffset.UTC))));

    service.transitionRun(tenant, runId, WorkflowRunState.SUCCEEDED, "run.succeeded", null);

    Timer duration = registry.find(RunStateMachineService.RUN_DURATION_METRIC).timer();
    assertThat(duration).isNotNull();
    assertThat(duration.count()).isEqualTo(1);
    // creation (11:58) -> terminal (12:00) = 120 seconds, in the registry's SECONDS-ish seconds
    assertThat(duration.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(120.0);
    Counter succeeded =
        registry.find(RunStateMachineService.RUN_TOTAL_METRIC).tag("state", "succeeded").counter();
    assertThat(succeeded).isNotNull();
    assertThat(succeeded.count()).isEqualTo(1.0);
  }

  @Test
  void terminal_transition_without_readable_run_records_no_duration() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(runRepository.findState(tenant, runId))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.RUNNING, 9, 0)));
    // findRun unstubbed -> Optional.empty() -> duration skipped, no exception, no timer

    service.transitionRun(tenant, runId, WorkflowRunState.SUCCEEDED, "run.succeeded", null);

    assertThat(registry.find(RunStateMachineService.RUN_DURATION_METRIC).timer()).isNull();
    Counter succeeded =
        registry.find(RunStateMachineService.RUN_TOTAL_METRIC).tag("state", "succeeded").counter();
    assertThat(succeeded.count()).isEqualTo(1.0);
  }

  @Test
  void step_transition_to_cancelled_increments_the_cancellation_counter_once() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID stepId = UUID.randomUUID();
    String stepKey = "tool_call";
    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(
            Optional.of(new StepStateSnapshot(stepId.toString(), WorkflowStepState.RUNNING, 1)));

    service.transitionStep(
        tenant, runId, stepKey, WorkflowStepState.CANCELLED, "step.cancelled", null);

    Counter cancelled = registry.find(RunStateMachineService.STEP_CANCELLED_METRIC).counter();
    assertThat(cancelled).isNotNull();
    assertThat(cancelled.count()).isEqualTo(1.0);
  }

  @Test
  void step_transition_not_to_cancelled_records_nothing() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID stepId = UUID.randomUUID();
    String stepKey = "tool_call";
    when(stepRepository.findState(tenant, runId, stepKey))
        .thenReturn(
            Optional.of(new StepStateSnapshot(stepId.toString(), WorkflowStepState.PENDING, 2)));

    service.transitionStep(tenant, runId, stepKey, WorkflowStepState.READY, "step.ready", null);

    assertThat(registry.find(RunStateMachineService.STEP_CANCELLED_METRIC).counter()).isNull();
  }
}
