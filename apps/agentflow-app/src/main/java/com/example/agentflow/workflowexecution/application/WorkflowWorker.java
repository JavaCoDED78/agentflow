package com.example.agentflow.workflowexecution.application;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable workflow worker (S07-08): a tenant-scoped event loop that claims runs, materializes the
 * pinned graph's steps, executes the ready ones through the {@link StepHandler} SPI, and reconciles
 * the run state. Every state change goes through the single transition service ({@link
 * RunStateMachineService}) with the tenant-scoped conditional update and the {@code lease_epoch}
 * fence (invariants 4 and 5); the worker holds no in-flight state of its own, so a restart leaves
 * each run at its last persisted state for recovery.
 *
 * @see RunStateMachineService
 */
public class WorkflowWorker {

  /** The output-schema version carried on every step checkpoint this worker persists (S07-07). */
  public static final String STEP_OUTPUT_SCHEMA_VERSION = "1";

  private final WorkflowRunRepository runRepository;
  private final WorkflowStepRunRepository stepRepository;
  private final WorkflowVersionService versionService;
  private final RunLeaseService leaseService;
  private final RunStateMachineService transitionService;
  private final CheckpointAdapter checkpointAdapter;
  private final List<StepHandler> handlers;
  private final String actor;

  /**
   * @param runRepository the tenant-scoped run repository
   * @param stepRepository the tenant-scoped step repository
   * @param versionService the pinned graph loader (the {@code workflowdefinition} public API)
   * @param leaseService the run claim service (S07-03)
   * @param transitionService the single step / run transition service (invariant 4)
   * @param checkpointAdapter the redacting, size-bounding checkpoint seam (S07-07)
   * @param handlers the {@link StepHandler} beans, resolved by node type at runtime
   * @param properties the validated application configuration (used for the event {@code actor})
   */
  public WorkflowWorker(
      WorkflowRunRepository runRepository,
      WorkflowStepRunRepository stepRepository,
      WorkflowVersionService versionService,
      RunLeaseService leaseService,
      RunStateMachineService transitionService,
      CheckpointAdapter checkpointAdapter,
      List<StepHandler> handlers,
      AgentFlowProperties properties) {
    this.runRepository = runRepository;
    this.stepRepository = stepRepository;
    this.versionService = versionService;
    this.leaseService = leaseService;
    this.transitionService = transitionService;
    this.checkpointAdapter = checkpointAdapter;
    this.handlers = handlers;
    this.actor = resolveActor(properties);
  }

  /**
   * Poll the tenant's claimable runs once (S07-08): claim each, drive it through one lease, execute
   * its ready steps, and reconcile its state.
   *
   * @param tenantId the owning tenant (invariant 1 — the candidate list is tenant-scoped)
   * @return the number of runs the worker made progress on this poll (claimed and scheduled or
   *     finished at least one step, or moved to a terminal state)
   */
  @Transactional
  public int pollOnce(UUID tenantId) {
    List<UUID> claimable = runRepository.findClaimableRuns(tenantId);
    int processed = 0;
    for (UUID runId : claimable) {
      if (processRun(tenantId, runId)) {
        processed++;
      }
    }
    return processed;
  }

  /**
   * Drive one run through one lease: claim it, transition it to {@code RUNNING}, materialize the
   * pinned graph's steps, execute the ready ones through their {@link StepHandler}, and reconcile
   * the run state.
   *
   * @return {@code true} when the worker made progress on the run this poll; {@code false} when the
   *     run could not be made to progress this poll — it could not be claimed (terminal, owned by a
   *     live worker, a cross-tenant id, or the lease lost to another writer), or the run or its
   *     pinned graph is missing, or a run transition was denied ({@code 404}/{@code 409})
   */
  private boolean processRun(UUID tenantId, UUID runId) {
    int epoch = leaseService.claim(tenantId, runId);
    if (epoch == -1) {
      return false;
    }
    if (!transitionRun(tenantId, runId, WorkflowRunState.RUNNING, "run.started")) {
      return false;
    }
    RunView run = runRepository.findRun(tenantId, runId).orElse(null);
    if (run == null) {
      return false;
    }
    WorkflowGraph graph = versionService.loadGraph(tenantId, run.workflowVersionId());
    if (graph == null) {
      return false;
    }
    materializeSteps(tenantId, runId, graph, epoch);
    executeUntilQuiesced(tenantId, runId, graph, epoch);
    reconcileRun(tenantId, runId);
    return true;
  }

  /**
   * Materialize a PENDING step row for every graph node the run does not already hold (idempotent:
   * the step row is created at most once per run, keyed by {@code node.id} / {@code step_key}).
   */
  private void materializeSteps(UUID tenantId, UUID runId, WorkflowGraph graph, int leaseEpoch) {
    Set<String> existing =
        stepRepository.findStepStatuses(tenantId, runId).stream()
            .map(StepStatusRow::stepKey)
            .collect(Collectors.toSet());
    for (WorkflowNode node : graph.nodes()) {
      if (existing.add(node.id())) {
        stepRepository.createStep(runId, tenantId, node.id(), leaseEpoch, actor);
      }
    }
  }

  /**
   * Execute ready steps until no more are ready or a step fails permanently (S07-08). A {@code
   * PENDING} step is <em>ready</em> when all of its predecessors are {@code SUCCEEDED} (a step with
   * no predecessor is ready immediately), <em>skipped</em> when a predecessor {@code FAILED} or was
   * skipped (cascade), and otherwise left for the next pass. Each executed step is checkpointed
   * through {@link CheckpointAdapter} so its output can be reused on recovery (S07-07).
   */
  private void executeUntilQuiesced(
      UUID tenantId, UUID runId, WorkflowGraph graph, int leaseEpoch) {
    Map<String, WorkflowNode> nodes = byId(graph);
    List<String> order = graph.nodes().stream().map(WorkflowNode::id).collect(Collectors.toList());
    List<WorkflowEdge> edges = graph.edges();
    for (; ; ) {
      Map<String, WorkflowStepState> statusByKey =
          stepRepository.findStepStatuses(tenantId, runId).stream()
              .collect(
                  Collectors.toMap(
                      StepStatusRow::stepKey,
                      StepStatusRow::status,
                      (a, b) -> a,
                      LinkedHashMap::new));
      Map<String, String> outputs = stepRepository.findStepOutputs(tenantId, runId);
      boolean progressed =
          executeReadySteps(tenantId, runId, order, edges, statusByKey, outputs, nodes, leaseEpoch);
      if (!progressed) {
        break;
      }
    }
  }

  /**
   * Execute the ready steps of one pass (see {@link #executeUntilQuiesced}).
   *
   * @return {@code true} when at least one step was scheduled, skipped or completed this pass
   */
  private boolean executeReadySteps(
      UUID tenantId,
      UUID runId,
      List<String> order,
      List<WorkflowEdge> edges,
      Map<String, WorkflowStepState> statusByKey,
      Map<String, String> outputs,
      Map<String, WorkflowNode> nodes,
      int leaseEpoch) {
    boolean progressed = false;
    for (String stepKey : order) {
      if (!WorkflowStepState.PENDING.equals(statusByKey.get(stepKey))) {
        continue;
      }
      Set<String> predecessors = predecessors(stepKey, edges);
      if (hasFailedPredecessor(predecessors, statusByKey)) {
        // Cascade skip: a failed or skipped predecessor means this node can never run (invariant 7
        // — the DSL is data, so a failed predecessor is not "unknown" — it is a decided outcome).
        transitionStep(tenantId, runId, stepKey, WorkflowStepState.SKIPPED, "step.skipped");
        progressed = true;
        continue;
      }
      // A PENDING step is <em>ready</em> when every predecessor has SUCCEEDED (a step with no
      // predecessor is ready immediately — allMatch over the empty predecessor set is true). The
      // failed-predecessor cascade handled above already covers FAILED / SKIPPED predecessors; a
      // still-running, retrying, approval-paused or not-yet-successful predecessor leaves the step
      // not-ready for this pass, so it is left for the next one (invariant 7 — the graph is data,
      // the readiness decision is a pure gate over already-persisted step states).
      boolean ready =
          predecessors.stream()
              .allMatch(k -> WorkflowStepState.SUCCEEDED.equals(statusByKey.get(k)));
      if (ready) {
        executeStep(tenantId, runId, stepKey, nodes.get(stepKey), outputs, leaseEpoch);
        progressed = true;
      }
    }
    return progressed;
  }

  /**
   * Schedule, execute and conclude one step through the state machine (PENDING → READY → RUNNING →
   * SUCCEEDED / FAILED), then checkpoint its output on success.
   */
  private void executeStep(
      UUID tenantId,
      UUID runId,
      String stepKey,
      WorkflowNode node,
      Map<String, String> outputs,
      int leaseEpoch) {
    transitionStep(tenantId, runId, stepKey, WorkflowStepState.READY, "step.ready");
    transitionStep(tenantId, runId, stepKey, WorkflowStepState.RUNNING, "step.running");
    StepResult result =
        resolve(node.type()).handle(stepContext(tenantId, runId, stepKey, node, outputs));
    if (WorkflowStepState.SUCCEEDED.equals(result.outcome())) {
      transitionStep(tenantId, runId, stepKey, WorkflowStepState.SUCCEEDED, "step.succeeded");
      checkpointAdapter.saveCheckpoint(
          tenantId, runId, stepKey, leaseEpoch, STEP_OUTPUT_SCHEMA_VERSION, result.output());
    } else {
      transitionStep(tenantId, runId, stepKey, WorkflowStepState.FAILED, "step.failed");
    }
  }

  /** Reconcile the run state once its steps are quiescent (S07-08). */
  private void reconcileRun(UUID tenantId, UUID runId) {
    List<StepStatusRow> statuses = stepRepository.findStepStatuses(tenantId, runId);
    if (statuses.stream().anyMatch(s -> !s.status().isTerminal())) {
      // Work still pending (an approval pause, a retry backoff, or simply not yet ready): leave the
      // run RUNNING for the next poll rather than concluding it prematurely.
      return;
    }
    boolean allSucceeded =
        statuses.stream().allMatch(s -> WorkflowStepState.SUCCEEDED.equals(s.status()));
    if (allSucceeded) {
      transitionRun(tenantId, runId, WorkflowRunState.SUCCEEDED, "run.finished");
    } else {
      transitionRun(tenantId, runId, WorkflowRunState.FAILED, "run.failed");
    }
  }

  /**
   * Build the step execution context for one executed node (tenant-scoped; config is data).
   *
   * @return the immutable {@link StepExecutionContext} the {@link StepHandler} receives
   */
  private StepExecutionContext stepContext(
      UUID tenantId,
      UUID runId,
      String stepKey,
      WorkflowNode node,
      Map<String, String> upstreamOutputs) {
    return new StepExecutionContext(
        tenantId, runId, stepKey, node.type(), node.config(), upstreamOutputs);
  }

  /**
   * Resolve the {@link StepHandler} registered for a node type (a missing handler is a misconfig).
   */
  private StepHandler resolve(String nodeType) {
    return handlers.stream()
        .filter(handler -> handler.supports(nodeType))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no StepHandler registered for node type '" + nodeType + "'"));
  }

  /**
   * @return the graph nodes keyed by their {@code id} (the step key).
   */
  private Map<String, WorkflowNode> byId(WorkflowGraph graph) {
    Map<String, WorkflowNode> byId = new LinkedHashMap<>();
    for (WorkflowNode node : graph.nodes()) {
      byId.put(node.id(), node);
    }
    return byId;
  }

  /**
   * @return the predecessor node ids of a step, from the edges whose {@code to} is the step.
   */
  private Set<String> predecessors(String stepKey, List<WorkflowEdge> edges) {
    Set<String> predecessors = new LinkedHashSet<>();
    for (WorkflowEdge edge : edges) {
      if (edge.to().equals(stepKey)) {
        predecessors.add(edge.from());
      }
    }
    return predecessors;
  }

  /**
   * @return {@code true} when any predecessor has reached a non-success terminal state.
   */
  private boolean hasFailedPredecessor(
      Set<String> predecessors, Map<String, WorkflowStepState> statusByKey) {
    return predecessors.stream()
        .anyMatch(
            key ->
                WorkflowStepState.FAILED.equals(statusByKey.get(key))
                    || WorkflowStepState.SKIPPED.equals(statusByKey.get(key)));
  }

  /**
   * Transition a step. A lost-claim / missing step {@code 404} or a transition lost to another
   * writer {@code 409} propagates as a {@link ResponseStatusException} and rolls back this run's
   * progress in the outer transaction (invariant 4), so the run is retried on the next poll.
   */
  private void transitionStep(
      UUID tenantId, UUID runId, String stepKey, WorkflowStepState to, String eventType) {
    transitionService.transitionStep(tenantId, runId, stepKey, to, eventType, actor);
  }

  /**
   * Transition a run, returning {@code false} when the transition could not be applied — a cross
   * tenant / missing run ({@code 404}) or a lost lease fence ({@code 409}), in which case the run
   * is not ours this poll.
   */
  private boolean transitionRun(UUID tenantId, UUID runId, WorkflowRunState to, String eventType) {
    try {
      transitionService.transitionRun(tenantId, runId, to, eventType, actor);
      return true;
    } catch (ResponseStatusException e) {
      return false;
    }
  }

  /**
   * @return the run's event actor: the configured worker instance id, or a stable default.
   */
  private String resolveActor(AgentFlowProperties properties) {
    String instanceId = properties.getWorker().getInstanceId();
    return (instanceId != null && !instanceId.isBlank()) ? instanceId : "agentflow-worker";
  }
}
