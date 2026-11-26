package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowEdge;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import com.example.agentflow.workflowexecution.domain.ApprovalPayloadFingerprint;
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
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DB-free tests for the worker event loop ({@link WorkflowWorker}, S07-08). The persistence,
 * transition, lease, checkpoint and graph-loader collaborators are mocks, so the loop's own control
 * flow is asserted in isolation: claiming, ready-step selection, predecessor cascade, checkpoint on
 * success and run reconciliation. The SQL these mocks stand in for is covered by the repository and
 * integration suites.
 *
 * <p>The step state the worker sees is backed by a mutable {@code stepKey -> state} map that the
 * mocked transition service mutates and the mocked status repository reads, so each pass of the
 * event loop observes the state left by the previous one (exactly as a real transaction would
 * persist and re-read it). A {@code NOOP} {@link StepHandler} succeeds every step; a second handler
 * fails a {@code FAIL} node so the cascade-skip path is exercised.
 */
class WorkflowWorkerTest {

  private static final int LEASE_EPOCH = 3;

  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final WorkflowStepRunRepository stepRepository = mock(WorkflowStepRunRepository.class);
  private final WorkflowVersionService versionService = mock(WorkflowVersionService.class);
  private final RunLeaseService leaseService = mock(RunLeaseService.class);
  private final RunStateMachineService transitionService = mock(RunStateMachineService.class);
  private final CheckpointAdapter checkpointAdapter = mock(CheckpointAdapter.class);
  private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);

  @Test
  void run_a_two_step_chain_to_success_and_reaches_every_node() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    stepState.put("b", WorkflowStepState.PENDING);
    WorkflowGraph graph =
        new WorkflowGraph(List.of(node("a", "NOOP"), node("b", "NOOP")), List.of(edge("a", "b")));

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
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
    // NOOP handler: succeeds every step
    StepHandler noop = new DeterministicStepHandler();
    WorkflowWorker worker = buildWorker(List.of(noop));

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    // both nodes reached SUCCEEDED through the state machine
    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.SUCCEEDED);
    assertThat(stepState.get("b")).isEqualTo(WorkflowStepState.SUCCEEDED);
    // a checkpoint was persisted for each completed step
    ArgumentCaptor<String> stepKey = ArgumentCaptor.forClass(String.class);
    verify(checkpointAdapter, org.mockito.Mockito.times(2))
        .saveCheckpoint(any(), any(), stepKey.capture(), anyInt(), any(), any());
    assertThat(stepKey.getAllValues()).containsExactly("a", "b");
    // the run is reconciled to SUCCEEDED once every step is terminal and successful
    ArgumentCaptor<WorkflowRunState> runState = ArgumentCaptor.forClass(WorkflowRunState.class);
    // transitionRun is called twice: the start of the lease (RUNNING) and the final reconcile
    verify(transitionService, times(2))
        .transitionRun(any(), any(), runState.capture(), anyString(), any(), anyInt());
    List<WorkflowRunState> runStates = runState.getAllValues();
    assertThat(runStates).contains(WorkflowRunState.RUNNING);
    // the final reconcile moves the run to SUCCEEDED once every step succeeded
    assertThat(runStates.get(runStates.size() - 1)).isEqualTo(WorkflowRunState.SUCCEEDED);
  }

  @Test
  void cascades_skip_to_a_failed_predecessor_and_fails_the_run() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    stepState.put("b", WorkflowStepState.PENDING);
    WorkflowGraph graph =
        new WorkflowGraph(List.of(node("a", "FAIL"), node("b", "NOOP")), List.of(edge("a", "b")));

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
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
    // FAIL node fails immediately; NOOP node succeeds
    StepHandler fail =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "FAIL".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            return StepResult.failed();
          }
        };
    StepHandler noop = new DeterministicStepHandler();
    WorkflowWorker worker = buildWorker(List.of(fail, noop));

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    // the failed predecessor cascades a skip to its dependent step
    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.FAILED);
    assertThat(stepState.get("b")).isEqualTo(WorkflowStepState.SKIPPED);
    // the run fails because not every step succeeded (a failed predecessor is a decided outcome)
    ArgumentCaptor<WorkflowRunState> runState = ArgumentCaptor.forClass(WorkflowRunState.class);
    // transitionRun is called twice: the start of the lease (RUNNING) and the final reconcile
    verify(transitionService, times(2))
        .transitionRun(any(), any(), runState.capture(), anyString(), any(), anyInt());
    List<WorkflowRunState> runStates = runState.getAllValues();
    assertThat(runStates).contains(WorkflowRunState.RUNNING);
    // the run fails because a step permanently failed and its dependent was skipped
    assertThat(runStates.get(runStates.size() - 1)).isEqualTo(WorkflowRunState.FAILED);
  }

  @Test
  void completes_a_diamond_of_dependent_steps() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    for (String n : List.of("a", "b", "c", "d")) {
      stepState.put(n, WorkflowStepState.PENDING);
    }
    // a -> b, a -> c, b -> d, c -> d : d has two predecessors and must wait for both
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(node("a", "NOOP"), node("b", "NOOP"), node("c", "NOOP"), node("d", "NOOP")),
            List.of(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d")));

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
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
    StepHandler noop = new DeterministicStepHandler();
    WorkflowWorker worker = buildWorker(List.of(noop));

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    assertThat(stepState).containsEntry("a", WorkflowStepState.SUCCEEDED);
    assertThat(stepState).containsEntry("b", WorkflowStepState.SUCCEEDED);
    assertThat(stepState).containsEntry("c", WorkflowStepState.SUCCEEDED);
    assertThat(stepState).containsEntry("d", WorkflowStepState.SUCCEEDED);
    // d only ran after both b and c succeeded
    ArgumentCaptor<WorkflowRunState> runState = ArgumentCaptor.forClass(WorkflowRunState.class);
    // transitionRun is called twice: the start of the lease (RUNNING) and the final reconcile
    verify(transitionService, times(2))
        .transitionRun(any(), any(), runState.capture(), anyString(), any(), anyInt());
    List<WorkflowRunState> runStates = runState.getAllValues();
    assertThat(runStates).contains(WorkflowRunState.RUNNING);
    // the final reconcile moves the run to SUCCEEDED once every step succeeded
    assertThat(runStates.get(runStates.size() - 1)).isEqualTo(WorkflowRunState.SUCCEEDED);
  }

  @Test
  void executes_ready_steps_in_topological_order_regardless_of_declaration_order() {
    // S08-11: the graph declares its nodes in an order that violates the topology; the worker must
    // still execute a -> b, a -> c, (b, c) -> d with every edge respected and the free-choice
    // tie broken by declaration order — never by the incidental ordering of a collection.
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    for (String n : List.of("d", "b", "c", "a")) {
      stepState.put(n, WorkflowStepState.PENDING);
    }
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(node("d", "NOOP"), node("b", "NOOP"), node("c", "NOOP"), node("a", "NOOP")),
            List.of(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d")));

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
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
    List<String> executionOrder = new ArrayList<>();
    StepHandler recording =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "NOOP".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            executionOrder.add(context.stepKey());
            return StepResult.succeeded("done:" + context.stepKey());
          }
        };
    WorkflowWorker worker = buildWorker(List.of(recording));

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    // the execution order follows the graph edges exactly, independent of the scrambled
    // declaration order: a before b and c, and b and c before d
    assertThat(executionOrder).containsExactly("a", "b", "c", "d");
    assertThat(stepState)
        .containsEntry("a", WorkflowStepState.SUCCEEDED)
        .containsEntry("b", WorkflowStepState.SUCCEEDED)
        .containsEntry("c", WorkflowStepState.SUCCEEDED)
        .containsEntry("d", WorkflowStepState.SUCCEEDED);
  }

  @Test
  void does_nothing_when_the_tenant_has_no_claimable_run() {
    when(leaseService.claim(any(), any())).thenReturn(LEASE_EPOCH);
    when(runRepository.findClaimableRuns(any())).thenReturn(Collections.emptyList());
    WorkflowWorker worker = buildWorker(List.of(new DeterministicStepHandler()));

    int processed = worker.pollOnce(UUID.randomUUID());

    assertThat(processed).isEqualTo(0);
    verify(transitionService, never()).transitionRun(any(), any(), any(), any(), any(), anyInt());
    verify(runRepository, never()).findRun(any(), any());
  }

  @Test
  void does_nothing_when_the_run_could_not_be_claimed() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    // another worker holds the lease / the run is terminal / a cross-tenant id: claim returns -1
    when(leaseService.claim(tenant, runId)).thenReturn(-1);
    when(runRepository.findClaimableRuns(tenant)).thenReturn(List.of(runId));
    WorkflowWorker worker = buildWorker(List.of(new DeterministicStepHandler()));

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(0);
    verify(runRepository, never()).findRun(any(), any());
    verify(transitionService, never()).transitionRun(any(), any(), any(), any(), any(), anyInt());
  }

  // ---------------------------------------------------------------------------
  // S07-11: a handler that throws is classified; transient faults retry, others are terminal
  // ---------------------------------------------------------------------------

  @Test
  void a_transient_fault_is_classified_and_parks_the_step_for_a_retry() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    String stepKey = "a";

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put(stepKey, WorkflowStepState.PENDING);
    WorkflowGraph graph = new WorkflowGraph(List.of(node(stepKey, "THROW")), List.of());

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
    // the 8-arg (fault) overload mutates the observed state like the 6-arg one does
    when(transitionService.transitionStep(
            any(), any(), anyString(), any(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
    // the fault handler throws a transient infra fault (a connection the retry can re-establish)
    StepHandler throwing =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "THROW".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            // The handler SPI declares no checked throws, so a connection fault is surfaced as an
            // unchecked fault whose message the classifier recognises (transient_infra).
            throw new RuntimeException("connection refused");
          }
        };
    WorkflowWorker worker = buildWorker(List.of(throwing));

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    // a transient fault is not terminal: the step is parked in RETRY_WAIT for a bounded retry
    assertThat(stepState.get(stepKey)).isEqualTo(WorkflowStepState.RETRY_WAIT);
    // the run is left RUNNING for the next poll: reconcileRun sees a non-terminal step and does not
    // conclude the run, so a retry can pick it up (this poll's only transitionRun is the lease
    // start)
    ArgumentCaptor<WorkflowRunState> runState = ArgumentCaptor.forClass(WorkflowRunState.class);
    verify(transitionService, times(1))
        .transitionRun(any(), any(), runState.capture(), anyString(), any(), anyInt());
    assertThat(runState.getAllValues()).containsExactly(WorkflowRunState.RUNNING);
    // the classifier produced the transient class and the worker recorded it on the fault
    // transition
    ArgumentCaptor<String> errorClass = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(transitionService)
        .transitionStep(
            any(),
            any(),
            anyString(),
            any(),
            eq("step.retry_wait"),
            errorClass.capture(),
            reason.capture(),
            anyInt());
    assertThat(errorClass.getValue()).isEqualTo("transient_infra");
    assertThat(reason.getValue()).contains("connection refused");
  }

  @Test
  void a_permanent_fault_is_classified_and_moves_the_step_to_failed_and_fails_the_run() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    String stepKey = "a";

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put(stepKey, WorkflowStepState.PENDING);
    WorkflowGraph graph = new WorkflowGraph(List.of(node(stepKey, "THROW")), List.of());

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
    when(transitionService.transitionStep(
            any(), any(), anyString(), any(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
    // the fault handler throws an unrecognised exception: a permanent fault (INTERNAL)
    StepHandler throwing =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "THROW".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            throw new RuntimeException("something unexpected happened");
          }
        };
    WorkflowWorker worker = buildWorker(List.of(throwing));

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    // a permanent fault is terminal: the step is FAILED
    assertThat(stepState.get(stepKey)).isEqualTo(WorkflowStepState.FAILED);
    // the run is reconciled to FAILED because a step permanently failed
    ArgumentCaptor<WorkflowRunState> runState = ArgumentCaptor.forClass(WorkflowRunState.class);
    verify(transitionService, times(2))
        .transitionRun(any(), any(), runState.capture(), anyString(), any(), anyInt());
    assertThat(runState.getAllValues().get(runState.getAllValues().size() - 1))
        .isEqualTo(WorkflowRunState.FAILED);
    // the classifier produced a permanent class and the worker recorded it on the fault transition
    ArgumentCaptor<String> errorClass = ArgumentCaptor.forClass(String.class);
    verify(transitionService)
        .transitionStep(
            any(),
            any(),
            anyString(),
            any(),
            eq("step.failed"),
            errorClass.capture(),
            any(),
            anyInt());
    assertThat(errorClass.getValue()).isEqualTo("internal");
  }

  @Test
  void cancel_a_queued_run_cancels_the_not_started_steps_and_closes_the_run() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    stepState.put("b", WorkflowStepState.PENDING);
    WorkflowGraph graph =
        new WorkflowGraph(List.of(node("a", "NOOP"), node("b", "NOOP")), List.of(edge("a", "b")));

    when(leaseService.claim(any(), any())).thenReturn(LEASE_EPOCH);
    when(runRepository.findClaimableRuns(tenant)).thenReturn(List.of(runId));
    when(runRepository.findRun(tenant, runId))
        .thenReturn(
            Optional.of(
                new RunView(
                    runId, versionId, WorkflowRunState.CANCEL_REQUESTED, 1, OffsetDateTime.now())));
    when(versionService.loadGraph(tenant, versionId)).thenReturn(graph);
    when(stepRepository.findStepStatuses(tenant, runId))
        .thenAnswer(
            inv ->
                stepState.entrySet().stream()
                    .map(e -> new StepStatusRow(e.getKey(), e.getValue(), LEASE_EPOCH))
                    .collect(Collectors.toList()));
    doNothing().when(stepRepository).createStep(any(), any(), anyString(), anyInt(), any());
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });

    int processed = buildWorker(List.of(new DeterministicStepHandler())).pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    // the not-yet-started steps are cancelled, nothing is executed
    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.CANCELLED);
    assertThat(stepState.get("b")).isEqualTo(WorkflowStepState.CANCELLED);
    // a cancelled run never starts: the only run transition is the drain closing it to CANCELLED
    ArgumentCaptor<WorkflowRunState> runState = ArgumentCaptor.forClass(WorkflowRunState.class);
    verify(transitionService, times(1))
        .transitionRun(any(), any(), runState.capture(), anyString(), any(), anyInt());
    assertThat(runState.getAllValues()).containsExactly(WorkflowRunState.CANCELLED);
  }

  @Test
  void cancel_preserves_a_completed_step_and_cancels_only_the_pending_one() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    // step "a" already completed successfully; "b" is a dependent step that has not started.
    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.SUCCEEDED);
    stepState.put("b", WorkflowStepState.PENDING);
    WorkflowGraph graph =
        new WorkflowGraph(List.of(node("a", "NOOP"), node("b", "NOOP")), List.of(edge("a", "b")));

    when(leaseService.claim(any(), any())).thenReturn(LEASE_EPOCH);
    when(runRepository.findClaimableRuns(tenant)).thenReturn(List.of(runId));
    when(runRepository.findRun(tenant, runId))
        .thenReturn(
            Optional.of(
                new RunView(
                    runId, versionId, WorkflowRunState.CANCEL_REQUESTED, 1, OffsetDateTime.now())));
    when(versionService.loadGraph(tenant, versionId)).thenReturn(graph);
    when(stepRepository.findStepStatuses(tenant, runId))
        .thenAnswer(
            inv ->
                stepState.entrySet().stream()
                    .map(e -> new StepStatusRow(e.getKey(), e.getValue(), LEASE_EPOCH))
                    .collect(Collectors.toList()));
    when(stepRepository.findStepOutputs(any(), any()))
        .thenReturn(Collections.singletonMap("a", "a-done"));
    assertThat(stepRepository.findStepOutputs(tenant, runId)).containsEntry("a", "a-done");
    doNothing().when(stepRepository).createStep(any(), any(), anyString(), anyInt(), any());
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });

    int processed = buildWorker(List.of(new DeterministicStepHandler())).pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    // the completed step keeps its result: cancel never erases a completed result (invariant 4)
    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.SUCCEEDED);
    assertThat(stepRepository.findStepOutputs(tenant, runId)).containsEntry("a", "a-done");
    // the not-yet-started dependent step is cancelled
    assertThat(stepState.get("b")).isEqualTo(WorkflowStepState.CANCELLED);
    // the run closes to CANCELLED once every step is terminal
    ArgumentCaptor<WorkflowRunState> runState = ArgumentCaptor.forClass(WorkflowRunState.class);
    verify(transitionService, times(1))
        .transitionRun(any(), any(), runState.capture(), anyString(), any(), anyInt());
    assertThat(runState.getAllValues()).containsExactly(WorkflowRunState.CANCELLED);
  }

  @Test
  void resolves_step_inputs_from_predecessor_outputs_before_the_handler_runs() {
    // S08-12: the downstream node's {node, output} wiring resolves against the upstream step's
    // persisted output document, and the handler receives the resolved values in its context.
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    stepState.put("b", WorkflowStepState.PENDING);
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode(
                    "a",
                    "NOOP",
                    new LinkedHashMap<>(),
                    Map.of(),
                    Map.of("summary", "the produced summary")),
                new WorkflowNode(
                    "b",
                    "RECORD",
                    new LinkedHashMap<>(),
                    Map.of("doc", Map.of("node", "a", "output", "summary"), "tone", "terse"),
                    Map.of())),
            List.of(edge("a", "b")));

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
    when(stepRepository.findStepOutputs(tenant, runId))
        .thenReturn(Map.of("a", "{\"summary\":\"the produced summary\"}"));
    doNothing().when(stepRepository).createStep(any(), any(), anyString(), anyInt(), any());
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
    List<Map<String, Object>> recorded = new ArrayList<>();
    StepHandler recording =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "RECORD".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            recorded.add(context.inputs());
            return StepResult.succeeded("b-done");
          }
        };
    WorkflowWorker worker = buildWorker(List.of(new DeterministicStepHandler(), recording));

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.SUCCEEDED);
    assertThat(stepState.get("b")).isEqualTo(WorkflowStepState.SUCCEEDED);
    // the handler saw the resolved wiring: the reference replaced with the upstream value, the
    // literal passed through
    assertThat(recorded).hasSize(1);
    assertThat(recorded.get(0))
        .containsEntry("doc", "the produced summary")
        .containsEntry("tone", "terse");
  }

  @Test
  void an_unknown_output_path_fails_the_step_before_the_handler_and_any_tool() {
    // S08-12: a wiring that points at an output the upstream node does not declare is rejected
    // with a classified contract failure (validation, permanent — never retried) before the
    // handler is invoked.
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    stepState.put("b", WorkflowStepState.PENDING);
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode(
                    "a",
                    "NOOP",
                    new LinkedHashMap<>(),
                    Map.of(),
                    Map.of("summary", "the produced summary")),
                new WorkflowNode(
                    "b",
                    "RECORD",
                    new LinkedHashMap<>(),
                    Map.of("doc", Map.of("node", "a", "output", "secret")),
                    Map.of())),
            List.of(edge("a", "b")));

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
    when(stepRepository.findStepOutputs(tenant, runId))
        .thenReturn(Map.of("a", "{\"summary\":\"the produced summary\"}"));
    doNothing().when(stepRepository).createStep(any(), any(), anyString(), anyInt(), any());
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
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
    List<Map<String, Object>> recorded = new ArrayList<>();
    StepHandler recording =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "RECORD".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            recorded.add(context.inputs());
            return StepResult.succeeded("b-done");
          }
        };
    WorkflowWorker worker = buildWorker(List.of(new DeterministicStepHandler(), recording));

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    // the upstream step completed; the downstream step failed as a contract violation
    assertThat(stepState.get("a")).isEqualTo(WorkflowStepState.SUCCEEDED);
    assertThat(stepState.get("b")).isEqualTo(WorkflowStepState.FAILED);
    // the handler was never invoked: the wiring was rejected before any call
    assertThat(recorded).isEmpty();
    // the classifier produced the permanent `validation` class and the worker recorded it
    ArgumentCaptor<String> errorClass = ArgumentCaptor.forClass(String.class);
    verify(transitionService)
        .transitionStep(
            any(),
            any(),
            anyString(),
            any(),
            eq("step.failed"),
            errorClass.capture(),
            any(),
            anyInt());
    assertThat(errorClass.getValue()).isEqualTo("validation");
    // the run is reconciled to FAILED because a step permanently failed
    ArgumentCaptor<WorkflowRunState> runState = ArgumentCaptor.forClass(WorkflowRunState.class);
    verify(transitionService, times(2))
        .transitionRun(any(), any(), runState.capture(), anyString(), any(), anyInt());
    assertThat(runState.getAllValues().get(runState.getAllValues().size() - 1))
        .isEqualTo(WorkflowRunState.FAILED);
  }

  @Test
  void a_human_approval_pause_survives_a_restart_without_skipping_the_side_effect() {
    // S11-03 acceptance: a restart neither skips the approval nor leaves a worker thread occupied.
    // The graph gates a side effect ("send-email") behind a HUMAN_APPROVAL step ("send-report").
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("send-report", WorkflowStepState.PENDING);
    stepState.put("send-email", WorkflowStepState.PENDING);
    // the gate node carries the redacted "action" the decision binds to (never the secret payload)
    Map<String, Object> gateConfig = new LinkedHashMap<>();
    gateConfig.put("action", "Send the monthly financial report to finance@acme.example");
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(
                new WorkflowNode("send-report", "HUMAN_APPROVAL", gateConfig),
                node("send-email", "NOOP")),
            List.of(edge("send-report", "send-email")));

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
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
    // a recording handler proves the gated downstream side effect never runs while paused
    List<String> downstreamRuns = new ArrayList<>();
    StepHandler noop =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "NOOP".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            downstreamRuns.add(context.stepKey());
            return StepResult.succeeded("email sent");
          }
        };

    // worker 1 drives the run until it pauses at the approval boundary
    WorkflowWorker worker = buildWorker(List.of(noop));
    worker.pollOnce(tenant);
    // the gate parked in WAITING_FOR_APPROVAL and never reached SUCCEEDED; the downstream is still
    // PENDING (its predecessor did not succeed), and the gated side effect never ran (invariant 7)
    assertThat(stepState.get("send-report")).isEqualTo(WorkflowStepState.WAITING_FOR_APPROVAL);
    assertThat(stepState.get("send-email")).isEqualTo(WorkflowStepState.PENDING);
    assertThat(downstreamRuns).isEmpty();
    // the durable approval was recorded at the pause (the checkpoint of where execution stopped),
    // and the payload fingerprint it pins is the exact digest the decision re-validates (S11-06) —
    // both halves share one algorithm, so a drifted binding can never pass the decision
    ArgumentCaptor<String> pinnedFingerprint = ArgumentCaptor.forClass(String.class);
    verify(approvalRepository)
        .create(
            eq(tenant),
            eq(runId),
            eq("send-report"),
            anyString(),
            pinnedFingerprint.capture(),
            any(),
            any());
    assertThat(pinnedFingerprint.getValue())
        .isEqualTo(ApprovalPayloadFingerprint.compute("send-report", gateConfig));
    // the run is parked in WAITING_FOR_APPROVAL (the higher-level pause), not concluded; the step
    // ran READY -> RUNNING -> WAITING_FOR_APPROVAL
    ArgumentCaptor<WorkflowRunState> runState = ArgumentCaptor.forClass(WorkflowRunState.class);
    verify(transitionService, times(2))
        .transitionRun(any(), any(), runState.capture(), anyString(), any(), anyInt());
    assertThat(runState.getAllValues())
        .containsExactly(WorkflowRunState.RUNNING, WorkflowRunState.WAITING_FOR_APPROVAL);
    ArgumentCaptor<WorkflowStepState> stepTo = ArgumentCaptor.forClass(WorkflowStepState.class);
    verify(transitionService, times(3))
        .transitionStep(
            any(), any(), eq("send-report"), stepTo.capture(), anyString(), any(), anyInt());
    assertThat(stepTo.getAllValues())
        .containsExactly(
            WorkflowStepState.READY,
            WorkflowStepState.RUNNING,
            WorkflowStepState.WAITING_FOR_APPROVAL);
    // the run's lease is released: a paused run is not a live owner while it waits (S11-03)
    verify(runRepository).releaseLease(tenant, runId, LEASE_EPOCH);

    // a restart: a fresh worker claims the run again and drives it (same persisted stepState)
    worker = buildWorker(List.of(noop));
    worker.pollOnce(tenant);
    // the approval still holds: the gate is still waiting, the downstream still did not run — the
    // restart neither skipped the approval nor executed the gated side effect
    assertThat(stepState.get("send-report")).isEqualTo(WorkflowStepState.WAITING_FOR_APPROVAL);
    assertThat(stepState.get("send-email")).isEqualTo(WorkflowStepState.PENDING);
    assertThat(downstreamRuns).isEmpty();
    // no duplicate approval on the restart (at-least-once: one pause, one approval)
    verify(approvalRepository, times(1))
        .create(eq(tenant), eq(runId), eq("send-report"), anyString(), anyString(), any(), any());
    // the lease was released once and never re-held: the paused run held no worker thread (S11-03)
    verify(runRepository, times(1)).releaseLease(tenant, runId, LEASE_EPOCH);
  }

  @Test
  void a_paused_run_waiting_for_approval_is_not_stolen_back_to_running_by_the_poll() {
    // S11-05 acceptance: the pause released the lease, so the poll CAN claim the run again — but a
    // paused run is moved only by the approval decision. The claim must be released again and no
    // run.started transition may fire, otherwise the poll would both lie about the run state
    // (WAITING_FOR_APPROVAL -> RUNNING without a decision) and race the decision's own resume edge.
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    when(leaseService.claim(any(), any())).thenReturn(LEASE_EPOCH);
    when(runRepository.findClaimableRuns(tenant)).thenReturn(List.of(runId));
    when(runRepository.findRun(tenant, runId))
        .thenReturn(
            Optional.of(
                new RunView(
                    runId,
                    versionId,
                    WorkflowRunState.WAITING_FOR_APPROVAL,
                    1,
                    OffsetDateTime.now())));
    when(versionService.loadGraph(tenant, versionId))
        .thenReturn(
            new WorkflowGraph(
                List.of(node("send-report", "HUMAN_APPROVAL"), node("send-email", "NOOP")),
                List.of(edge("send-report", "send-email"))));
    List<String> downstreamRuns = new ArrayList<>();
    StepHandler noop =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "NOOP".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            downstreamRuns.add(context.stepKey());
            return StepResult.succeeded("email sent");
          }
        };

    WorkflowWorker worker = buildWorker(List.of(noop));
    int processed = worker.pollOnce(tenant);

    // no progress, no state machine moves, no gated side effect — the pause is left durable
    assertThat(processed).isZero();
    verify(transitionService, never()).transitionRun(any(), any(), any(), any(), any(), anyInt());
    verify(transitionService, never())
        .transitionStep(any(), any(), any(), any(), any(), any(), anyInt());
    verify(stepRepository, never()).createStep(any(), any(), anyString(), anyInt(), any());
    assertThat(downstreamRuns).isEmpty();
    // the accidental claim is released again, so the run stays claimable for the resume (S11-04)
    verify(runRepository, times(1)).releaseLease(tenant, runId, LEASE_EPOCH);
  }

  @Test
  void a_run_already_running_when_claimed_is_driven_without_a_second_run_started() {
    // S11-05 acceptance: a run already RUNNING when claimed — resumed by an approval decision, or
    // re-claimed after its owner's lease expired — is driven without the run.started transition
    // (the state machine forbids RUNNING -> RUNNING). The re-drive picks up the persisted step
    // states and concludes the run; a second run.started would be a lie about the run's history.
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("extract", WorkflowStepState.PENDING);

    when(leaseService.claim(any(), any())).thenReturn(LEASE_EPOCH);
    when(runRepository.findClaimableRuns(tenant)).thenReturn(List.of(runId));
    when(runRepository.findRun(tenant, runId))
        .thenReturn(
            Optional.of(
                new RunView(runId, versionId, WorkflowRunState.RUNNING, 1, OffsetDateTime.now())));
    when(versionService.loadGraph(tenant, versionId))
        .thenReturn(new WorkflowGraph(List.of(node("extract", "NOOP")), List.of()));
    when(stepRepository.findStepStatuses(tenant, runId))
        .thenAnswer(
            inv ->
                stepState.entrySet().stream()
                    .map(e -> new StepStatusRow(e.getKey(), e.getValue(), LEASE_EPOCH))
                    .collect(Collectors.toList()));
    when(stepRepository.findStepOutputs(any(), any())).thenReturn(Collections.emptyMap());
    doNothing().when(stepRepository).createStep(any(), any(), anyString(), anyInt(), any());
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
    StepHandler noop =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "NOOP".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            return StepResult.succeeded("extracted");
          }
        };

    WorkflowWorker worker = buildWorker(List.of(noop));
    int processed = worker.pollOnce(tenant);

    assertThat(processed).isOne();
    // the parked step was driven to its conclusion ...
    assertThat(stepState.get("extract")).isEqualTo(WorkflowStepState.SUCCEEDED);
    // ... WITHOUT a run.started: the run never paused, so the re-drive is invisible on the run's
    // transition history; the only run-level moves are the resume's own SUCCEEDED conclusion
    verify(transitionService, never())
        .transitionRun(any(), any(), eq(WorkflowRunState.RUNNING), anyString(), any(), anyInt());
    verify(transitionService)
        .transitionRun(
            eq(tenant),
            eq(runId),
            eq(WorkflowRunState.SUCCEEDED),
            eq("run.finished"),
            eq("agentflow-worker"),
            anyInt());
    // the step ran READY -> RUNNING -> SUCCEEDED under its own step.* events
    ArgumentCaptor<String> stepEvents = ArgumentCaptor.forClass(String.class);
    verify(transitionService, times(3))
        .transitionStep(
            eq(tenant), eq(runId), eq("extract"), any(), stepEvents.capture(), any(), anyInt());
    assertThat(stepEvents.getAllValues())
        .containsExactly("step.ready", "step.running", "step.succeeded");
  }

  // ---------------------------------------------------------------------------
  // S07-18: service identity — the stable service subject, never the instance id
  // ---------------------------------------------------------------------------

  @Test
  void the_worker_runs_under_the_stable_service_subject_even_when_an_instance_id_is_configured() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("extract", WorkflowStepState.PENDING);

    when(leaseService.claim(any(), any())).thenReturn(LEASE_EPOCH);
    when(runRepository.findClaimableRuns(tenant)).thenReturn(List.of(runId));
    when(runRepository.findRun(tenant, runId))
        .thenReturn(
            Optional.of(
                new RunView(runId, versionId, WorkflowRunState.RUNNING, 1, OffsetDateTime.now())));
    when(versionService.loadGraph(tenant, versionId))
        .thenReturn(new WorkflowGraph(List.of(node("extract", "NOOP")), List.of()));
    when(stepRepository.findStepStatuses(tenant, runId))
        .thenAnswer(
            inv ->
                stepState.entrySet().stream()
                    .map(e -> new StepStatusRow(e.getKey(), e.getValue(), LEASE_EPOCH))
                    .collect(Collectors.toList()));
    when(stepRepository.findStepOutputs(any(), any())).thenReturn(Collections.emptyMap());
    doNothing().when(stepRepository).createStep(any(), any(), anyString(), anyInt(), any());
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
    StepHandler noop =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "NOOP".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            return StepResult.succeeded("extracted");
          }
        };

    // A per-instance lease id configured on the worker: the run still runs under the stable
    // service principal subject (S07-18), never the per-instance identity.
    AgentFlowProperties.Worker workerProps = new AgentFlowProperties.Worker();
    workerProps.setInstanceId("a-per-instance-lease-id");
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    when(properties.getWorker()).thenReturn(workerProps);
    WorkflowWorker worker = buildWorker(List.of(noop), properties);

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isOne();
    // the only run-level move is the resume's own SUCCEEDED conclusion — and the actor is the
    // stable service subject, not the configured lease identity
    verify(transitionService)
        .transitionRun(
            eq(tenant),
            eq(runId),
            eq(WorkflowRunState.SUCCEEDED),
            eq("run.finished"),
            eq("agentflow-worker"),
            anyInt());
  }

  // ---------------------------------------------------------------------------
  // S13-11: step-duration timer and step-retry counter
  // ---------------------------------------------------------------------------

  @Test
  void a_successful_step_records_a_step_duration_sample() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a", "NOOP")), List.of());

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
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WorkflowWorker worker = buildWorker(List.of(new DeterministicStepHandler()), registry);

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    Timer duration = registry.find(WorkflowWorker.STEP_DURATION_METRIC).timer();
    assertThat(duration).isNotNull();
    assertThat(duration.count()).isEqualTo(1);
  }

  @Test
  void a_transient_fault_increments_the_step_retry_counter() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    String stepKey = "a";

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put(stepKey, WorkflowStepState.PENDING);
    WorkflowGraph graph = new WorkflowGraph(List.of(node(stepKey, "THROW")), List.of());

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
    when(transitionService.transitionStep(
            any(), any(), anyString(), any(), any(), any(), any(), anyInt()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
    StepHandler throwing =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "THROW".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            throw new RuntimeException("connection refused");
          }
        };

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WorkflowWorker worker = buildWorker(List.of(throwing), registry);

    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    assertThat(stepState.get(stepKey)).isEqualTo(WorkflowStepState.RETRY_WAIT);
    io.micrometer.core.instrument.Counter retries =
        registry.find(WorkflowWorker.STEP_RETRY_METRIC).counter();
    assertThat(retries).isNotNull();
    assertThat(retries.count()).isEqualTo(1.0);
  }

  private static WorkflowNode node(String id, String type) {
    return new WorkflowNode(id, type, new LinkedHashMap<>());
  }

  private static WorkflowEdge edge(String from, String to) {
    return new WorkflowEdge(from, to);
  }

  private WorkflowWorker buildWorker(List<StepHandler> handlers) {
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    when(properties.getWorker()).thenReturn(new AgentFlowProperties.Worker());
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
        properties);
  }

  private WorkflowWorker buildWorker(List<StepHandler> handlers, AgentFlowProperties properties) {
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
        properties);
  }

  private WorkflowWorker buildWorker(List<StepHandler> handlers, SimpleMeterRegistry registry) {
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    when(properties.getWorker()).thenReturn(new AgentFlowProperties.Worker());
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
        Clock.systemUTC(),
        registry);
  }
}
