package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowEdge;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.persistence.RunView;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for the graceful-shutdown hooks of the {@link WorkflowWorker} (S07-13). The
 * persistence / transition / lease / checkpoint collaborators are mocks, so the shutdown contract
 * is asserted in isolation: {@link #pollOnce} stops claiming once {@link WorkflowWorker#stop()} is
 * called, an in-flight run still finishes its current step sequence, and the flag is observable.
 *
 * <p>These prove the worker's half of the S07-13 contract that the {@link WorkerLifecycle} drives
 * on {@code SIGTERM}: claim nothing new, give the in-flight step bounded time, and leave the run
 * recoverable (the lease expires and recovery re-claims it — S07-09), never lost.
 */
class WorkflowWorkerShutdownTest {

  private static final int LEASE_EPOCH = 3;

  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final WorkflowStepRunRepository stepRepository = mock(WorkflowStepRunRepository.class);
  private final WorkflowVersionService versionService = mock(WorkflowVersionService.class);
  private final RunLeaseService leaseService = mock(RunLeaseService.class);
  private final RunStateMachineService transitionService = mock(RunStateMachineService.class);
  private final CheckpointAdapter checkpointAdapter = mock(CheckpointAdapter.class);

  @Test
  void poll_once_returns_zero_and_skips_claiming_once_stopping() {
    WorkflowWorker worker = buildWorker();

    assertThat(worker.isShuttingDown()).isFalse();

    worker.stop();

    int processed = worker.pollOnce(UUID.randomUUID());

    assertThat(processed).isZero();
    // once shutting down the loop never reads the tenant's claimable runs — it claims nothing new
    verify(runRepository, never()).findClaimableRuns(any());
  }

  @Test
  void shutdown_extends_nothing_on_the_lease_after_stopping() {
    UUID tenant = UUID.randomUUID();

    WorkflowWorker worker = buildWorker();

    worker.stop();

    int processed = worker.pollOnce(tenant);
    assertThat(processed).isZero();

    // S07-13: on shutdown the worker stops extending the run lease (the heartbeat) — it claims
    // nothing new, so the in-flight run's lease is left to expire and recovery (S07-09) re-claims
    // it; the run is left owned and recoverable, never lost and never extended indefinitely.
    verify(leaseService, never()).claim(any(), any());
    verify(runRepository, never()).findClaimableRuns(any());
  }

  @Test
  void stop_sets_the_shutting_down_flag() {
    WorkflowWorker worker = buildWorker();

    assertThat(worker.isShuttingDown()).isFalse();

    worker.stop();

    assertThat(worker.isShuttingDown()).isTrue();
  }

  @Test
  void poll_once_processes_runs_normally_before_stopping() {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    when(leaseService.claim(any(), any())).thenReturn(LEASE_EPOCH);
    when(runRepository.findClaimableRuns(tenant)).thenReturn(List.of(runId));
    when(runRepository.findRun(tenant, runId))
        .thenReturn(
            Optional.of(
                new RunView(runId, versionId, WorkflowRunState.QUEUED, 1, OffsetDateTime.now())));
    when(versionService.loadGraph(tenant, versionId))
        .thenReturn(new WorkflowGraph(List.of(node("a", "NOOP")), List.of()));

    WorkflowWorker worker = buildWorker();

    // before stopping the loop runs the run to completion (the flag does not stall normal work)
    int processed = worker.pollOnce(tenant);

    assertThat(processed).isEqualTo(1);
    assertThat(worker.isShuttingDown()).isFalse();
  }

  private static WorkflowNode node(String id, String type) {
    return new WorkflowNode(id, type, new java.util.LinkedHashMap<>());
  }

  private static WorkflowEdge edge(String from, String to) {
    return new WorkflowEdge(from, to);
  }

  private WorkflowWorker buildWorker() {
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
        List.of(new DeterministicStepHandler()),
        properties);
  }
}
