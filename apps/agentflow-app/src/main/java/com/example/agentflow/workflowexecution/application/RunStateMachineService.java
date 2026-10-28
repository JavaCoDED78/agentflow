package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.messaging.application.WorkflowEventService;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.InvalidStateException;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.RunStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.StepStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
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
 *   <li><b>applies</b> a conditional update (optimistic-lock {@code version} on the run, {@code
 *       lease_epoch} fence on the step) whose zero-rows outcome is a {@link ConflictException}
 *       surfaced as {@code 409};
 *   <li><b>appends</b> one {@code workflow_events} row (through the {@code messaging} module's
 *       {@link WorkflowEventService}, module-map edge 13) recording {@code prior=&gt;new} &mdash;
 *       the state change and the history row commit and roll back together (invariant 3 / {@code
 *       ADR-004}).
 * </ol>
 *
 * <p>The methods are {@link Transactional}: the state and its event are one durable fact. When no
 * database is configured the dependency is {@link Autowired optional} and the service reports
 * {@code 503} instead of silently doing nothing (S01-02).
 *
 * <p>This type is the boundary that makes the state machine the only way state can change: it owns
 * the domain gate, the conditional update and the event append, and nothing else touches {@code
 * workflow_runs} / {@code workflow_step_runs} status.
 */
@Service
public class RunStateMachineService {

  private final WorkflowRunRepository runRepository;
  private final WorkflowStepRunRepository stepRepository;
  private final WorkflowEventService eventService;

  public RunStateMachineService(
      @Autowired(required = false) WorkflowRunRepository runRepository,
      @Autowired(required = false) WorkflowStepRunRepository stepRepository,
      WorkflowEventService eventService) {
    this.runRepository = runRepository;
    this.stepRepository = stepRepository;
    this.eventService = eventService;
  }

  /**
   * Move a run from its current state to {@code to}, the run-level half of the single transition
   * service.
   *
   * <p>Reads the run's current state through a tenant-scoped lookup (a cross-tenant id is a {@code
   * 404}), asserts {@code from -> to} with the domain gate (a forbidden pair is a {@code 400}),
   * applies a conditional update guarded by the optimistic {@code version} (a stale row is a {@code
   * 409}) and appends the transition fact in the same transaction.
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
   *     writer won the transition)
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
    applyRunTransition(runId, tenantId, from, current.version(), to);
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
   * transaction.
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
    appendStepEvent(tenantId, runId, current.stepId(), stepKey, from, to, eventType, actor);
    return to;
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
   * conditional update throws into a permanent {@code 409} (another writer won the transition,
   * invariant 4).
   */
  private void applyRunTransition(
      UUID runId, UUID tenantId, WorkflowRunState from, int expectedVersion, WorkflowRunState to) {
    try {
      runRepository.transition(runId, tenantId, from, expectedVersion, to);
    } catch (ConflictException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "run " + runId + " is out of date: " + e.getMessage());
    }
  }

  /**
   * Applies the guarded step update, converting the {@link ConflictException} a zero-rows
   * conditional update throws into a permanent {@code 409} (the lease fence moved or another writer
   * won the transition, invariant 4 / fencing).
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
    eventService.append(
        tenantId,
        runId,
        UUID.fromString(stepId),
        eventType,
        "step=" + stepKey + " " + buildPayload(from, to) + actorSuffix(actor));
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
