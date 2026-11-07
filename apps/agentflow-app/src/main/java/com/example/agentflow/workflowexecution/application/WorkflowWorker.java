package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import com.example.agentflow.workflowexecution.domain.DagReadinessResolver;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable workflow worker (S07-08): a tenant-scoped event loop that claims runs, materializes the
 * pinned graph's steps, executes the ready ones through the {@link StepHandler} SPI, and reconciles
 * the run state. Every state change goes through the single transition service ({@link
 * RunStateMachineService}) with the tenant-scoped conditional update and the {@code lease_epoch}
 * fence (invariants 4 and 5); the worker holds no in-flight state of its own, so a restart leaves
 * each run at its last persisted state for recovery.
 *
 * <p>The blocking {@link StepHandler#handle} runs <em>outside</em> any transaction: the claim and
 * each step/run transition commit through their own {@code @Transactional} service method, so no
 * transaction holds the {@code workflow_runs} row lock across the handler. That is deliberate — the
 * run-level lease heartbeat ({@link RunHeartbeatService}, S07-04) extends {@code lease_until} from
 * a dedicated thread <em>during</em> a long-running step, and a fenced lease {@code UPDATE} cannot
 * run while this worker's poll transaction still locks the row (the gap-b fix). {@link #pollOnce}
 * is therefore not itself {@code @Transactional}.
 *
 * @see RunStateMachineService
 * @see RunHeartbeatService
 */
public class WorkflowWorker implements DisposableBean {

  /** The output-schema version carried on every step checkpoint this worker persists (S07-07). */
  public static final String STEP_OUTPUT_SCHEMA_VERSION = "1";

  private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

  private final WorkflowRunRepository runRepository;
  private final WorkflowStepRunRepository stepRepository;
  private final WorkflowVersionService versionService;
  private final RunLeaseService leaseService;
  private final RunHeartbeatService heartbeat;
  private final RunStateMachineService transitionService;
  private final CheckpointAdapter checkpointAdapter;
  private final List<StepHandler> handlers;
  private final String actor;

  private final AgentFlowProperties properties;

  /**
   * The DAG readiness resolver (S08-11): the pure gate that turns the pinned graph plus the
   * persisted step states into this pass's ready / skipped / cancelled step lists. Stateless, so a
   * single instance serves the worker's whole lifetime.
   */
  private final DagReadinessResolver readinessResolver = new DagReadinessResolver();

  /**
   * The step input mapping seam (S08-12): resolves the node's {@code input} wiring against the
   * completed predecessors' persisted outputs <em>before</em> the handler (and any tool) is
   * invoked, so an unknown path, a type violation or a null value fails the step as a classified
   * contract failure instead of reaching a call. Stateless, so a single instance serves the
   * worker's whole lifetime.
   */
  private final StepInputResolver stepInputResolver = new StepInputResolver();

  /**
   * The heartbeat scheduler: a single daemon thread that extends the run's lease while a step is
   * executing (S07-04, the gap-b fix). {@code null} when no {@link RunHeartbeatService} is wired —
   * a DB-less skeleton has no lease to extend — so the loop never starts a thread that writes
   * nothing.
   */
  private final ScheduledExecutorService heartbeatScheduler;

  /**
   * @param runRepository the tenant-scoped run repository
   * @param stepRepository the tenant-scoped step repository
   * @param versionService the pinned graph loader (the {@code workflowdefinition} public API)
   * @param leaseService the run claim service (S07-03)
   * @param heartbeat the run lease heartbeat (S07-04); {@code null} when no database is configured
   *     (a DB-less skeleton has no lease to extend), so the loop starts no heartbeat thread
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
      RunHeartbeatService heartbeat,
      RunStateMachineService transitionService,
      CheckpointAdapter checkpointAdapter,
      List<StepHandler> handlers,
      AgentFlowProperties properties) {
    this.runRepository = runRepository;
    this.stepRepository = stepRepository;
    this.versionService = versionService;
    this.leaseService = leaseService;
    this.heartbeat = heartbeat;
    this.transitionService = transitionService;
    this.checkpointAdapter = checkpointAdapter;
    this.handlers = handlers;
    this.actor = resolveActor(properties);
    this.properties = properties;
    // A heartbeat thread only helps when there is a lease to extend. Guard on the same
    // {@code null} the {@link #executeStep} heartbeat path guards, so a DB-less skeleton builds no
    // scheduler at all.
    this.heartbeatScheduler =
        heartbeat == null
            ? null
            : Executors.newSingleThreadScheduledExecutor(heartbeatThreadFactory());
  }

  /**
   * Graceful shutdown of the heartbeat scheduler (S07-13). The loop is driven by {@link
   * WorkerLifecycle}, which stops the loop thread and the in-flight step; this releases the
   * scheduler's daemon thread when the worker bean itself is destroyed. A heartbeat task already
   * stopped cancelling the run's lease once its step concluded (see {@link #stopHeartbeat}).
   */
  @Override
  public void destroy() {
    ScheduledExecutorService scheduler = heartbeatScheduler;
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /**
   * Shutdown flag (S07-13, graceful-shutdown lifecycle). When set the poll loop stops claiming new
   * runs and lets the in-flight run/step finish before it returns (bounded drain is the {@link
   * WorkerLifecycle}'s job). It is {@code volatile} so a {@link #stop()} issued from the lifecycle
   * thread is visible to the loop thread without a lock.
   */
  private volatile boolean shuttingDown;

  /**
   * @return {@code true} once {@link #stop()} has been called (the worker is shutting down)
   */
  public boolean isShuttingDown() {
    return shuttingDown;
  }

  /**
   * Begin a graceful shutdown (S07-13). Sets the shutdown flag so {@link #pollOnce} stops claiming
   * new runs; an already in-flight run is allowed to finish its current step sequence (bounded
   * drain), then the loop returns control to the caller. This is the worker's half of the shutdown
   * contract the {@link WorkerLifecycle} drives on {@code SIGTERM}.
   */
  public void stop() {
    shuttingDown = true;
  }

  /**
   * Poll the tenant's claimable runs once (S07-08): claim each, drive it through one lease, execute
   * its ready steps, and reconcile its state.
   *
   * @param tenantId the owning tenant (invariant 1 — the candidate list is tenant-scoped)
   * @return the number of runs the worker made progress on this poll (claimed and scheduled or
   *     finished at least one step, or moved to a terminal state)
   */
  public int pollOnce(UUID tenantId) {
    // Graceful shutdown (S07-13): once shutting down, stop claiming new runs. An already in-flight
    // run/step finishes on its own; the lifecycle bounds how long it is given. Returning zero here
    // (rather than processing) is what lets termination stop making progress without losing runs —
    // the lease expires and the stale-run recovery scheduler re-claims them.
    if (shuttingDown) {
      return 0;
    }
    List<UUID> claimable = runRepository.findClaimableRuns(tenantId);
    int processed = 0;
    for (UUID runId : claimable) {
      // Graceful shutdown (S07-13): stop starting new runs once shutting down; the run we are
      // already processing finishes its current step sequence, then the loop returns.
      if (shuttingDown) {
        break;
      }
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
    // The stable lease owner the claim stamped on {@code lease_owner} (memoized by
    // RunLeaseService),
    // echoed on the heartbeat fence below — a freshly generated id per call would never match the
    // fenced heartbeat and every extension would be rejected.
    UUID owner = leaseService.workerId();
    RunView run = runRepository.findRun(tenantId, runId).orElse(null);
    if (run == null) {
      return false;
    }
    WorkflowGraph graph = versionService.loadGraph(tenantId, run.workflowVersionId());
    if (graph == null) {
      return false;
    }
    // Cooperative cancellation (S07-12): a run cancelled while not owned by this worker is
    // re-claimed (CANCEL_REQUESTED is not terminal, so findClaimableRuns still returns it) and
    // drained — not-yet-started steps are cancelled, completed steps keep their result, and the
    // run closes to CANCELLED once every step is terminal (run-state-machine.md section 3).
    if (WorkflowRunState.CANCEL_REQUESTED.equals(run.status())) {
      drainForCancellation(tenantId, runId, graph, epoch);
      return true;
    }
    if (!transitionRun(tenantId, runId, WorkflowRunState.RUNNING, "run.started")) {
      return false;
    }
    materializeSteps(tenantId, runId, graph, epoch);
    executeUntilQuiesced(tenantId, runId, graph, owner, epoch);
    reconcileRun(tenantId, runId);
    return true;
  }

  /**
   * Cooperatively drain a run whose cancellation was requested (S07-12): cancel every
   * not-yet-started step ({@code PENDING}, {@code READY}, {@code RETRY_WAIT}) to {@code CANCELLED},
   * while the result and output of any already-completed step is preserved — a completed step is a
   * terminal state and is never overwritten (invariant 4). The run closes to {@code CANCELLED} once
   * every step is terminal (handled by {@link #reconcileRun}).
   *
   * <p>Cancellation is a cooperative, boundary-level stop, not a mid-flight interruption: a step
   * that is already executing its {@link StepHandler} is not torn down mid-call — it finishes or
   * fails and its result is kept; the drain then cancels the remaining not-yet-started steps. The
   * worker re-reads the run status each pass, so a cancel that arrives while the previous pass was
   * running is honoured before the next step starts.
   */
  private void drainForCancellation(
      UUID tenantId, UUID runId, WorkflowGraph graph, int leaseEpoch) {
    // Ensure every graph node has a step row before the drain inspects the step states.
    materializeSteps(tenantId, runId, graph, leaseEpoch);
    for (StepStatusRow row : stepRepository.findStepStatuses(tenantId, runId)) {
      WorkflowStepState state = row.status();
      // Cancel every not-yet-started step. RETRY_WAIT drains to CANCELLED (cancel precedence
      // over a pending retry); a RUNNING step is left to reconcile — it is not torn down
      // mid-flight (in-flight external calls are documented as incomplete, S07-12).
      if (state == WorkflowStepState.PENDING
          || state == WorkflowStepState.READY
          || state == WorkflowStepState.RETRY_WAIT) {
        transitionStep(
            tenantId, runId, row.stepKey(), WorkflowStepState.CANCELLED, "step.cancelled");
      }
    }
    reconcileRun(tenantId, runId);
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
   * Execute ready steps until no more are ready or a step fails permanently (S07-08); the per-pass
   * readiness decisions, the failure/skip/cancel propagation and the deterministic execution order
   * are the {@link DagReadinessResolver}'s contract (S08-11). Each executed step is checkpointed
   * through {@link CheckpointAdapter} so its output can be reused on recovery (S07-07).
   */
  private void executeUntilQuiesced(
      UUID tenantId, UUID runId, WorkflowGraph graph, UUID owner, int leaseEpoch) {
    Map<String, WorkflowNode> nodes = byId(graph);
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
      // The readiness gate is pure and reads only the persisted states: a PENDING step is ready
      // only when every predecessor has SUCCEEDED, is cascade-skipped by a FAILED / SKIPPED
      // predecessor, and is cancelled by a CANCELLED predecessor (invariant 7 — the graph is
      // data). A still-running, retrying or approval-paused predecessor leaves the step waiting
      // for the next pass.
      DagReadinessResolver.Resolution resolution = readinessResolver.resolve(graph, statusByKey);
      boolean progressed =
          applyResolution(tenantId, runId, resolution, nodes, outputs, owner, leaseEpoch);
      if (!progressed) {
        break;
      }
    }
  }

  /**
   * Apply one pass of the {@link DagReadinessResolver}'s resolution (see {@link
   * #executeUntilQuiesced}).
   *
   * @return {@code true} when at least one step was executed, skipped or cancelled this pass
   */
  private boolean applyResolution(
      UUID tenantId,
      UUID runId,
      DagReadinessResolver.Resolution resolution,
      Map<String, WorkflowNode> nodes,
      Map<String, String> outputs,
      UUID owner,
      int leaseEpoch) {
    boolean progressed = false;
    // Cancel propagation and the failure cascade are decided outcomes: record them through the
    // state machine before any new step starts, so this pass's executions never outrun a
    // downstream cancellation (invariant 4 — every change through the single transition service).
    for (String stepKey : resolution.cancelled()) {
      transitionStep(tenantId, runId, stepKey, WorkflowStepState.CANCELLED, "step.cancelled");
      progressed = true;
    }
    for (String stepKey : resolution.skipped()) {
      transitionStep(tenantId, runId, stepKey, WorkflowStepState.SKIPPED, "step.skipped");
      progressed = true;
    }
    // Ready steps run sequentially, in the resolver's deterministic topological order (S08-11) —
    // a parallel executor is a later, measured change.
    for (String stepKey : resolution.ready()) {
      executeStep(tenantId, runId, stepKey, nodes, outputs, owner, leaseEpoch);
      progressed = true;
    }
    return progressed;
  }

  /**
   * Schedule, execute and conclude one step through the state machine (PENDING → READY → RUNNING →
   * SUCCEEDED / RETRY_WAIT / FAILED), then checkpoint its output on success.
   *
   * <p>A handler that <em>returns</em> {@code FAILED} is a decided domain outcome: the step ends
   * {@code FAILED}. A handler that <em>throws</em> is a fault the {@link StepFailureClassifier}
   * (S07-11) classifies: a transient fault (infra / provider rate-limit / provider timeout) ends
   * the step {@code RETRY_WAIT} so it is retried with bounded backoff; every other fault ends it
   * {@code FAILED}. The fault's {@code ErrorClass} token and a short redacted reason are recorded
   * (the safe reason never carries a secret, full prompt or document — invariant 8). Previously the
   * exception propagated out of the {@code @Transactional} poll and rolled the whole run back; now
   * it is contained to the step and persisted.
   */
  private void executeStep(
      UUID tenantId,
      UUID runId,
      String stepKey,
      Map<String, WorkflowNode> nodes,
      Map<String, String> outputs,
      UUID owner,
      int leaseEpoch) {
    transitionStep(tenantId, runId, stepKey, WorkflowStepState.READY, "step.ready");
    transitionStep(tenantId, runId, stepKey, WorkflowStepState.RUNNING, "step.running");
    // Heartbeat (S07-04, the gap-b fix): while this step's handler runs — it may outlive the lease
    // lifetime — extend the run's lease so the stale-run recovery scan (S07-09) does not mistake an
    // actively-owned run for a crashed owner and re-claim it. The heartbeat runs on its own thread
    // (the poll thread is blocked in the blocking {@link StepHandler#handle}) and outside the poll
    // transaction (see the class Javadoc); the step's own fenced writes still fence through this
    // worker's {@code lease_epoch}. The task is cancelled the instant the step concludes.
    ScheduledFuture<?> leaseHeartbeat = startHeartbeat(tenantId, runId, owner, leaseEpoch);
    try {
      WorkflowNode node = nodes.get(stepKey);
      // S08-12: resolve the node's input wiring against the completed predecessors' persisted
      // outputs BEFORE the handler (and any tool) is invoked. An unresolvable wiring — an unknown
      // output path, a type violation, a null value — throws InputMappingException (an
      // IllegalArgumentException, classified `validation`: permanent, never retried), so a broken
      // wiring is rejected before any call, not mid-call.
      StepResult result =
          resolve(node.type()).handle(stepContext(tenantId, runId, stepKey, node, nodes, outputs));
      if (WorkflowStepState.SUCCEEDED.equals(result.outcome())) {
        transitionStep(tenantId, runId, stepKey, WorkflowStepState.SUCCEEDED, "step.succeeded");
        checkpointAdapter.saveCheckpoint(
            tenantId, runId, stepKey, leaseEpoch, STEP_OUTPUT_SCHEMA_VERSION, result.output());
      } else {
        // A non-throwing FAILED is a decided domain outcome: permanent, no fault to classify.
        transitionStep(tenantId, runId, stepKey, WorkflowStepState.FAILED, "step.failed");
      }
    } catch (Throwable failure) {
      // S07-11: classify the fault the handler threw. Retry transient infra / provider faults;
      // every other fault is terminal. The transition is contained to this step, so a fault never
      // rolls the run back — it is persisted for the next poll / recovery.
      StepFailure failureInfo = StepFailureClassifier.defaults().classify(failure);
      WorkflowStepState next =
          failureInfo.isRetryable() ? WorkflowStepState.RETRY_WAIT : WorkflowStepState.FAILED;
      transitionStepError(
          tenantId,
          runId,
          stepKey,
          next,
          failureInfo.errorClass().errorClass(),
          failureInfo.safeReason(),
          "step." + next.name().toLowerCase(Locale.ROOT));
    } finally {
      // The step has concluded: stop extending the lease. A run that released it (terminal,
      // approval
      // pause, cancelled) matches zero rows on the next heartbeat anyway; reconcile (pollOnce)
      // releases the lease when the run reaches a lease-releasing state.
      stopHeartbeat(leaseHeartbeat);
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
    // A run cancelled while not yet drained closes to CANCELLED once every step is terminal
    // (S07-12): the drain cancelled the not-yet-started steps, the completed ones were kept.
    boolean cancelling =
        runRepository
            .findRun(tenantId, runId)
            .map(RunView::status)
            .map(status -> WorkflowRunState.CANCEL_REQUESTED.equals(status))
            .orElse(false);
    if (cancelling) {
      transitionRun(tenantId, runId, WorkflowRunState.CANCELLED, "run.cancelled");
      return;
    }
    if (allSucceeded) {
      transitionRun(tenantId, runId, WorkflowRunState.SUCCEEDED, "run.finished");
    } else {
      transitionRun(tenantId, runId, WorkflowRunState.FAILED, "run.failed");
    }
  }

  /**
   * Build the step execution context for one executed node (tenant-scoped; config is data). The
   * node's {@code input} wiring is resolved against the completed predecessors' persisted outputs
   * here, before the handler sees the context (S08-12).
   *
   * @return the immutable {@link StepExecutionContext} the {@link StepHandler} receives
   */
  private StepExecutionContext stepContext(
      UUID tenantId,
      UUID runId,
      String stepKey,
      WorkflowNode node,
      Map<String, WorkflowNode> nodes,
      Map<String, String> upstreamOutputs) {
    return new StepExecutionContext(
        tenantId,
        runId,
        stepKey,
        node.type(),
        node.config(),
        stepInputResolver.resolve(node, nodes, upstreamOutputs),
        upstreamOutputs);
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
   * Transition a step. A lost-claim / missing step {@code 404} or a transition lost to another
   * writer {@code 409} propagates as a {@link ResponseStatusException} and rolls back this run's
   * progress in the outer transaction (invariant 4), so the run is retried on the next poll.
   */
  private void transitionStep(
      UUID tenantId, UUID runId, String stepKey, WorkflowStepState to, String eventType) {
    transitionService.transitionStep(tenantId, runId, stepKey, to, eventType, actor);
  }

  /**
   * Transition a step that failed, recording the fault's {@link
   * com.example.agentflow.common.ErrorClass} token and short safe reason (S07-11). A lost-claim /
   * missing step {@code 404} or a transition lost to another writer {@code 409} still propagates as
   * a {@link ResponseStatusException} and rolls this run's progress back (invariant 4), so the run
   * is retried on the next poll.
   */
  private void transitionStepError(
      UUID tenantId,
      UUID runId,
      String stepKey,
      WorkflowStepState to,
      String errorClass,
      String safeReason,
      String eventType) {
    transitionService.transitionStep(
        tenantId, runId, stepKey, to, eventType, errorClass, safeReason);
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

  /**
   * @return a daemon thread factory so the heartbeat thread never blocks JVM shutdown and carries a
   *     recognizable name in the thread dump.
   */
  private static ThreadFactory heartbeatThreadFactory() {
    return runnable -> {
      Thread thread = new Thread(runnable, "agentflow-worker-heartbeat");
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * Extend the run's lease once per {@code heartbeat-interval-seconds} until the returned task is
   * cancelled (see {@link #executeStep}). Runs on the dedicated single-thread scheduler — never on
   * the poll thread (whose blocking {@link StepHandler#handle} would starve it) and never inside
   * the poll transaction (whose row lock would block the fenced lease update).
   *
   * @return the cancellable task, or {@code null} when no {@link RunHeartbeatService} is wired (a
   *     DB-less skeleton has no lease to extend)
   */
  private ScheduledFuture<?> startHeartbeat(UUID tenantId, UUID runId, UUID owner, int epoch) {
    if (heartbeat == null || heartbeatScheduler == null) {
      return null;
    }
    long periodMs =
        TimeUnit.SECONDS.toMillis(properties.getWorker().getLease().getHeartbeatIntervalSeconds());
    if (periodMs <= 0) {
      // Defensive: the lease config is @Min(1) in production; clamp a misconfigured zero so the
      // scheduler never spins without yielding.
      periodMs = 1L;
    }
    return heartbeatScheduler.scheduleAtFixedRate(
        () -> {
          try {
            heartbeat.heartbeat(tenantId, runId, owner, epoch);
          } catch (RuntimeException failure) {
            // A rejected heartbeat (an old owner, a re-claimed run) or a DB hiccup is counted by
            // RunHeartbeatService and surfaced as a metric (S07-09); never let one tick kill the
            // scheduler's next beat.
            log.debug("heartbeat for run {} rejected: {}", runId, failure.getMessage());
          }
        },
        periodMs,
        periodMs,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Stop extending the lease once the step concluded. Cancelling the task leaves the lease to its
   * {@code lease_until}; a run that released its lease matches zero rows on the next heartbeat
   * anyway. A {@code null} task (no heartbeat wired) is a no-op.
   */
  private void stopHeartbeat(ScheduledFuture<?> heartbeat) {
    if (heartbeat != null) {
      heartbeat.cancel(false);
    }
  }
}
