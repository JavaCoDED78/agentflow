package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for the run-level lease heartbeat the {@link WorkflowWorker} weaves into the step
 * loop (S07-04, the gap-b fix): while a step's handler runs — possibly longer than the lease
 * lifetime — the heartbeat extends the run's {@code lease_until} from a dedicated thread, so the
 * stale-run recovery scan (S07-09) never re-claims an actively-owned run.
 *
 * <p>The persistence / transition / lease / heartbeat collaborators are mocks. A {@code SLOW}
 * handler blocks on a latch to stand in for a long-running external call; the heartbeat cadence is
 * set to its minimum so several beats land while the handler is blocked, and the invocation (with
 * the exact {@code (tenant, run, owner, epoch)} fence) is asserted. Running {@code pollOnce} in a
 * separate thread keeps the blocking handler from stalling the test.
 */
class WorkflowWorkerHeartbeatTest {

  private static final int EPOCH = 7;
  private static final String SLOW = "SLOW";

  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final WorkflowStepRunRepository stepRepository = mock(WorkflowStepRunRepository.class);
  private final WorkflowVersionService versionService = mock(WorkflowVersionService.class);
  private final RunLeaseService leaseService = mock(RunLeaseService.class);
  private final RunStateMachineService transitionService = mock(RunStateMachineService.class);
  private final CheckpointAdapter checkpointAdapter = mock(CheckpointAdapter.class);
  private final RunHeartbeatService heartbeat = mock(RunHeartbeatService.class);

  @Test
  void heartbeats_the_run_while_a_long_step_blocks() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();

    // the claim stamps this memoized owner + epoch on the run; the heartbeat must echo them
    when(leaseService.workerId()).thenReturn(owner);
    when(leaseService.claim(any(), any())).thenReturn(EPOCH);
    when(runRepository.findClaimableRuns(tenant)).thenReturn(List.of(runId));
    when(runRepository.findRun(tenant, runId))
        .thenReturn(
            Optional.of(
                new RunView(runId, versionId, WorkflowRunState.QUEUED, 1, OffsetDateTime.now())));
    when(versionService.loadGraph(tenant, versionId))
        .thenReturn(new WorkflowGraph(List.of(node(SLOW)), List.of()));

    Map<String, WorkflowStepState> stepState = new LinkedHashMap<>();
    stepState.put(SLOW, WorkflowStepState.PENDING);
    when(stepRepository.findStepStatuses(tenant, runId))
        .thenAnswer(
            inv ->
                stepState.entrySet().stream()
                    .map(e -> new StepStatusRow(e.getKey(), e.getValue(), EPOCH))
                    .collect(Collectors.toList()));
    when(stepRepository.findStepOutputs(any(), any())).thenReturn(Map.of());
    when(transitionService.transitionStep(any(), any(), anyString(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              stepState.put(inv.getArgument(2), inv.getArgument(3));
              return inv.getArgument(3);
            });

    // a long-running step: signals it started, then blocks until the test releases it
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    StepHandler slow =
        new StepHandler() {
          @Override
          public boolean supports(String nodeType) {
            return SLOW.equals(nodeType);
          }

          @Override
          public StepResult handle(StepExecutionContext context) {
            try {
              entered.countDown(); // the heartbeat scheduler is already running at this point
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return StepResult.succeeded("done");
          }
        };

    WorkflowWorker worker = buildWorker(slow);

    // pollOnce blocks inside the handler, so drive it on its own thread
    Thread poller = new Thread(() -> worker.pollOnce(tenant), "test-poller");
    poller.start();

    // wait for the handler to be inside handle(); then give the heartbeat room to beat
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(200L);

    // while the step was still running, the lease was extended (the gap-b fix)
    verify(heartbeat, atLeast(1)).heartbeat(eq(tenant), eq(runId), eq(owner), eq(EPOCH));

    // let the step finish; the step concludes, the run reconciles, the run completes
    release.countDown();
    poller.join(5_000L);
    assertThat(poller.isAlive()).isFalse();
  }

  private static WorkflowNode node(String id) {
    return new WorkflowNode(id, SLOW, new LinkedHashMap<>());
  }

  private WorkflowWorker buildWorker(StepHandler handler) {
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    AgentFlowProperties.Worker workerProps = new AgentFlowProperties.Worker();
    // minimum cadence: @Min(1) in production, clamped to 1 ms so several beats land during the
    // handler's block in this fast test
    workerProps.getLease().setHeartbeatIntervalSeconds(0);
    when(properties.getWorker()).thenReturn(workerProps);
    return new WorkflowWorker(
        runRepository,
        stepRepository,
        versionService,
        leaseService,
        heartbeat,
        transitionService,
        checkpointAdapter,
        List.of(handler),
        properties);
  }
}
