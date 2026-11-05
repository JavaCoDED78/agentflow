package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.InvalidStateException;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.RunStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.StepStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepAttemptHistoryRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.util.UUID;
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

  private final WorkflowRunRepository runRepository;
  private final WorkflowStepRunRepository stepRepository;
  private final WorkflowStepAttemptHistoryRepository attemptHistoryRepository;
  private final WorkflowEventService eventService;

  public RunStateMachineService(
      @Autowired(required = false) WorkflowRunRepository runRepository,
      @Autowired(required = false) WorkflowStepRunRepository stepRepository,
      @Autowired(required = false) WorkflowStepAttemptHistoryRepository attemptHistoryRepository,
      WorkflowEventService eventService) {
    this.runRepository = runRepository;
    this.stepRepository = stepRepository;
    this.attemptHistoryRepository = attemptHistoryRepository;
    this.eventService = eventService;
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
    applyRunTransition(runId, tenantId, from, current.version(), current.leaseEpoch(), to);
    appendRunEvent(tenantId, runId, from, to, eventType, actor);
    return to;
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
    applyStepTransition(runId, tenantId, stepKey, from, current.leaseEpoch(), to);
    recordStepAttempt(tenantId, runId, stepKey, from, to, actor, null, null);
    appendStepEvent(tenantId, runId, current.stepId(), stepKey, from, to, eventType, actor, null);
    return to;
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
    applyStepTransition(
        runId, tenantId, stepKey, from, current.leaseEpoch(), to, errorClass, safeReason);
    recordStepAttempt(tenantId, runId, stepKey, from, to, null, errorClass, safeReason);
    appendStepEvent(
        tenantId, runId, current.stepId(), stepKey, from, to, eventType, null, errorClass);
    return to;
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
    eventService.append(
        tenantId, runId, null, eventType, buildPayload(from, to) + actorSuffix(actor));
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
