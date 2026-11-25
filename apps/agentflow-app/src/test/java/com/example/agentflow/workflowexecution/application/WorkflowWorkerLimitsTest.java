package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowEdge;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import com.example.agentflow.workflowexecution.domain.StepExecutionContext;
import com.example.agentflow.workflowexecution.domain.StepHandler;
import com.example.agentflow.workflowexecution.domain.StepResult;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.ApprovalRepository;
import com.example.agentflow.workflowexecution.persistence.RunView;
import com.example.agentflow.workflowexecution.persistence.StepStatusRow;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DB-free tests for the per-run hard execution limits (S11-13): the worker stops a run in a
 * documented, controlled {@code FAILED} the moment a per-run budget is reached. Each test drives
 * one breach against a mocked transition / persistence / lease layer, so the worker's per-step gate
 * is asserted in isolation — the SQL those mocks stand in for is covered by the repository and
 * integration suites. DB-free; time is injected (invariant 10).
 *
 * <p>The observed step state is a mutable {@code stepKey -> state} map the mocked transition
 * service mutates and the mocked status repository reads, so each pass of the event loop observes
 * the state left by the previous one — exactly as a real transaction persists and re-reads it.
 */
class WorkflowWorkerLimitsTest {

  private static final int LEASE_EPOCH = 3;
  private static final Instant RUN_STARTED = Instant.parse("2026-06-01T12:00:00Z");

  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final WorkflowStepRunRepository stepRepository = mock(WorkflowStepRunRepository.class);
  private final WorkflowVersionService versionService = mock(WorkflowVersionService.class);
  private final RunLeaseService leaseService = mock(RunLeaseService.class);
  private final RunStateMachineService transitionService = mock(RunStateMachineService.class);
  private final CheckpointAdapter checkpointAdapter = mock(CheckpointAdapter.class);
  private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);

  // ---------------------------------------------------------------------------
  // STEPS budget: a run at its step budget stops before the next handler runs.
  // ---------------------------------------------------------------------------

  @Test
  void a_run_at_the_step_budget_closes_the_step_and_run_to_a_limited_failed() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    stepState.put("b", WorkflowStepState.PENDING);
    WorkflowGraph graph =
        new WorkflowGraph(List.of(node("a", "NOOP"), node("b", "NOOP")), List.of(edge("a", "b")));

    Harness h = new Harness(stepState, tenant, runId, versionId);
    h.configure(graph);
    h.execution.setMaxSteps(1); // the first step is the only one allowed

    WorkflowWorker worker = h.build(List.of(new DeterministicStepHandler()));

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isOne();
    // step a ran to completion; step b was rejected at the gate before its handler ran
    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.SUCCEEDED);
    assertThat(stepState.get("b")).isEqualTo(WorkflowStepState.FAILED);
    // the breach is recorded on the step transition and the run transition, both as limit_exceeded
    verify(transitionService)
        .transitionStep(
            any(),
            any(),
            eq("b"),
            eq(WorkflowStepState.FAILED),
            eq("step.failed"),
            eq("limit_exceeded"),
            any(),
            anyInt());
    verifyRunFailed("run.limit-exceeded");
  }

  // ---------------------------------------------------------------------------
  // WALL-CLOCK budget: a run past its wall-clock duration stops before the next handler runs.
  // ---------------------------------------------------------------------------

  @Test
  void a_run_past_the_wall_clock_budget_closes_the_step_and_run_to_a_limited_failed() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a", "NOOP")), List.of());

    Harness h = new Harness(stepState, tenant, runId, versionId);
    h.configure(graph);
    h.execution.setMaxWallClockSeconds(5);
    Clock clock = advancingClock(RUN_STARTED);

    WorkflowWorker worker = h.build(List.of(new DeterministicStepHandler()), clock);

    worker.pollOnce(tenant);

    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.FAILED);
    verify(transitionService)
        .transitionStep(
            any(),
            any(),
            eq("a"),
            eq(WorkflowStepState.FAILED),
            eq("step.failed"),
            eq("limit_exceeded"),
            any(),
            anyInt());
    verifyRunFailed("run.limit-exceeded");
  }

  // ---------------------------------------------------------------------------
  // TOOL-CALL budget: a run past its tool-call budget stops before the next handler runs.
  // ---------------------------------------------------------------------------

  @Test
  void a_run_at_the_tool_call_budget_closes_the_run_to_a_limited_failed() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    for (String n : List.of("a", "b", "c")) {
      stepState.put(n, WorkflowStepState.PENDING);
    }
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(node("a", "TOOL"), node("b", "TOOL"), node("c", "TOOL")),
            List.of(edge("a", "b"), edge("b", "c")));

    Harness h = new Harness(stepState, tenant, runId, versionId);
    h.configure(graph);
    h.execution.setMaxToolCalls(1); // each step reports one tool call

    WorkflowWorker worker = h.build(List.of(toolReporting()), advancingClock(RUN_STARTED));

    worker.pollOnce(tenant);

    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.SUCCEEDED);
    assertThat(stepState.get("b")).isEqualTo(WorkflowStepState.SUCCEEDED);
    assertThat(stepState.get("c")).isEqualTo(WorkflowStepState.FAILED);
    verify(transitionService)
        .transitionStep(
            any(),
            any(),
            eq("c"),
            eq(WorkflowStepState.FAILED),
            eq("step.failed"),
            eq("limit_exceeded"),
            any(),
            anyInt());
    verifyRunFailed("run.limit-exceeded");
  }

  // ---------------------------------------------------------------------------
  // OUTPUT-SIZE budget: a step whose output exceeds the bound is rejected.
  // ---------------------------------------------------------------------------

  @Test
  void a_step_with_output_larger_than_the_bound_closes_the_run_to_a_limited_failed() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a", "OUT")), List.of());

    Harness h = new Harness(stepState, tenant, runId, versionId);
    h.configure(graph);
    h.execution.setMaxOutputBytes(10L);

    WorkflowWorker worker = h.build(List.of(bigOutputHandler()));

    worker.pollOnce(tenant);

    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.FAILED);
    verify(transitionService)
        .transitionStep(
            any(),
            any(),
            eq("a"),
            eq(WorkflowStepState.FAILED),
            eq("step.failed"),
            eq("limit_exceeded"),
            any(),
            anyInt());
    verifyRunFailed("run.limit-exceeded");
  }

  // ---------------------------------------------------------------------------
  // PARALLEL-CALL budget: a handler over the concurrent-call cap closes the run.
  // ---------------------------------------------------------------------------

  @Test
  void a_step_handler_over_the_parallel_cap_closes_the_run_to_a_limited_failed() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a", "PAR")), List.of());

    Harness h = new Harness(stepState, tenant, runId, versionId);
    h.configure(graph);
    h.execution.setMaxParallelCalls(1);

    WorkflowWorker worker = h.build(List.of(parallelHandler()));

    worker.pollOnce(tenant);

    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.FAILED);
    verify(transitionService)
        .transitionStep(
            any(),
            any(),
            eq("a"),
            eq(WorkflowStepState.FAILED),
            eq("step.failed"),
            eq("limit_exceeded"),
            any(),
            anyInt());
    verifyRunFailed("run.limit-exceeded");
  }

  // ---------------------------------------------------------------------------
  // RETRIES budget: a run whose retry budget is exhausted stops instead of retrying.
  // ---------------------------------------------------------------------------

  @Test
  void a_run_at_the_retry_budget_closes_the_run_to_a_limited_failed() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a", "THROW")), List.of());

    Harness h = new Harness(stepState, tenant, runId, versionId);
    h.configure(graph);
    h.execution.setMaxRetries(0); // no retry is permitted — the first transient fault is terminal

    WorkflowWorker worker = h.build(List.of(retryableFault()));

    int processed = worker.pollOnce(tenant);

    // the run stops instead of parking the step for a retry
    assertThat(processed).isOne();
    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.FAILED);
    verify(transitionService)
        .transitionStep(
            any(),
            any(),
            eq("a"),
            eq(WorkflowStepState.FAILED),
            eq("step.failed"),
            eq("limit_exceeded"),
            any(),
            anyInt());
    verifyRunFailed("run.limit-exceeded");
  }

  // ---------------------------------------------------------------------------
  // A run that stays within every budget completes normally (the limits do not fire spuriously).
  // ---------------------------------------------------------------------------

  @Test
  void a_run_within_every_budget_completes_successfully() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    stepState.put("b", WorkflowStepState.PENDING);
    WorkflowGraph graph =
        new WorkflowGraph(List.of(node("a", "NOOP"), node("b", "NOOP")), List.of(edge("a", "b")));

    Harness h = new Harness(stepState, tenant, runId, versionId);
    h.configure(graph);
    // modest but generous bounds
    h.execution.setMaxSteps(100L);
    h.execution.setMaxToolCalls(100L);
    h.execution.setMaxRetries(5L);
    h.execution.setMaxWallClockSeconds(3600L);
    h.execution.setMaxOutputBytes(1_000_000L);
    h.execution.setMaxParallelCalls(4);

    WorkflowWorker worker = h.build(List.of(new DeterministicStepHandler()));

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isOne();
    assertThat(stepState).containsEntry("a", WorkflowStepState.SUCCEEDED);
    assertThat(stepState).containsEntry("b", WorkflowStepState.SUCCEEDED);
    verify(transitionService, never())
        .transitionStep(
            any(), any(), anyString(), any(), any(), eq("limit_exceeded"), any(), anyInt());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static StepHandler toolReporting() {
    return new StepHandler() {
      @Override
      public boolean supports(String nodeType) {
        return "TOOL".equals(nodeType);
      }

      @Override
      public StepResult handle(StepExecutionContext context) {
        // S11-13: the handler reports each tool call into the per-run monitor
        context.budget().recordToolCall();
        return StepResult.succeeded("done:" + context.stepKey());
      }
    };
  }

  private static StepHandler bigOutputHandler() {
    return new StepHandler() {
      @Override
      public boolean supports(String nodeType) {
        return "OUT".equals(nodeType);
      }

      @Override
      public StepResult handle(StepExecutionContext context) {
        return StepResult.succeeded("x".repeat(1000)); // 1000 bytes, far over the 10-byte bound
      }
    };
  }

  private static StepHandler parallelHandler() {
    return new StepHandler() {
      @Override
      public boolean supports(String nodeType) {
        return "PAR".equals(nodeType);
      }

      @Override
      public StepResult handle(StepExecutionContext context) {
        // S11-13: the handler opens one slot, then a second over the cap of 1 — the monitor flags
        // the breach even though the handler does not throw
        context.budget().beginParallelCall();
        context.budget().beginParallelCall();
        return StepResult.succeeded("done:" + context.stepKey());
      }
    };
  }

  private static StepHandler retryableFault() {
    return new StepHandler() {
      @Override
      public boolean supports(String nodeType) {
        return "THROW".equals(nodeType);
      }

      @Override
      public StepResult handle(StepExecutionContext context) {
        throw new RuntimeException("connection refused"); // classified transient_infra
      }
    };
  }

  private static Clock advancingClock(Instant start) {
    final AtomicLong index = new AtomicLong(0);
    return new Clock() {
      @Override
      public Instant instant() {
        return start.plusSeconds(index.incrementAndGet() * 100L);
      }

      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }
    };
  }

  private void verifyRunFailed(String eventType) {
    ArgumentCaptor<WorkflowRunState> runState = ArgumentCaptor.forClass(WorkflowRunState.class);
    verify(transitionService)
        .transitionRun(
            any(),
            any(),
            runState.capture(),
            eq(eventType),
            any(),
            eq("limit_exceeded"),
            any(),
            anyInt());
    assertThat(runState.getValue()).isEqualTo(WorkflowRunState.FAILED);
  }

  private static WorkflowNode node(String id, String type) {
    return new WorkflowNode(id, type, new LinkedHashMap<>());
  }

  private static WorkflowEdge edge(String from, String to) {
    return new WorkflowEdge(from, to);
  }

  /**
   * Owns the shared mocks + observed state for one test, and configures them to drive a single
   * tenant/run. Non-static so it reads the test's mock fields; shared so each limit test asserts
   * only its own breach.
   */
  private final class Harness {
    final Map<String, WorkflowStepState> stepState;
    final UUID tenant;
    final UUID runId;
    final UUID versionId;
    AgentFlowProperties.Execution execution = new AgentFlowProperties.Execution();

    Harness(Map<String, WorkflowStepState> stepState, UUID tenant, UUID runId, UUID versionId) {
      this.stepState = stepState;
      this.tenant = tenant;
      this.runId = runId;
      this.versionId = versionId;
    }

    Harness configure(WorkflowGraph graph) {
      when(leaseService.claim(any(), any())).thenReturn(LEASE_EPOCH);
      when(runRepository.findClaimableRuns(tenant)).thenReturn(List.of(runId));
      when(runRepository.findRun(tenant, runId))
          .thenReturn(
              Optional.of(
                  new RunView(runId, versionId, WorkflowRunState.QUEUED, 1, OffsetDateTime.now())));
      when(versionService.loadGraph(tenant, versionId)).thenReturn(graph);
      when(stepRepository.findStepStatuses(tenant, runId))
          .thenAnswer(
              inv ->
                  stepState.entrySet().stream()
                      .map(e -> new StepStatusRow(e.getKey(), e.getValue(), LEASE_EPOCH))
                      .collect(Collectors.toList()));
      when(stepRepository.findStepOutputs(any(), any())).thenReturn(Collections.emptyMap());
      doNothing().when(stepRepository).createStep(any(), any(), anyString(), anyInt(), any());
      // the clean (6-arg) and error (7-arg) step transitions both mutate the observed state, so a
      // transition persists and the next pass re-reads it — exactly as a real transaction does
      when(transitionService.transitionStep(
              any(), any(), anyString(), any(), any(), any(), anyInt()))
          .thenAnswer(
              inv -> {
                stepState.put(inv.getArgument(2), inv.getArgument(3));
                return inv.getArgument(3);
              });
      when(transitionService.transitionStep(
              any(), any(), anyString(), any(), any(), any(), any(), anyInt()))
          .thenAnswer(
              inv -> {
                stepState.put(inv.getArgument(2), inv.getArgument(3));
                return inv.getArgument(3);
              });
      return this;
    }

    WorkflowWorker build(List<StepHandler> handlers) {
      return build(handlers, Clock.systemUTC());
    }

    WorkflowWorker build(List<StepHandler> handlers, Clock clock) {
      AgentFlowProperties properties = mock(AgentFlowProperties.class);
      when(properties.getWorker()).thenReturn(new AgentFlowProperties.Worker());
      when(properties.getExecution()).thenReturn(execution);
      return new WorkflowWorker(
          runRepository,
          stepRepository,
          versionService,
          leaseService,
          null,
          transitionService,
          checkpointAdapter,
          approvalRepository,
          handlers,
          properties,
          clock);
    }
  }
}
