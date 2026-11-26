package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import com.example.agentflow.workflowexecution.domain.ApprovalPayloadFingerprint;
import com.example.agentflow.workflowexecution.domain.DagReadinessResolver;
import com.example.agentflow.workflowexecution.domain.RunExecutionLimitExceeded;
import com.example.agentflow.workflowexecution.domain.RunExecutionLimits;
import com.example.agentflow.workflowexecution.domain.RunExecutionMonitor;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.beans.factory.annotation.Autowired;
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

  /**
   * The {@code HUMAN_APPROVAL} node type (a side-effect boundary, {@code workflowdefinition}). At
   * this boundary a step pauses the run for a human decision instead of running a handler (S11-03).
   */
  private static final String HUMAN_APPROVAL = "HUMAN_APPROVAL";

  /**
   * The step-duration timer (S13-11): one sample per handler invocation, in seconds. No labels —
   * high-cardinality ids never live in metric labels (invariant 9).
   */
  static final String STEP_DURATION_METRIC = "agentflow.workflowexecution.step.duration.seconds";

  /**
   * The step-retry counter (S13-11): one increment per step actually moved to RETRY_WAIT. No
   * labels.
   */
  static final String STEP_RETRY_METRIC = "agentflow.workflowexecution.step.retry.total";

  private static final Logger log = LoggerFactory.getLogger(WorkflowWorker.class);

  private final WorkflowRunRepository runRepository;
  private final WorkflowStepRunRepository stepRepository;
  private final WorkflowVersionService versionService;
  private final RunLeaseService leaseService;
  private final RunHeartbeatService heartbeat;
  private final RunStateMachineService transitionService;
  private final CheckpointAdapter checkpointAdapter;

  /** The approvals owner: the durable pause at a {@code HUMAN_APPROVAL} boundary (S11-03). */
  private final ApprovalRepository approvalRepository;

  private final List<StepHandler> handlers;

  /**
   * The stable service-principal actor the worker runs under (S07-18, {@link ServiceIdentity}):
   * every transition, row and audit record it writes goes out under this subject, so the run
   * history is uniformly attributable to the platform service rather than to a per-instance id.
   */
  private final String actor;

  private final AgentFlowProperties properties;

  /**
   * The injectable clock (invariant 10): the only source of "now" for the per-run wall-clock bound
   * (S11-13). The poll loop is driven by {@link WorkerLifecycle}, so tests inject a clock whose
   * instant advances to exercise the wall-clock breach deterministically.
   */
  private final Clock clock;

  /** The meter registry the S13-11 step meters register on (throwaway when unwired). */
  private final MeterRegistry meterRegistry;

  /** The S13-11 step-duration timer; no labels. */
  private final Timer stepDuration;

  /** The S13-11 step-retry counter; no labels. */
  private final Counter stepRetries;

  /**
   * The per-run hard execution limits (S11-13), read once from the configuration (or the defaults
   * when no database-less skeleton leaves {@code AgentFlowProperties#getExecution()} unset): the
   * bounds the per-run {@link RunExecutionMonitor} checks the loop against.
   */
  private final RunExecutionLimits executionLimits;

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
   * @param approvalRepository the approvals owner (the durable pause at a {@code HUMAN_APPROVAL}
   *     boundary — S11-03)
   * @param handlers the {@link StepHandler} beans, resolved by node type at runtime
   * @param properties the validated application configuration (the service-client decision is read
   *     from it; the stable service subject is derived from {@link ServiceIdentity}, not from the
   *     instance id)
   */
  public WorkflowWorker(
      WorkflowRunRepository runRepository,
      WorkflowStepRunRepository stepRepository,
      WorkflowVersionService versionService,
      RunLeaseService leaseService,
      RunHeartbeatService heartbeat,
      RunStateMachineService transitionService,
      CheckpointAdapter checkpointAdapter,
      ApprovalRepository approvalRepository,
      List<StepHandler> handlers,
      AgentFlowProperties properties) {
    this(
        runRepository,
        stepRepository,
        versionService,
        leaseService,
        heartbeat,
        transitionService,
        checkpointAdapter,
        approvalRepository,
        handlers,
        properties,
        Clock.systemUTC());
  }

  /**
   * Full constructor: also injects the {@link Clock} the per-run wall-clock bound is measured
   * against (S11-13, invariant 10). The lifecycle drives the loop, so production wiring passes the
   * application clock and only tests inject a controllable one.
   *
   * @param clock the injectable clock (invariant 10)
   */
  public WorkflowWorker(
      WorkflowRunRepository runRepository,
      WorkflowStepRunRepository stepRepository,
      WorkflowVersionService versionService,
      RunLeaseService leaseService,
      RunHeartbeatService heartbeat,
      RunStateMachineService transitionService,
      CheckpointAdapter checkpointAdapter,
      ApprovalRepository approvalRepository,
      List<StepHandler> handlers,
      AgentFlowProperties properties,
      Clock clock) {
    this(
        runRepository,
        stepRepository,
        versionService,
        leaseService,
        heartbeat,
        transitionService,
        checkpointAdapter,
        approvalRepository,
        handlers,
        properties,
        clock,
        null);
  }

  /**
   * Full constructor: also injects the {@link MeterRegistry} the S13-11 step meters register on. A
   * {@code null} registry means "no shared registry" and the meters live on a throwaway one (no
   * observability surface).
   *
   * @param meterRegistry the registry the step meters register on; may be {@code null}
   */
  @Autowired
  public WorkflowWorker(
      WorkflowRunRepository runRepository,
      WorkflowStepRunRepository stepRepository,
      WorkflowVersionService versionService,
      RunLeaseService leaseService,
      RunHeartbeatService heartbeat,
      RunStateMachineService transitionService,
      CheckpointAdapter checkpointAdapter,
      ApprovalRepository approvalRepository,
      List<StepHandler> handlers,
      AgentFlowProperties properties,
      @Autowired(required = false) Clock clock,
      MeterRegistry meterRegistry) {
    this.runRepository = runRepository;
    this.stepRepository = stepRepository;
    this.versionService = versionService;
    this.leaseService = leaseService;
    this.heartbeat = heartbeat;
    this.transitionService = transitionService;
    this.checkpointAdapter = checkpointAdapter;
    this.approvalRepository = approvalRepository;
    this.handlers = handlers;
    // S07-18: the worker runs under a single, stable service-principal actor (the
    // {@code agentflow-worker} subject), never the per-instance lease identity — the instanceId
    // stays on the lease ({@code lease_owner}) and never doubles as an actor. The M2M client
    // decision is validated up front: when the operator asks the worker to call protected APIs
    // or external services, a missing or blank secret refuses construction (a clear startup
    // failure); when it is not needed, the worker issues no token and holds none.
    boolean serviceClientEnabled = properties.getWorker().isServiceClientEnabled();
    ServiceIdentity identity =
        ServiceIdentity.worker(
            serviceClientEnabled,
            serviceClientEnabled ? System.getenv(ServiceIdentity.M2M_CLIENT_SECRET_ENV) : null);
    this.actor = identity.subject();
    this.properties = properties;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    // A DB-less skeleton may leave the execution config unset; the defaults keep the loop stopping
    // the run on a limit, never silently ignoring one (S11-13).
    AgentFlowProperties.Execution execution = properties.getExecution();
    this.executionLimits = execution == null ? RunExecutionLimits.defaults() : toLimits(execution);
    // A heartbeat thread only helps when there is a lease to extend. Guard on the same
    // {@code null} the {@link #executeStep} heartbeat path guards, so a DB-less skeleton builds no
    // scheduler at all.
    this.heartbeatScheduler =
        heartbeat == null
            ? null
            : Executors.newSingleThreadScheduledExecutor(heartbeatThreadFactory());
    this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
    this.stepDuration =
        Timer.builder(STEP_DURATION_METRIC)
            .description("Step handler duration in seconds (S13-11); no labels")
            .register(this.meterRegistry);
    this.stepRetries =
        Counter.builder(STEP_RETRY_METRIC)
            .description("Steps moved to RETRY_WAIT (S13-11); no labels")
            .register(this.meterRegistry);
  }

  private static RunExecutionLimits toLimits(AgentFlowProperties.Execution execution) {
    return new RunExecutionLimits(
        execution.getMaxSteps(),
        execution.getMaxToolCalls(),
        execution.getMaxRetries(),
        execution.getMaxWallClockSeconds(),
        execution.getMaxOutputBytes(),
        execution.getMaxParallelCalls());
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
   * <p>Two states are special-cased around the {@code run.started} transition (S11-05): an
   * approval-paused run ({@code WAITING_FOR_APPROVAL}, its lease released at the pause) answers a
   * claim by releasing it again — only the approval decision moves a paused run, so a poll neither
   * steals it back to {@code RUNNING} nor races the decision's own resume edge; and a run already
   * {@code RUNNING} when claimed (resumed by an approval decision, or re-claimed after its owner's
   * lease expired) is driven without the transition, which the state machine forbids from {@code
   * RUNNING}.
   *
   * @return {@code true} when the worker made progress on the run this poll; {@code false} when the
   *     run could not be made to progress this poll — it could not be claimed (terminal, owned by a
   *     live worker, a cross-tenant id, or the lease lost to another writer), it is approval-paused
   *     (released back to the decision), or the run or its pinned graph is missing, or a run
   *     transition was denied ({@code 404}/{@code 409})
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
    // S11-05 — an approval-paused run is moved only by the approval decision. The pause released
    // the lease, so this claim is possible even though no worker may drive the run: a poll must
    // not re-drive it (that would both lie about the run state — WAITING_FOR_APPROVAL -> RUNNING
    // without a decision — and race the decision's own WAITING_FOR_APPROVAL -> RUNNING edge).
    // Release the accidental claim and leave the pause durable for the decision.
    if (WorkflowRunState.WAITING_FOR_APPROVAL.equals(run.status())) {
      releaseRunLease(tenantId, runId, epoch);
      return false;
    }
    // A run already RUNNING when claimed — resumed by an approval decision (run.approved), or
    // re-claimed after its owner's lease expired — is driven without the run.started transition:
    // the state machine forbids RUNNING -> RUNNING, and the run is genuinely owned and executable.
    if (!WorkflowRunState.RUNNING.equals(run.status())
        && !transitionRun(tenantId, runId, WorkflowRunState.RUNNING, "run.started", epoch)) {
      return false;
    }
    materializeSteps(tenantId, runId, graph, epoch);
    // S11-13: a fresh per-run execution-limits monitor for this drive. The step handlers report
    // tool calls and concurrent-call slots into it (through the step context) so the loop can stop
    // the run the moment a per-run hard limit is reached.
    RunExecutionMonitor monitor = new RunExecutionMonitor(executionLimits, clock);
    try {
      executeUntilQuiesced(tenantId, runId, graph, owner, epoch, monitor);
      reconcileRun(tenantId, runId, epoch);
    } catch (RunExecutionLimitExceeded limitEx) {
      // The worker already failed the current step and closed the run to a documented, controlled
      // {@code FAILED} in {@link #failRunDueToLimit}: count the run as progressed this poll and do
      // not let the breach escape the poll loop to the next run.
      log.info(
          "run {} stopped at the {} execution limit: {} > {}",
          runId,
          limitEx.limit(),
          limitEx.used(),
          limitEx.limitValue());
    }
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
            tenantId,
            runId,
            row.stepKey(),
            WorkflowStepState.CANCELLED,
            "step.cancelled",
            leaseEpoch);
      }
    }
    reconcileRun(tenantId, runId, leaseEpoch);
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
      UUID tenantId,
      UUID runId,
      WorkflowGraph graph,
      UUID owner,
      int leaseEpoch,
      RunExecutionMonitor monitor) {
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
          applyResolution(tenantId, runId, resolution, nodes, outputs, owner, leaseEpoch, monitor);
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
      int leaseEpoch,
      RunExecutionMonitor monitor) {
    boolean progressed = false;
    // Cancel propagation and the failure cascade are decided outcomes: record them through the
    // state machine before any new step starts, so this pass's executions never outrun a
    // downstream cancellation (invariant 4 — every change through the single transition service).
    for (String stepKey : resolution.cancelled()) {
      transitionStep(
          tenantId, runId, stepKey, WorkflowStepState.CANCELLED, "step.cancelled", leaseEpoch);
      progressed = true;
    }
    for (String stepKey : resolution.skipped()) {
      transitionStep(
          tenantId, runId, stepKey, WorkflowStepState.SKIPPED, "step.skipped", leaseEpoch);
      progressed = true;
    }
    // Ready steps run sequentially, in the resolver's deterministic topological order (S08-11) —
    // a parallel executor is a later, measured change.
    for (String stepKey : resolution.ready()) {
      executeStep(tenantId, runId, stepKey, nodes, outputs, owner, leaseEpoch, monitor);
      progressed = true;
    }
    return progressed;
  }

  /**
   * Schedule, execute and conclude one step through the state machine (PENDING → READY → RUNNING →
   * SUCCEEDED / RETRY_WAIT / FAILED), then checkpoint its output on success.
   *
   * <p>A {@code HUMAN_APPROVAL} node stops the step and the run at the side-effect boundary instead
   * of running a handler: it records the durable approval, parks the step and the run in {@code
   * WAITING_FOR_APPROVAL}, and releases the run's lease (S11-03).
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
      int leaseEpoch,
      RunExecutionMonitor monitor) {
    transitionStep(tenantId, runId, stepKey, WorkflowStepState.READY, "step.ready", leaseEpoch);
    transitionStep(tenantId, runId, stepKey, WorkflowStepState.RUNNING, "step.running", leaseEpoch);
    WorkflowNode node = nodes.get(stepKey);
    // S11-03 — the HUMAN_APPROVAL boundary: a HUMAN_APPROVAL node stops the step and the run at a
    // side-effect boundary. It records the durable approval (the checkpoint of where execution
    // paused), parks the step and the run in WAITING_FOR_APPROVAL, and releases the run's lease
    // without starting a heartbeat or running a handler — so the blocked side effect never executes
    // and no worker thread is held while the run waits (the acceptance: a restart neither skips the
    // approval nor leaves a thread occupied). The step never reaches a StepHandler; the resume is a
    // separate decision (S11-04). A boundary step does not consume the execution budgets below.
    if (HUMAN_APPROVAL.equals(node.type())) {
      pauseForApproval(tenantId, runId, stepKey, node, leaseEpoch);
      return;
    }
    // S11-13 — per-run hard limits checked before the handler runs. A run already at its step,
    // wall-clock or tool-call budget stops here, before any call, into a documented, controlled
    // FAILED (invariant: the stop is a controlled state, not an unhandled fault).
    // S11-13 — a per-run hard limit reached before the handler still closes the run: the breach is
    // contained here so the current step and the run are failed into a documented, controlled
    // {@code FAILED} (the step was just marked RUNNING), matching the in-handler breach path.
    try {
      enforcePreStepLimits(monitor);
    } catch (RunExecutionLimitExceeded limitEx) {
      failRunDueToLimit(tenantId, runId, stepKey, limitEx, leaseEpoch);
      throw limitEx;
    }
    // Heartbeat (S07-04, the gap-b fix): while this step's handler runs — it may outlive the lease
    // lifetime — extend the run's lease so the stale-run recovery scan (S07-09) does not mistake an
    // actively-owned run for a crashed owner and re-claim it. The heartbeat runs on its own thread
    // (the poll thread is blocked in the blocking {@link StepHandler#handle}) and outside the poll
    // transaction (see the class Javadoc); the step's own fenced writes still fence through this
    // worker's {@code lease_epoch}. The task is cancelled the instant the step concludes.
    ScheduledFuture<?> leaseHeartbeat = startHeartbeat(tenantId, runId, owner, leaseEpoch);
    Instant stepStartedAt = clock.instant();
    try {
      // S08-12: resolve the node's input wiring against the completed predecessors' persisted
      // outputs BEFORE the handler (and any tool) is invoked. An unresolvable wiring — an unknown
      // output path, a type violation, a null value — throws InputMappingException (an
      // IllegalArgumentException, classified `validation`: permanent, never retried), so a broken
      // wiring is rejected before any call, not mid-call.
      StepResult result =
          resolve(node.type())
              .handle(stepContext(tenantId, runId, stepKey, node, nodes, outputs, monitor));
      // S11-13: a step handler that overran the parallel bound reports it on the monitor even if it
      // did not throw; the worker closes the run.
      if (monitor.isParallelBudgetExceeded()) {
        throw new RunExecutionLimitExceeded(
            RunExecutionLimits.Limit.PARALLEL_CALLS,
            executionLimits.maxParallelCalls(),
            monitor.parallelCallsAttempted());
      }
      if (WorkflowStepState.SUCCEEDED.equals(result.outcome())) {
        // S11-13: a step whose output exceeds the step-output size bound rejects the step and
        // closes
        // the run into a documented, controlled FAILED (independent of the storage-only checkpoint
        // inline bound).
        enforceOutputSize(monitor, result.output());
        transitionStep(
            tenantId, runId, stepKey, WorkflowStepState.SUCCEEDED, "step.succeeded", leaseEpoch);
        checkpointAdapter.saveCheckpoint(
            tenantId, runId, stepKey, leaseEpoch, STEP_OUTPUT_SCHEMA_VERSION, result.output());
      } else {
        // A non-throwing FAILED is a decided domain outcome: permanent, no fault to classify.
        transitionStep(
            tenantId, runId, stepKey, WorkflowStepState.FAILED, "step.failed", leaseEpoch);
      }
    } catch (RunExecutionLimitExceeded limitEx) {
      // A per-run limit was exceeded: close the run and the current step to a documented,
      // controlled
      // FAILED (S11-13), then unwind so the poll loop starts no further step for this run this
      // poll.
      failRunDueToLimit(tenantId, runId, stepKey, limitEx, leaseEpoch);
      throw limitEx;
    } catch (Throwable failure) {
      // S07-11: classify the fault the handler threw. Retry transient infra / provider faults;
      // every other fault is terminal. The transition is contained to this step, so a fault never
      // rolls the run back — it is persisted for the next poll / recovery.
      StepFailure failureInfo = StepFailureClassifier.defaults().classify(failure);
      WorkflowStepState next =
          failureInfo.isRetryable() ? WorkflowStepState.RETRY_WAIT : WorkflowStepState.FAILED;
      if (next == WorkflowStepState.RETRY_WAIT && !monitor.recordRetry()) {
        // S11-13: the run's retry budget is exhausted — close the run into a documented, controlled
        // FAILED instead of retrying a step that would keep failing (a per-run budget, not a
        // per-step
        // one). The step ends FAILED with the breach recorded.
        RunExecutionLimitExceeded limitEx =
            new RunExecutionLimitExceeded(
                RunExecutionLimits.Limit.RETRIES,
                executionLimits.maxRetries(),
                monitor.retriesUsed());
        failRunDueToLimit(tenantId, runId, stepKey, limitEx, leaseEpoch);
        throw limitEx;
      }
      if (next == WorkflowStepState.RETRY_WAIT) {
        // S13-11: one increment per step actually moved to RETRY_WAIT.
        stepRetries.increment();
      }
      transitionStepError(
          tenantId,
          runId,
          stepKey,
          next,
          failureInfo.errorClass().errorClass(),
          failureInfo.safeReason(),
          "step." + next.name().toLowerCase(Locale.ROOT),
          leaseEpoch);
    } finally {
      // S13-11: record the step-handler duration (one sample per handler invocation).
      stepDuration.record(Duration.between(stepStartedAt, clock.instant()));
      // The step has concluded: stop extending the lease. A run that released it (terminal,
      // approval
      // pause, cancelled) matches zero rows on the next heartbeat anyway; reconcile (pollOnce)
      // releases the lease when the run reaches a lease-releasing state.
      stopHeartbeat(leaseHeartbeat);
    }
  }

  /**
   * Enforce the per-run budgets checked <em>before</em> a step's handler runs (S11-13): the step
   * budget, the wall-clock duration and the tool-call budget. The first breached bound throws a
   * {@link RunExecutionLimitExceeded} the worker maps to a controlled {@code FAILED} run. A bound
   * of {@code 0} permits nothing of that kind (the first step / the first tool call stops the run).
   */
  private void enforcePreStepLimits(RunExecutionMonitor monitor) {
    if (!monitor.beginStep()) {
      throw new RunExecutionLimitExceeded(
          RunExecutionLimits.Limit.STEPS, executionLimits.maxSteps(), monitor.stepsUsed());
    }
    Instant now = clock.instant();
    if (!monitor.withinWallClock(now)) {
      throw new RunExecutionLimitExceeded(
          RunExecutionLimits.Limit.WALL_CLOCK,
          executionLimits.maxWallClockSeconds(),
          monitor.elapsedSeconds(now));
    }
    if (monitor.toolCallsUsed() > executionLimits.maxToolCalls()) {
      throw new RunExecutionLimitExceeded(
          RunExecutionLimits.Limit.TOOL_CALLS,
          executionLimits.maxToolCalls(),
          monitor.toolCallsUsed());
    }
  }

  /**
   * Enforce the per-run step-output size bound (S11-13): a step whose output is larger than {@code
   * maxOutputBytes} rejects the step. Called only for a {@code SUCCEEDED} result, so the step
   * output (the persisted document) is never larger than the bound. {@code null} output counts as
   * empty.
   */
  private void enforceOutputSize(RunExecutionMonitor monitor, String output) {
    long size =
        output == null ? 0L : output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (size > executionLimits.maxOutputBytes()) {
      throw new RunExecutionLimitExceeded(
          RunExecutionLimits.Limit.OUTPUT_SIZE, executionLimits.maxOutputBytes(), size);
    }
  }

  /**
   * Close a run and the current step into the documented, controlled {@code FAILED} state that a
   * per-run hard execution limit breach requires (S11-13). The current step goes {@code RUNNING ->
   * FAILED} and the run goes {@code RUNNING -> FAILED}, each recording the breach's {@code
   * limit_exceeded} {@link com.example.agentflow.common.ErrorClass} and a short, safe (redacted)
   * reason that carries no secret, prompt, document or high-cardinality id (invariant 8 / rule 9).
   * A lost-lease {@code 409} on the run transition is contained: the step already committed FAILED,
   * so the next poll reconciles the run to {@code FAILED}.
   */
  private void failRunDueToLimit(
      UUID tenantId,
      UUID runId,
      String stepKey,
      RunExecutionLimitExceeded limitEx,
      int leaseEpoch) {
    transitionStepError(
        tenantId,
        runId,
        stepKey,
        WorkflowStepState.FAILED,
        limitEx.errorClass(),
        limitEx.safeReason(),
        "step.failed",
        leaseEpoch);
    transitionRunError(
        tenantId, runId, WorkflowRunState.FAILED, "run.limit-exceeded", limitEx, leaseEpoch);
  }

  /**
   * Pause a step at a {@code HUMAN_APPROVAL} boundary (S11-03): record the durable approval, park
   * the step and the run in {@code WAITING_FOR_APPROVAL}, and release the run's lease so a paused
   * run is not a live owner while it waits (durable-execution §3).
   *
   * <p>The pause is the checkpoint of where execution stopped. The run's {@code
   * WAITING_FOR_APPROVAL} transition is applied first (the run-level pause is the higher-level
   * fact): if the run's lease moved, the run is not ours and the pause changes nothing — the next
   * poll recovers. The step's transition then parks the step, and the transition service concludes
   * its in-flight attempt, so no attempt lifecycle marker is left dangling (invariant 4). Finally
   * the run's lease is released (the step transition fences through this worker's {@code
   * lease_epoch}, so it cannot race the release): the run is claimable again and is resumed by the
   * approval decision (S11-04), not by a re-claimed run re-running its gated step. No heartbeat
   * starts and no handler runs, so the blocked side effect never executes and no worker thread is
   * held — the acceptance of S11-03: a restart neither skips the approval nor leaves a thread
   * occupied.
   *
   * @param tenantId the run's tenant (its tenant isolation flows through the run)
   * @param runId the run to pause
   * @param stepKey the graph node / step the request is gating (matches {@code
   *     workflow_step_runs.step_key})
   * @param node the {@code HUMAN_APPROVAL} node (its {@code action} + payload are the request)
   * @param leaseEpoch the run lease_epoch this worker claims to hold (invariant 5)
   */
  private void pauseForApproval(
      UUID tenantId, UUID runId, String stepKey, WorkflowNode node, int leaseEpoch) {
    String action = approvalAction(node);
    String fingerprint = approvalFingerprint(node, stepKey);
    // Record the pause before anything else: this is the durable checkpoint of where execution
    // stopped, so a restart re-pauses rather than skipping the approval (S11-03 acceptance).
    approvalRepository.create(tenantId, runId, stepKey, action, fingerprint, actor, actor);
    // Park the run first (the higher-level pause); if the run's lease moved, the run is not ours
    // and the pause changes nothing — the next poll recovers (invariant 5).
    if (!transitionRun(
        tenantId,
        runId,
        WorkflowRunState.WAITING_FOR_APPROVAL,
        "run.approval-needed",
        leaseEpoch)) {
      return;
    }
    // Park the step; the transition service concludes the step's in-flight attempt (leaving
    // RUNNING), so no attempt lifecycle marker is left dangling (invariant 4).
    transitionStep(
        tenantId,
        runId,
        stepKey,
        WorkflowStepState.WAITING_FOR_APPROVAL,
        "step.approval-needed",
        leaseEpoch);
    log.info(
        "run {} paused at the human-approval boundary: step={} action={}", runId, stepKey, action);
    // Release the run's lease (best-effort): the run is paused, not owned by a live worker, so
    // clearing the lease makes it claimable again for the resume (S11-04) without a re-run of the
    // gated step. A concurrent re-claim in the gap leaves the lease it set intact.
    releaseRunLease(tenantId, runId, leaseEpoch);
  }

  /**
   * The short, human-readable summary of the gated side effect the decision binds to. It is a
   * redacted instruction, never a secret (invariant 8): the node's {@code action} is a display
   * summary, so a human can approve without seeing the payload the fingerprint pins.
   */
  private String approvalAction(WorkflowNode node) {
    Object action = node.config().get("action");
    return action == null ? node.type() : action.toString();
  }

  /**
   * The payload fingerprint the decision binds to (S11-06): the canonical-JSON SHA-256 of the step
   * plus the node's exact config — the tool / arguments / version it carries — so the same request
   * fingerprints identically on every pause, and the decision is bound to that digest, never the
   * raw payload (invariant 8: a fingerprint is a digest, not the payload). {@link ApprovalService}
   * recomputes the same digest from the run's pinned graph at the decision and refuses to approve a
   * request whose stored fingerprint no longer matches — the approve path can never authorize a
   * different argument set than the one pinned here.
   */
  private String approvalFingerprint(WorkflowNode node, String stepKey) {
    return ApprovalPayloadFingerprint.compute(stepKey, node.config());
  }

  /**
   * Release the run's lease after the pause (S11-03), best-effort. A {@link
   * com.example.agentflow.workflowexecution.domain.ConflictException} means the lease moved (the
   * run was re-claimed by another writer in the gap between the step and the release — a race the
   * fenced update refuses) or the run is already leaseless; in either case the run is not ours to
   * release, and the pause is durable regardless. It is never treated as a failure — releasing the
   * lease is idempotent: releasing an already-released lease is a no-op (invariant 3: the pause is
   * recorded once and observed; the lease release is a best-effort cleanup).
   */
  private void releaseRunLease(UUID tenantId, UUID runId, int leaseEpoch) {
    try {
      runRepository.releaseLease(tenantId, runId, leaseEpoch);
    } catch (com.example.agentflow.workflowexecution.domain.ConflictException e) {
      log.debug(
          "run {} lease release denied during human-approval pause: {}", runId, e.getMessage());
    }
  }

  /** Reconcile the run state once its steps are quiescent (S07-08). */
  private void reconcileRun(UUID tenantId, UUID runId, int leaseEpoch) {
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
      transitionRun(tenantId, runId, WorkflowRunState.CANCELLED, "run.cancelled", leaseEpoch);
      return;
    }
    if (allSucceeded) {
      transitionRun(tenantId, runId, WorkflowRunState.SUCCEEDED, "run.finished", leaseEpoch);
    } else {
      transitionRun(tenantId, runId, WorkflowRunState.FAILED, "run.failed", leaseEpoch);
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
      Map<String, String> upstreamOutputs,
      RunExecutionMonitor monitor) {
    return new StepExecutionContext(
        tenantId,
        runId,
        stepKey,
        node.type(),
        node.config(),
        stepInputResolver.resolve(node, nodes, upstreamOutputs),
        upstreamOutputs,
        monitor);
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
      UUID tenantId,
      UUID runId,
      String stepKey,
      WorkflowStepState to,
      String eventType,
      int leaseEpoch) {
    transitionService.transitionStep(tenantId, runId, stepKey, to, eventType, actor, leaseEpoch);
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
      String eventType,
      int leaseEpoch) {
    transitionService.transitionStep(
        tenantId, runId, stepKey, to, eventType, errorClass, safeReason, leaseEpoch);
  }

  /**
   * Transition a run, returning {@code false} when the transition could not be applied — a cross
   * tenant / missing run ({@code 404}) or a lost lease fence ({@code 409}), in which case the run
   * is not ours this poll.
   */
  private boolean transitionRun(
      UUID tenantId, UUID runId, WorkflowRunState to, String eventType, int leaseEpoch) {
    try {
      transitionService.transitionRun(tenantId, runId, to, eventType, actor, leaseEpoch);
      return true;
    } catch (ResponseStatusException e) {
      return false;
    }
  }

  /**
   * Transition a run into the documented, controlled {@code FAILED} state of a per-run execution
   * limit breach (S11-13), recording the breach's {@code limit_exceeded} error class and safe
   * reason. A lost-lease / cross-tenant {@code 404}/{@code 409} is contained (returns {@code
   * false}) rather than propagating: the current step already committed {@code FAILED} in an
   * earlier transaction, so the next poll reconciles the run to {@code FAILED}.
   */
  private boolean transitionRunError(
      UUID tenantId,
      UUID runId,
      WorkflowRunState to,
      String eventType,
      RunExecutionLimitExceeded limitEx,
      int leaseEpoch) {
    try {
      transitionService.transitionRun(
          tenantId,
          runId,
          to,
          eventType,
          actor,
          limitEx.errorClass(),
          limitEx.safeReason(),
          leaseEpoch);
      return true;
    } catch (ResponseStatusException e) {
      log.debug(
          "run {} failed at execution limit: transition to {} denied ({})",
          runId,
          to,
          e.getStatusCode());
      return false;
    }
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
