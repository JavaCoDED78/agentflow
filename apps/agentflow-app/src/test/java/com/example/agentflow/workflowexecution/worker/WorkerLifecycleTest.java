package com.example.agentflow.workflowexecution.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowEdge;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import com.example.agentflow.workflowexecution.application.CheckpointAdapter;
import com.example.agentflow.workflowexecution.application.DeterministicStepHandler;
import com.example.agentflow.workflowexecution.application.RunLeaseService;
import com.example.agentflow.workflowexecution.application.RunStateMachineService;
import com.example.agentflow.workflowexecution.application.WorkflowWorker;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for the {@link WorkerLifecycle} (S07-13). The worker is real (a {@link
 * WorkflowWorker} over mocked collaborators) so the loop, the shutdown flag and the bounded drain
 * are exercised together. A {@code NOOP} handler runs every step; a second handler blocks on the
 * step to prove the drain is bounded, not the step.
 *
 * <p>The acceptance criterion — termination never leaves a {@code RUNNING} run without recovery —
 * is satisfied here by construction: {@link #stop()} stops claiming (the worker's {@code stop()}
 * flag), waits bounded time for the in-flight step, and then leaves the loop thread running so its
 * run-level lease expires and the {@code S07-09} recovery scheduler re-claims the run.
 */
class WorkerLifecycleTest {

  private static final int LEASE_EPOCH = 3;
  private static final long DRAIN_TIMEOUT_MS = 1_000L;
  private static final long BLOCKING_STEP_MS = 10_000L;

  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final WorkflowStepRunRepository stepRepository = mock(WorkflowStepRunRepository.class);
  private final WorkflowVersionService versionService = mock(WorkflowVersionService.class);
  private final RunLeaseService leaseService = mock(RunLeaseService.class);
  private final RunStateMachineService transitionService = mock(RunStateMachineService.class);
  private final CheckpointAdapter checkpointAdapter = mock(CheckpointAdapter.class);

  @Test
  void startThenStop_setsShuttingDownAndDrainsTheLoop() throws Exception {
    AgentFlowProperties properties = workerProperties(50L, 30L);
    properties.getWorker().setTenants(List.of(UUID.randomUUID()));

    WorkflowWorker worker =
        new WorkflowWorker(
            runRepository,
            stepRepository,
            versionService,
            leaseService,
            null,
            transitionService,
            checkpointAdapter,
            List.of(new DeterministicStepHandler()),
            properties);
    WorkerLifecycle lifecycle = new WorkerLifecycle(worker, properties);

    // nothing is running before start()
    assertThat(lifecycle.isRunning()).isFalse();

    lifecycle.start();
    Thread.sleep(150L);
    assertThat(lifecycle.isRunning()).isTrue();

    lifecycle.stop();

    assertThat(lifecycle.isRunning()).isFalse();
    assertThat(worker.isShuttingDown()).isTrue();
  }

  @Test
  void stopWithoutStart_isAnNoOp() {
    AgentFlowProperties properties = workerProperties(50L, 30L);
    WorkflowWorker worker =
        new WorkflowWorker(
            runRepository,
            stepRepository,
            versionService,
            leaseService,
            null,
            transitionService,
            checkpointAdapter,
            List.of(new DeterministicStepHandler()),
            properties);
    WorkerLifecycle lifecycle = new WorkerLifecycle(worker, properties);

    lifecycle.stop(); // must not throw when never started

    assertThat(lifecycle.isRunning()).isFalse();
    assertThat(worker.isShuttingDown()).isFalse();
  }

  @Test
  void stopDrainsWithinTheBoundedTimeEvenWhenTheStepBlocks() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    // the step handler blocks past the drain ceiling, so the loop cannot finish on its own
    StepHandler blocking =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return "NOOP".equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            try {
              Thread.sleep(BLOCKING_STEP_MS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return StepResult.succeeded("done");
          }
        };

    stubClaimableRun(tenant, runId, versionId);

    AgentFlowProperties properties = workerProperties(50L, 1L);
    properties.getWorker().setTenants(List.of(tenant));

    WorkflowWorker worker =
        new WorkflowWorker(
            runRepository,
            stepRepository,
            versionService,
            leaseService,
            null,
            transitionService,
            checkpointAdapter,
            List.of(blocking),
            properties);
    WorkerLifecycle lifecycle = new WorkerLifecycle(worker, properties);

    lifecycle.start();
    Thread.sleep(200L); // let the loop reach the blocking step

    long startedAt = System.nanoTime();
    lifecycle.stop();
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

    // bounded: the drain waited roughly the 1 s ceiling but did not wait the 10 s step
    assertThat(elapsedMs).isGreaterThanOrEqualTo(900L);
    assertThat(elapsedMs).isLessThan(5_000L);
    // the shutdown flag is set (claim nothing new) even though the step is still running
    assertThat(lifecycle.isRunning()).isFalse();
    assertThat(worker.isShuttingDown()).isTrue();
  }

  /**
   * Stubs the full claim → load → execute path so {@code pollOnce} reaches the step handler, using
   * a shared in-memory {@code stepState} that the {@code transitionStep} stub writes back and
   * {@code findStepStatuses} reads — the same seam the {@link WorkflowWorkerTest} uses, so the
   * worker sees a step that actually moves {@code PENDING → RUNNING → SUCCEEDED} rather than a
   * fixed empty list.
   */
  private void stubClaimableRun(UUID tenant, UUID runId, UUID versionId) {
    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put("a", WorkflowStepState.PENDING);
    when(leaseService.claim(any(), any())).thenReturn(LEASE_EPOCH);
    when(runRepository.findClaimableRuns(tenant)).thenReturn(List.of(runId));
    when(runRepository.findRun(tenant, runId))
        .thenReturn(
            Optional.of(
                new RunView(runId, versionId, WorkflowRunState.QUEUED, 1, OffsetDateTime.now())));
    when(versionService.loadGraph(tenant, versionId))
        .thenReturn(new WorkflowGraph(List.of(node("a", "NOOP")), List.of()));
    when(stepRepository.findStepStatuses(tenant, runId))
        .thenAnswer(
            inv ->
                stepState.entrySet().stream()
                    .map(e -> new StepStatusRow(e.getKey(), e.getValue(), LEASE_EPOCH))
                    .collect(Collectors.toList()));
    when(stepRepository.findStepOutputs(any(), any())).thenReturn(Map.of());
    doNothing().when(stepRepository).createStep(any(), any(), anyString(), anyInt(), any());
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });
  }

  private static AgentFlowProperties workerProperties(long pollIntervalMs, long drainSeconds) {
    AgentFlowProperties properties = new AgentFlowProperties();
    properties.getWorker().setPollIntervalMs(pollIntervalMs);
    properties.getWorker().setShutdownTimeoutSeconds(drainSeconds);
    return properties;
  }

  private static WorkflowNode node(String id, String type) {
    return new WorkflowNode(id, type, new LinkedHashMap<>());
  }

  private static WorkflowEdge edge(String from, String to) {
    return new WorkflowEdge(from, to);
  }
}
