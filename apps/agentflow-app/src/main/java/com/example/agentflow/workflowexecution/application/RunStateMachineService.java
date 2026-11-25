package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.InvalidStateException;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.RunStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.RunView;
import com.example.agentflow.workflowexecution.persistence.StepStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepAttemptHistoryRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single point where a run or a step changes state (S05-11, the {@code RunStateMachineService}
 * named in {@code docs/architecture/module-api.md}).
 *
 * <p>Every transition goes through one of the {@link #transitionRun} / {@link #transitionStep}
 * methods &mdash; an application service, a controller or a Kafka listener never sets a run or step
 * status directly (invariant 4). Each method:
 *
 * <ol>
 *   <li><b>reads</b> the current state (and optimistic guard) through a tenant-scoped lookup; a
 *       cross-tenant id is a {@code 404} (invariant 1);
 *   <li><b>checks</b> the transition against the pure state machine ({@link
 *       WorkflowRunState#assertAllowed} / {@link WorkflowStepState#assertAllowed}); a forbidden
 *       {@code (from, to)} pair is a domain error surfaced as {@code 400};
 *   <li><b>applies</b> a conditional update (optimistic-lock {@code version} AND the run's {@code
 *       lease_epoch} fence on the run, {@code lease_epoch} fence on the step) whose zero-rows
 *       outcome is a {@link ConflictException} surfaced as {@code 409};
 *   <li><b>appends</b> one {@code workflow_events} row (through the {@code messaging} module's
 *       {@link WorkflowEventService}, module-map edge 13) recording {@code prior=&gt;new} &mdash;
 *       the state change and the history row commit and roll back together (invariant 3 / {@code
 *       ADR-004}).
 * </ol>
 *
 * <p>The methods are {@link Transactional}: the state and its event are one durable fact. The
 * attempt lifecycle (S07-14) is recorded in the same transaction here — the attempt begins when the
 * step enters {@code RUNNING} and concludes when it leaves it — so an attempt is never recorded
 * without the state change it describes, and a failed retry is investigable through the
 * tenant-scoped attempt-history read (no secret payload). When no database is configured the
 * dependency is {@link Autowired optional} and the service reports {@code 503} instead of silently
 * doing nothing (S01-02).
 *
 * <p>This type is the boundary that makes the state machine the only way state can change: it owns
 * the domain gate, the conditional update and the event append, and nothing else touches {@code
 * workflow_runs} / {@code workflow_step_runs} status.
 */
@Service
public class RunStateMachineService {

  /**
   * The run-transition counter (S13-11): one increment per run transition, labelled {@code state}
   * with the target state (a bounded closed vocabulary). Never carries an id.
   */
  static final String RUN_TOTAL_METRIC = "agentflow.workflowexecution.run.total";

  /** The run-duration timer (S13-11): run creation to the terminal transition, in seconds. */
  static final String RUN_DURATION_METRIC = "agentflow.workflowexecution.run.duration.seconds";

  /** The step-cancellation counter (S13-11): one increment per step transition to CANCELLED. */
  static final String STEP_CANCELLED_METRIC = "agentflow.workflowexecution.step.cancelled.total";

  private static final Logger log = LoggerFactory.getLogger(RunStateMachineService.class);

  private final WorkflowRunRepository runRepository;
  private final WorkflowStepRunRepository stepRepository;
  private final WorkflowStepAttemptHistoryRepository attemptHistoryRepository;
  private final WorkflowEventService eventService;
  private final RunQuotaGuard runQuotaGuard;
  private final MeterRegistry meterRegistry;
  private final Clock clock;

  public RunStateMachineService(
      @Autowired(required = false) WorkflowRunRepository runRepository,
      @Autowired(required = false) WorkflowStepRunRepository stepRepository,
      @Autowired(required = false) WorkflowStepAttemptHistoryRepository attemptHistoryRepository,
      WorkflowEventService eventService,
      @Autowired(required = false) RunQuotaGuard runQuotaGuard) {
    this(
        runRepository,
        stepRepository,
        attemptHistoryRepository,
        eventService,
        runQuotaGuard,
        null,
        null);
  }

  /**
   * Full constructor: also injects the {@link MeterRegistry} the transition meters register on and
   * the injectable {@link Clock} (invariant 10). A {@code null} registry is treated as "no shared
   * registry" and the meters are registered on a throwaway one (no observability surface).
   *
   * @param meterRegistry the registry the meters register on; may be {@code null}
   * @param clock the injectable clock; may be {@code null} (falls back to UTC)
   */
  @Autowired
  public RunStateMachineService(
      @Autowired(required = false) WorkflowRunRepository runRepository,
      @Autowired(required = false) WorkflowStepRunRepository stepRepository,
      @Autowired(required = false) WorkflowStepAttemptHistoryRepository attemptHistoryRepository,
      WorkflowEventService eventService,
      @Autowired(required = false) RunQuotaGuard runQuotaGuard,
      MeterRegistry meterRegistry,
      @Autowired(required = false) Clock clock) {
    this.runRepository = runRepository;
    this.stepRepository = stepRepository;
    this.attemptHistoryRepository = attemptHistoryRepository;
    this.eventService = eventService;
    this.runQuotaGuard = runQuotaGuard;
    this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  /**
   * Record the S13-11 run-transition meters after a successful run transition: one increment of
   * {@code run.total{state}} and, when the transition is terminal, one duration sample of {@code
   * run.duration.seconds} (run creation to the terminal state). Metering never fails a transition.
   */
  private void recordRunTransitionMetrics(UUID tenantId, UUID runId, WorkflowRunState to) {
    try {
      Counter.builder(RUN_TOTAL_METRIC)
          .tag("state", to.name().toLowerCase(Locale.ROOT))
          .description("Run transitions by target state (S13-11); bounded state label, never an id")
          .register(meterRegistry)
          .increment();
    } catch (RuntimeException e) {
      log.debug("run transition metric failed: {}", e.getMessage());
    }
    if (to == null || !to.isTerminal()) {
      return;
    }
    try {
      RunView view =
          runRepository == null ? null : runRepository.findRun(tenantId, runId).orElse(null);
      if (view == null || view.createdAt() == null) {
        return;
      }
      Duration duration = Duration.between(view.createdAt().toInstant(), clock.instant());
      if (duration.isNegative()) {
        duration = Duration.ZERO;
      }
      Timer.builder(RUN_DURATION_METRIC)
          .description("Run duration from creation to a terminal state (S13-11), in seconds")
          .register(meterRegistry)
          .record(duration);
    } catch (RuntimeException e) {
      log.debug("run duration metric failed: {}", e.getMessage());
    }
  }

  /**
   * Record the S13-11 step-cancellation counter after a successful step transition to CANCELLED.
   */
  private void recordStepTransitionMetrics(WorkflowStepState to) {
    if (to != WorkflowStepState.CANCELLED) {
      return;
    }
    try {
      Counter.builder(STEP_CANCELLED_METRIC)
          .description("Step transitions to CANCELLED (S13-11); no labels")
          .register(meterRegistry)
          .increment();
    } catch (RuntimeException e) {
      log.debug("step cancellation metric failed: {}", e.getMessage());
    }
  }

  /**
   * Move a run from its current state to {@code to}, the run-level half of the single transition
   * service.
   *
   * <p>Reads the run's current state through a tenant-scoped lookup (a cross-tenant id is a {@code
   * 404}), asserts {@code from -> to} with the domain gate (a forbidden pair is a {@code 400}),
   * applies a conditional update guarded by the optimistic {@code version} AND the run's {@code
   * lease_epoch} fence (a stale row or a moved lease &mdash; another worker re-claimed the run
   * &mdash; is a {@code 409}) and appends the transition fact in the same transaction.
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param runId the run to move
   * @param to the requested next state, one of the allowed successors of the current state
   * @param eventType the transition / trigger name recorded in {@code workflow_events}
   * @param actor the authenticated actor recorded in the event payload (nullable)
   * @return {@code to}, the state the run moved to
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 404} if no run
   *     matches {@code (tenantId, runId)}, {@code 400} if {@code from -> to} is not an allowed edge
   *     or one state is {@code null}, {@code 409} if the conditional update matched no row (another
   *     writer won the transition, or the run's {@code lease_epoch} fence moved after the read)
   */
  @Transactional
  public WorkflowRunState transitionRun(
      UUID tenantId, UUID runId, WorkflowRunState to, String eventType, String actor) {
    return transitionRunFlow(tenantId, runId, to, eventType, actor, null, null, null);
  }

  /**
   * Move a run to {@code to}, fenced on the caller's claimed {@code lease_epoch} (invariant 5).
   *
   * <p>The worker calls this variant: after a successful atomic claim it passes the {@code
   * lease_epoch} the claim returned, and the service refuses the transition ({@code 409}) when the
   * run's fence has moved in the gap — another worker re-claimed the run — so a stale owner can
   * never commit a result on a fence it no longer holds. It shares the fresh-read variant's flow;
   * the only difference is the assertion below, checked <em>before</em> the conditional update,
   * with the claimed fence used for the update itself.
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param runId the run to move
   * @param to the requested next state, one of the allowed successors of the current state
   * @param eventType the transition / trigger name recorded in {@code workflow_events}
   * @param actor the authenticated actor recorded in the event payload (nullable)
   * @param expectedLeaseEpoch the {@code lease_epoch} the caller's successful claim returned
   * @return {@code to}, the state the run moved to
   * @throws IllegalArgumentException {@code expectedLeaseEpoch} is not a positive epoch
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 404} if no run
   *     matches {@code (tenantId, runId)}, {@code 400} if {@code from -> to} is not an allowed
   *     edge, {@code 409} if the run's fence no longer matches the claimed epoch (the run was
   *     re-claimed by another worker)
   */
  @Transactional
  public WorkflowRunState transitionRun(
      UUID tenantId,
      UUID runId,
      WorkflowRunState to,
      String eventType,
      String actor,
      int expectedLeaseEpoch) {
    return transitionRunFlow(tenantId, runId, to, eventType, actor, null, null, expectedLeaseEpoch);
  }

  /**
   * Move a run to {@code to} (from the tenant-scoped current state) and record the {@link
   * com.example.agentflow.common.ErrorClass} token of the transition together with its short, safe
   * (redacted) reason (S11-13). Used by the worker to close a run in the documented, controlled
   * {@code FAILED} state the moment a per-run hard execution limit is exceeded.
   *
   * <p>The error metadata is persisted in the same transaction as the run state and, for the {@code
   * FAILED} transition, included in the run event payload. A {@code null} error class is recorded
   * as no error: a clean transition never clobbers an error a prior transition left. This overload
   * shares the read / assert / conditional-update / append flow of the {@link #transitionRun}
   * overload — the error is only metadata recorded for observability, it does not change which runs
   * are eligible.
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param runId the run to move
   * @param to the requested next state, one of the allowed successors of the current state
   * @param eventType the transition / trigger name recorded in {@code workflow_events}
   * @param actor the authenticated actor recorded in the event payload (nullable)
   * @param errorClass the {@code ErrorClass#errorClass()} token of the transition (nullable:
   *     records no error)
   * @param safeReason the short redacted description ({@code null} / blank preserves the existing
   *     value)
   * @return {@code to}, the state the run moved to
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 404} if no run
   *     matches {@code (tenantId, runId)}, {@code 400} if {@code from -> to} is not an allowed edge
   *     or one state is {@code null}, {@code 409} if the conditional update matched no row (another
   *     writer won the transition, or the run's {@code lease_epoch} fence moved after the read)
   */
  @Transactional
  public WorkflowRunState transitionRun(
      UUID tenantId,
      UUID runId,
      WorkflowRunState to,
      String eventType,
      String actor,
      String errorClass,
      String safeReason) {
    return transitionRunFlow(tenantId, runId, to, eventType, actor, errorClass, safeReason, null);
  }

  /**
   * Move a run to {@code to} with the fault's {@code ErrorClass} token and short, safe reason,
   * fenced on the caller's claimed {@code lease_epoch} (invariant 5). This is the worker's
   * controlled-{@code FAILED} exit for a per-run limit breach: the same flow as the fresh-read
   * variant, plus the claimed-epoch assertion.
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param runId the run to move
   * @param to the requested next state, one of the allowed successors of the current state
   * @param eventType the transition / trigger name recorded in {@code workflow_events}
   * @param actor the authenticated actor recorded in the event payload (nullable)
   * @param errorClass the {@code ErrorClass#errorClass()} token of the transition (nullable)
   * @param safeReason the short redacted description (nullable)
   * @param expectedLeaseEpoch the {@code lease_epoch} the caller's successful claim returned
   * @return {@code to}, the state the run moved to
   * @throws IllegalArgumentException {@code expectedLeaseEpoch} is not a positive epoch
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 404} if no run
   *     matches {@code (tenantId, runId)}, {@code 400} if {@code from -> to} is not an allowed
   *     edge, {@code 409} if the run's fence no longer matches the claimed epoch
   */
  @Transactional
  public WorkflowRunState transitionRun(
      UUID tenantId,
      UUID runId,
      WorkflowRunState to,
      String eventType,
      String actor,
      String errorClass,
      String safeReason,
      int expectedLeaseEpoch) {
    return transitionRunFlow(
        tenantId, runId, to, eventType, actor, errorClass, safeReason, expectedLeaseEpoch);
  }

  /**
   * Move a step from its current state to {@code to}, the step-level half of the single transition
   * service.
   *
   * <p>Reads the step's current state through a parent-run-tenant-scoped lookup (a step whose
   * parent run is a different tenant is a {@code 404}), asserts {@code from -> to} with the domain
   * gate (a forbidden pair is a {@code 400}), applies a conditional update guarded by the {@code
   * lease_epoch} fence (a stale fence is a {@code 409}) and appends the transition fact in the same
   * transaction. The attempt lifecycle (S07-14) is recorded in the same transaction: the attempt
   * begins on entry to {@code RUNNING} and concludes on leaving it.
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @param to the requested next state, one of the allowed successors of the current state
   * @param eventType the transition / trigger name recorded in {@code workflow_events}
   * @param actor the authenticated actor recorded in the event payload (nullable)
   * @return {@code to}, the state the step moved to
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 404} if no
   *     step matches for this tenant, {@code 400} if {@code from -> to} is not an allowed edge or
   *     one state is {@code null}, {@code 409} if the conditional update matched no row (the fence
   *     moved or another writer won the transition)
   */
  @Transactional
  public WorkflowStepState transitionStep(
      UUID tenantId,
      UUID runId,
      String stepKey,
      WorkflowStepState to,
      String eventType,
      String actor) {
    return transitionStepFlow(
        tenantId, runId, stepKey, to, eventType, actor, null, null, null, false);
  }

  /**
   * Move a step to {@code to}, fenced on the caller's claimed {@code lease_epoch} (invariant 5).
   *
   * <p>The worker calls this variant: after a successful atomic claim it passes the {@code
   * lease_epoch} the claim returned, and the service refuses the transition ({@code 409}) when the
   * step's fence has moved in the gap — another worker re-claimed the run — so a stale owner can
   * never commit a result on a fence it no longer holds. It shares the fresh-read variant's flow;
   * the only difference is the assertion below, checked <em>before</em> the conditional update,
   * with the claimed fence used for the update itself.
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @param to the requested next state, one of the allowed successors of the current state
   * @param eventType the transition / trigger name recorded in {@code workflow_events}
   * @param actor the authenticated actor recorded in the event payload (nullable)
   * @param expectedLeaseEpoch the {@code lease_epoch} the caller's successful claim returned
   * @return {@code to}, the state the step moved to
   * @throws IllegalArgumentException {@code expectedLeaseEpoch} is not a positive epoch
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 404} if no
   *     step matches for this tenant, {@code 400} if {@code from -> to} is not an allowed edge,
   *     {@code 409} if the step's fence no longer matches the claimed epoch
   */
  @Transactional
  public WorkflowStepState transitionStep(
      UUID tenantId,
      UUID runId,
      String stepKey,
      WorkflowStepState to,
      String eventType,
      String actor,
      int expectedLeaseEpoch) {
    return transitionStepFlow(
        tenantId, runId, stepKey, to, eventType, actor, null, null, expectedLeaseEpoch, false);
  }

  /**
   * Move a step to {@code to} (from the tenant-scoped current state) and record the {@link
   * com.example.agentflow.common.ErrorClass} token of a step failure together with its short, safe
   * (redacted) reason (S07-11).
   *
   * <p>Used by the worker to record a faulted step as {@code RETRY_WAIT} (a transient fault —
   * retried with bounded backoff) or {@code FAILED} (a permanent fault — terminal). The error
   * metadata is persisted in the same transaction as the step state and, for a terminal transition,
   * included in the step event payload. A {@code null} error class is recorded as no error: it is
   * used for a retry that carries no new fault and for a success, so a clean transition never
   * clobbers an error a prior attempt left. The faulted attempt is also concluded in that same
   * transaction (S07-14): the {@code error_class} / {@code safe_reason} recorded here are the safe
   * error category the tenant-scoped attempt-history read surfaces, never the raw error.
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @param to the requested next state, one of the allowed successors of the current state
   * @param eventType the transition / trigger name recorded in {@code workflow_events}
   * @param errorClass the fault's {@code ErrorClass#errorClass()} token (nullable: records no
   *     error)
   * @param safeReason the short redacted description ({@code null} / blank preserves the existing
   *     value)
   * @return {@code to}, the state the step moved to
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 404} if no
   *     step matches for this tenant, {@code 400} if {@code from -> to} is not an allowed edge or
   *     one state is {@code null}, {@code 409} if the conditional update matched no row (the fence
   *     moved or another writer won the transition)
   */
  @Transactional
  public WorkflowStepState transitionStep(
      UUID tenantId,
      UUID runId,
      String stepKey,
      WorkflowStepState to,
      String eventType,
      String errorClass,
      String safeReason) {
    return transitionStepFlow(
        tenantId, runId, stepKey, to, eventType, null, errorClass, safeReason, null, true);
  }

  /**
   * Move a step to {@code to} with the fault's {@code ErrorClass} token and short, safe reason,
   * fenced on the caller's claimed {@code lease_epoch} (invariant 5). The worker calls this variant
   * for the RETRY_WAIT / FAILED exit of a classified fault.
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @param to the requested next state, one of the allowed successors of the current state
   * @param eventType the transition / trigger name recorded in {@code workflow_events}
   * @param errorClass the fault's {@code ErrorClass#errorClass()} token (nullable)
   * @param safeReason the short redacted description (nullable)
   * @param expectedLeaseEpoch the {@code lease_epoch} the caller's successful claim returned
   * @return {@code to}, the state the step moved to
   * @throws IllegalArgumentException {@code expectedLeaseEpoch} is not a positive epoch
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 404} if no
   *     step matches for this tenant, {@code 400} if {@code from -> to} is not an allowed edge,
   *     {@code 409} if the step's fence no longer matches the claimed epoch
   */
  @Transactional
  public WorkflowStepState transitionStep(
      UUID tenantId,
      UUID runId,
      String stepKey,
      WorkflowStepState to,
      String eventType,
      String errorClass,
      String safeReason,
      int expectedLeaseEpoch) {
    return transitionStepFlow(
        tenantId,
        runId,
        stepKey,
        to,
        eventType,
        null,
        errorClass,
        safeReason,
        expectedLeaseEpoch,
        true);
  }

  /**
   * Shared flow for the run transition variants (fresh-read and claimed-epoch). Reads the current
   * state through a tenant-scoped lookup (a cross-tenant id is a {@code 404}), asserts the {@code
   * from -> to} edge (a forbidden pair is a {@code 400}) and — for the claimed-epoch variants —
   * refuses the transition ({@code 409}) when the run's fence has moved, before the conditional
   * update. The update is fenced on the claimed epoch when one is passed (the fresh-read variants
   * fence on the freshly read epoch), and the transition fact is appended in the same transaction.
   *
   * @param expectedLeaseEpoch the epoch the caller claimed to hold, or {@code null} to fence on the
   *     freshly read epoch (the fresh-read variants)
   */
  private WorkflowRunState transitionRunFlow(
      UUID tenantId,
      UUID runId,
      WorkflowRunState to,
      String eventType,
      String actor,
      String errorClass,
      String safeReason,
      Integer expectedLeaseEpoch) {
    requireRepository(runRepository);
    RunStateSnapshot current =
        runRepository
            .findState(tenantId, runId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "run " + runId + " was not found for this tenant"));

    WorkflowRunState from = current.status();
    assertRunTransition(from, to);
    int fenceEpoch = current.leaseEpoch();
    if (expectedLeaseEpoch != null) {
      requireClaimedEpoch(expectedLeaseEpoch, current.leaseEpoch(), "run " + runId);
      fenceEpoch = expectedLeaseEpoch;
    }
    applyRunTransition(runId, tenantId, from, current.version(), fenceEpoch, to);
    appendRunEvent(tenantId, runId, from, to, eventType, actor, errorClass);
    releaseQuotaSlotAtTerminal(tenantId, runId, to);
    recordRunTransitionMetrics(tenantId, runId, to);
    return to;
  }

  /**
   * Shared flow for the step transition variants (fresh-read and claimed-epoch). Reads the step's
   * current state through a tenant-scoped lookup (a cross-tenant id is a {@code 404}), asserts the
   * {@code from -> to} edge (a forbidden pair is a {@code 400}) and — for the claimed-epoch
   * variants — refuses the transition ({@code 409}) when the step's fence has moved, before the
   * conditional update. The update is fenced on the claimed epoch when one is passed (the
   * fresh-read variants fence on the freshly read epoch), and the transition fact + attempt
   * lifecycle are appended in the same transaction.
   *
   * @param actor the worker instance recorded in the event payload (nullable)
   * @param errorClass the fault's {@code ErrorClass} token (nullable: records no error metadata)
   * @param safeReason the short redacted description (nullable)
   * @param expectedLeaseEpoch the epoch the caller claimed to hold, or {@code null} to fence on the
   *     freshly read epoch (the fresh-read variants)
   * @param isFault true when the caller asked to record fault metadata (the {@code errorClass} /
   *     {@code safeReason} — even when null): the repository's 8-arg {@code transition} overload is
   *     used. A clean transition uses the 6-arg overload, which never writes an error column.
   */
  private WorkflowStepState transitionStepFlow(
      UUID tenantId,
      UUID runId,
      String stepKey,
      WorkflowStepState to,
      String eventType,
      String actor,
      String errorClass,
      String safeReason,
      Integer expectedLeaseEpoch,
      boolean isFault) {
    requireRepository(stepRepository);
    StepStateSnapshot current =
        stepRepository
            .findState(tenantId, runId, stepKey)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "step '"
                            + stepKey
                            + "' on run "
                            + runId
                            + " was not found for this tenant"));

    WorkflowStepState from = current.status();
    assertStepTransition(from, to);
    int fenceEpoch = current.leaseEpoch();
    if (expectedLeaseEpoch != null) {
      requireClaimedEpoch(
          expectedLeaseEpoch, current.leaseEpoch(), "step '" + stepKey + "' on run " + runId);
      fenceEpoch = expectedLeaseEpoch;
    }
    if (isFault) {
      applyStepTransition(runId, tenantId, stepKey, from, fenceEpoch, to, errorClass, safeReason);
    } else {
      applyStepTransition(runId, tenantId, stepKey, from, fenceEpoch, to);
    }
    recordStepAttempt(tenantId, runId, stepKey, from, to, actor, errorClass, safeReason);
    appendStepEvent(
        tenantId, runId, current.stepId(), stepKey, from, to, eventType, actor, errorClass);
    recordStepTransitionMetrics(to);
    return to;
  }

  /**
   * Validate the caller's claimed {@code lease_epoch} against the row's fence, refusing the
   * transition ({@code 409}) when they disagree: the caller claimed a lease that has since been
   * re-taken (invariant 5, ADR-011 fencing). A non-positive claimed epoch is a programming error
   * and is rejected as {@code IllegalArgumentException}.
   */
  private static void requireClaimedEpoch(
      int expectedLeaseEpoch, int currentLeaseEpoch, String subject) {
    if (expectedLeaseEpoch < 1) {
      throw new IllegalArgumentException(
          "expectedLeaseEpoch must be >= 1 (the claimed lease_epoch), got " + expectedLeaseEpoch);
    }
    if (currentLeaseEpoch != expectedLeaseEpoch) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          subject
              + " is no longer owned by this worker (claimed lease epoch "
              + expectedLeaseEpoch
              + ", current "
              + currentLeaseEpoch
              + ")");
    }
  }

  /**
   * Record the attempt lifecycle of a step transition in the {@code workflow_step_attempt_history}
   * table (S07-14), joined to the same transaction as the state change (invariant 3): the attempt
   * <em>begins</em> when the step enters {@code RUNNING} (a fresh, monotonic attempt number, the
   * running worker instance and the start instant) and <em>concludes</em> when it leaves {@code
   * RUNNING} for any other state (its outcome, the finish instant and, for a fault, the safe error
   * category). A {@code null} history repository (no database configured, S01-02) is a no-op, so
   * the transition still works in a DB-less skeleton. The two overloads share this one helper: the
   * 6-arg clean path carries the {@code actor} as the worker instance; the 8-arg fault path carries
   * the classified {@code errorClass} / {@code safeReason}.
   *
   * @param tenantId the owning tenant (verified by the caller's tenant-scoped read)
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @param from the state the step was in before the transition
   * @param to the state the step moved to
   * @param actor the worker instance recording the attempt (nullable)
   * @param errorClass the fault's {@code ErrorClass} token (nullable: records no error)
   * @param safeReason the short redacted reason (nullable: records no error)
   */
  private void recordStepAttempt(
      UUID tenantId,
      UUID runId,
      String stepKey,
      WorkflowStepState from,
      WorkflowStepState to,
      String actor,
      String errorClass,
      String safeReason) {
    WorkflowStepAttemptHistoryRepository repo = this.attemptHistoryRepository;
    if (repo == null) {
      return;
    }
    if (to == WorkflowStepState.RUNNING) {
      // A step re-enters RUNNING (the first READY -> RUNNING and each retry-restart): a fresh
      // attempt begins. The worker instance is the 6-arg actor; the 8-arg path (RETRY_WAIT ->
      // RUNNING in tests) begins with a NULL worker instance, concluded later with the class.
      repo.beginAttempt(tenantId, runId, stepKey, actor);
    } else if (from == WorkflowStepState.RUNNING) {
      // The attempt left RUNNING: a clean transition (SUCCEEDED / CANCELLED / WAITING_FOR_APPROVAL)
      // records only its outcome; a fault (the 8-arg RETRY_WAIT / FAILED path) records the safe
      // error category and the redacted reason. The worker instance set at begin survives.
      repo.concludeAttempt(tenantId, runId, stepKey, to.name(), errorClass, safeReason);
    }
  }

  /** Asserts the run edge, mapping the domain gate's refusal to a permanent {@code 400}. */
  private void assertRunTransition(WorkflowRunState from, WorkflowRunState to) {
    try {
      WorkflowRunState.assertAllowed(from, to);
    } catch (InvalidStateException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /** Asserts the step edge, mapping the domain gate's refusal to a permanent {@code 400}. */
  private void assertStepTransition(WorkflowStepState from, WorkflowStepState to) {
    try {
      WorkflowStepState.assertAllowed(from, to);
    } catch (InvalidStateException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Applies the guarded run update, converting the {@link ConflictException} a zero-rows
   * conditional update throws into a permanent {@code 409} (the optimistic lock moved, the run's
   * {@code lease_epoch} fence moved, or another writer won the transition &mdash; invariant 4 /
   * ADR-011 fencing).
   */
  private void applyRunTransition(
      UUID runId,
      UUID tenantId,
      WorkflowRunState from,
      int expectedVersion,
      int expectedLeaseEpoch,
      WorkflowRunState to) {
    try {
      runRepository.transition(runId, tenantId, from, expectedVersion, expectedLeaseEpoch, to);
    } catch (ConflictException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "run " + runId + " is out of date: " + e.getMessage());
    }
  }

  /**
   * Applies the guarded step update with no error metadata (the 6-arg path): the transition that a
   * non-faulting step makes (ready, running, success, cancellation, approval). The 6-arg repository
   * overload records no error columns, so a clean transition never writes an error a prior attempt
   * may have left.
   */
  private void applyStepTransition(
      UUID runId,
      UUID tenantId,
      String stepKey,
      WorkflowStepState from,
      int expectedLeaseEpoch,
      WorkflowStepState to) {
    try {
      stepRepository.transition(runId, tenantId, stepKey, from, expectedLeaseEpoch, to);
    } catch (ConflictException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "step '" + stepKey + "' on run " + runId + " is out of date");
    }
  }

  /**
   * Applies the guarded step update, recording the fault's {@code ErrorClass} token and its short
   * safe reason when non-null (S07-11). A {@code null} error class — a retry (to {@code
   * RETRY_WAIT}) or a success — records no error metadata; the error is set on the faulting
   * transition only (the {@code COALESCE} in the repository update preserves the value until the
   * {@code S07-14} attempt-lifecycle clears it).
   */
  private void applyStepTransition(
      UUID runId,
      UUID tenantId,
      String stepKey,
      WorkflowStepState from,
      int expectedLeaseEpoch,
      WorkflowStepState to,
      String errorClass,
      String safeReason) {
    try {
      stepRepository.transition(
          runId, tenantId, stepKey, from, expectedLeaseEpoch, to, errorClass, safeReason);
    } catch (ConflictException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "step '" + stepKey + "' on run " + runId + " is out of date");
    }
  }

  /**
   * Appends the run transition fact in the same transaction with a {@code prior -> new} payload.
   */
  private void appendRunEvent(
      UUID tenantId,
      UUID runId,
      WorkflowRunState from,
      WorkflowRunState to,
      String eventType,
      String actor) {
    appendRunEvent(tenantId, runId, from, to, eventType, actor, null);
  }

  /**
   * Appends the run transition fact in the same transaction, and, when a transition was recorded
   * with an error class (S11-13), appends {@code error=<class>} to the payload so the run's {@code
   * FAILED} carries the documented reason (mirrors the step payload, {@link #appendStepEvent}).
   */
  private void appendRunEvent(
      UUID tenantId,
      UUID runId,
      WorkflowRunState from,
      WorkflowRunState to,
      String eventType,
      String actor,
      String errorClass) {
    String payload = buildPayload(from, to) + actorSuffix(actor);
    if (errorClass != null && !errorClass.isBlank()) {
      payload = payload + " error=" + errorClass;
    }
    eventService.append(tenantId, runId, null, eventType, payload);
  }

  /** Appends the step transition fact in the same transaction, scoped to the step's own row. */
  private void appendStepEvent(
      UUID tenantId,
      UUID runId,
      String stepId,
      String stepKey,
      WorkflowStepState from,
      WorkflowStepState to,
      String eventType,
      String actor) {
    appendStepEvent(tenantId, runId, stepId, stepKey, from, to, eventType, actor, null);
  }

  /**
   * Appends the step transition fact in the same transaction, scoped to the step's own row, and,
   * when a fault was recorded (S07-11), appends {@code error=<class>} to the payload. The class is
   * included for both terminal ({@code FAILED}) and transient ({@code RETRY_WAIT}) faults, so the
   * history names the fault; a {@code null} class (a normal transition) records nothing extra.
   */
  private void appendStepEvent(
      UUID tenantId,
      UUID runId,
      String stepId,
      String stepKey,
      WorkflowStepState from,
      WorkflowStepState to,
      String eventType,
      String actor,
      String errorClass) {
    String payload = "step=" + stepKey + " " + buildPayload(from, to) + actorSuffix(actor);
    if (errorClass != null && !errorClass.isBlank()) {
      payload = payload + " error=" + errorClass;
    }
    eventService.append(tenantId, runId, UUID.fromString(stepId), eventType, payload);
  }

  /**
   * @return the redacted {@code prior -> new} state pair, e.g. {@code "prior=RUNNING
   *     new=SUCCEEDED"}.
   */
  private static String buildPayload(Object from, Object to) {
    return "prior=" + from + " new=" + to;
  }

  /**
   * @return a leading {@code " (actor=...)} when an actor is supplied, otherwise an empty string.
   */
  private static String actorSuffix(String actor) {
    return (actor != null && !actor.isBlank()) ? " (actor=" + actor + ")" : "";
  }

  /**
   * Releases the run's parallel-run quota slot when the transition closes the run (S12-05): the
   * slot lifetime is exactly the run's non-terminal lifetime - it is acquired at run creation and
   * freed the moment the run reaches {@code SUCCEEDED} / {@code FAILED} / {@code CANCELLED}. Called
   * from both {@code transitionRun} overloads after the transition and its event are applied, in
   * the same transaction, so the release is atomic with the terminal state. Idempotent: the usage
   * side deletes a row keyed {@code (tenantId, runId)}, so a repeat (or a run that never acquired a
   * slot) changes nothing. The guard is {@code null} when no database is configured (S01-02) or in
   * tests that construct the service directly.
   */
  private void releaseQuotaSlotAtTerminal(UUID tenantId, UUID runId, WorkflowRunState to) {
    if (runQuotaGuard != null && to.isTerminal()) {
      runQuotaGuard.releaseRunSlot(tenantId, runId);
    }
  }

  /**
   * @return the repository, or a {@code 503} when no database is configured (see S01-02).
   */
  private <T> T requireRepository(T repository) {
    if (repository == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "workflow transitions are unavailable: no database connection is configured");
    }
    return repository;
  }
}
