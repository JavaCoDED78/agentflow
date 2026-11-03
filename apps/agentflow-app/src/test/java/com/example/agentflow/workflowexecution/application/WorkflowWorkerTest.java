package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.example.agentflow.workflowexecution.domain.StepExecutionContext;
import com.example.agentflow.workflowexecution.domain.StepHandler;
import com.example.agentflow.workflowexecution.domain.StepResult;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.RunView;
import com.example.agentflow.workflowexecution.persistence.StepStatusRow;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.time.OffsetDateTime;
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
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any()))
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
        .transitionRun(any(), any(), runState.capture(), anyString(), any());
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
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any()))
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
        .transitionRun(any(), any(), runState.capture(), anyString(), any());
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
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any()))
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
        .transitionRun(any(), any(), runState.capture(), anyString(), any());
    List<WorkflowRunState> runStates = runState.getAllValues();
    assertThat(runStates).contains(WorkflowRunState.RUNNING);
    // the final reconcile moves the run to SUCCEEDED once every step succeeded
    assertThat(runStates.get(runStates.size() - 1)).isEqualTo(WorkflowRunState.SUCCEEDED);
  }

  @Test
  void does_nothing_when_the_tenant_has_no_claimable_run() {
    when(leaseService.claim(any(), any())).thenReturn(LEASE_EPOCH);
    when(runRepository.findClaimableRuns(any())).thenReturn(Collections.emptyList());
    WorkflowWorker worker = buildWorker(List.of(new DeterministicStepHandler()));

    int processed = worker.pollOnce(UUID.randomUUID());

    assertThat(processed).isEqualTo(0);
    verify(transitionService, never()).transitionRun(any(), any(), any(), any(), any());
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
    verify(transitionService, never()).transitionRun(any(), any(), any(), any(), any());
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
        transitionService,
        checkpointAdapter,
        handlers,
        properties);
  }
}
