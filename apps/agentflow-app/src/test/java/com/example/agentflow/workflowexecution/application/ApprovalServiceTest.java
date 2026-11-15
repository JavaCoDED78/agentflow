package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.workflowdefinition.application.WorkflowVersionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import com.example.agentflow.workflowexecution.domain.ApprovalPayloadFingerprint;
import com.example.agentflow.workflowexecution.domain.ApprovalStatus;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.ApprovalRepository;
import com.example.agentflow.workflowexecution.persistence.ApprovalRow;
import com.example.agentflow.workflowexecution.persistence.OverdueApprovalRow;
import com.example.agentflow.workflowexecution.persistence.RunView;
import com.example.agentflow.workflowexecution.persistence.StepStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * DB-free tests for the approval decision lifecycle ({@link ApprovalService}: S11-04 approve,
 * S11-05 reject + expiry). The repositories, the transition service, the outbox and the audit
 * service are Mockito mocks, so every guard — tenant (404), status/expiry/step (409), the
 * conditional write, the atomic audit append, both idempotent replay mechanisms and the
 * resume/refusal completion (run + step transitions + the outbox continuation/refusal) — is
 * asserted directly; the real conditional update runs against a real PostgreSQL row in the
 * integration suite.
 */
class ApprovalServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID RUN = UUID.randomUUID();
  private static final UUID APPROVAL = UUID.randomUUID();
  private static final UUID VERSION = UUID.randomUUID();
  private static final String STEP = "send-monthly-report";
  private static final String ACTOR = "approver@acme.example";
  // the exact gated payload the approval pins: the tool, its arguments and the display summary —
  // the fingerprint below is computed by the same production algorithm the worker pauses with
  private static final Map<String, Object> GATE_CONFIG =
      Map.of(
          "action",
          "Send the monthly financial report",
          "tool",
          "email.send",
          "arguments",
          Map.of("to", "finance@acme.example"));
  private static final String PAYLOAD_FINGERPRINT =
      ApprovalPayloadFingerprint.compute(STEP, GATE_CONFIG);

  private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
  private final WorkflowStepRunRepository stepRepository = mock(WorkflowStepRunRepository.class);
  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final WorkflowVersionService versionService = mock(WorkflowVersionService.class);
  private final AuditService auditService = mock(AuditService.class);
  private final RunStateMachineService transitionService = mock(RunStateMachineService.class);
  private final OutboxService outboxService = mock(OutboxService.class);

  private final ApprovalService service =
      new ApprovalService(
          approvalRepository,
          stepRepository,
          runRepository,
          versionService,
          auditService,
          transitionService,
          outboxService,
          FIXED_CLOCK);

  @Test
  void approve_records_the_decision_and_audits_it_in_the_same_flow() {
    ApprovalRow pending = pendingRow(null);
    ApprovalRow decided = decidedRow(pending, "looks safe");
    when(approvalRepository.find(TENANT, RUN, APPROVAL))
        .thenReturn(Optional.of(pending))
        .thenReturn(Optional.of(decided));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 3)));
    stubPinnedGraph();

    ApprovalDecisionDto dto =
        service.approve(TENANT, ACTOR, RUN, APPROVAL, "looks safe", null, null);

    assertThat(dto.status()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(dto.decisionActor()).isEqualTo(ACTOR);
    assertThat(dto.decisionAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    assertThat(dto.decisionComment()).isEqualTo("looks safe");

    verify(approvalRepository)
        .recordApprove(
            eq(TENANT),
            eq(RUN),
            eq(APPROVAL),
            eq(ACTOR),
            eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)),
            eq("looks safe"),
            eq(null),
            eq(null),
            eq(0));
    // The audit append joins the same transaction: the verb/resource pair names the approval
    // decision, the metadata is the redacted step fact — never the comment, the payload or a
    // secret.
    verify(auditService)
        .record(
            eq(TENANT),
            eq(ACTOR),
            eq(AuditAction.APPROVE),
            eq(AuditResourceType.APPROVAL),
            eq(APPROVAL),
            eq("step=" + STEP));
  }

  @Test
  void a_repeat_approve_replays_the_recorded_decision_without_a_second_write() {
    ApprovalRow decided = decidedRow(pendingRow(null), "first call");
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(decided));

    ApprovalDecisionDto replay =
        service.approve(TENANT, ACTOR, RUN, APPROVAL, "second call", null, null);

    assertThat(replay.decisionComment()).isEqualTo("first call");
    verify(approvalRepository, never())
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    verifyNoInteractions(stepRepository);
  }

  @Test
  void a_missing_or_cross_tenant_approval_is_a_404() {
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
    verify(approvalRepository, never())
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void an_approval_decided_otherwise_is_a_409() {
    ApprovalRow rejected =
        new ApprovalRow(
            APPROVAL,
            RUN,
            STEP,
            PAYLOAD_FINGERPRINT,
            ApprovalStatus.REJECTED.name(),
            null,
            1,
            "other-actor",
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
            "no",
            null,
            null);
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(rejected));

    assertThatThrownBy(() -> service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(approvalRepository, never())
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
  }

  @Test
  void an_expired_approval_cannot_be_approved() {
    ApprovalRow expired =
        pendingRowWithExpiry(null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusSeconds(1));
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(expired));

    assertThatThrownBy(() -> service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(approvalRepository, never())
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void an_approval_whose_expiry_is_still_ahead_is_decidable() {
    ApprovalRow fresh =
        pendingRowWithExpiry(null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusHours(1));
    ApprovalRow decided = decidedRow(fresh, null);
    when(approvalRepository.find(TENANT, RUN, APPROVAL))
        .thenReturn(Optional.of(fresh))
        .thenReturn(Optional.of(decided));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));
    stubPinnedGraph();

    ApprovalDecisionDto dto = service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null);

    assertThat(dto.status()).isEqualTo(ApprovalStatus.APPROVED);
  }

  @Test
  void a_step_that_moved_on_makes_the_decision_a_409() {
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(pendingRow(null)));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(UUID.randomUUID().toString(), WorkflowStepState.RUNNING, 4)));

    assertThatThrownBy(() -> service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(approvalRepository, never())
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
  }

  @Test
  void a_missing_step_is_a_404() {
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(pendingRow(null)));
    when(stepRepository.findState(TENANT, RUN, STEP)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void a_lost_race_replays_the_winning_approve_decision() {
    when(approvalRepository.find(TENANT, RUN, APPROVAL))
        .thenReturn(Optional.of(pendingRow(null)))
        .thenReturn(Optional.of(decidedRow(pendingRow(null), "concurrent winner")));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));
    stubPinnedGraph();
    doThrow(new ConflictException("another decision won the race"))
        .when(approvalRepository)
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());

    // The conditional update lost the race; the re-read shows an APPROVED approval, so the repeat
    // replays the winning decision instead of surfacing a 409 (at-least-once, ADR-003).
    ApprovalDecisionDto replay = service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null);

    assertThat(replay.status()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(replay.decisionComment()).isEqualTo("concurrent winner");
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void a_lost_race_to_a_different_outcome_is_a_409() {
    ApprovalRow stillPending = pendingRow(null);
    when(approvalRepository.find(TENANT, RUN, APPROVAL))
        .thenReturn(Optional.of(stillPending))
        .thenReturn(Optional.of(stillPending));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));
    stubPinnedGraph();
    doThrow(new ConflictException("the row moved"))
        .when(approvalRepository)
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());

    assertThatThrownBy(() -> service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void a_keyed_repeat_replays_without_even_reading_the_targeted_approval() {
    UUID key = UUID.randomUUID();
    String fingerprint = "fingerprint-of-the-original-payload";
    ApprovalRow decided = decidedRow(pendingRow(key), null);
    decided = rowWithKey(decided, key, fingerprint);
    when(approvalRepository.findByIdempotencyKey(TENANT, key)).thenReturn(Optional.of(decided));

    ApprovalDecisionDto replay =
        service.approve(TENANT, ACTOR, RUN, APPROVAL, null, key, fingerprint);

    assertThat(replay.status()).isEqualTo(ApprovalStatus.APPROVED);
    // The keyed lookup answered before the path ids were read: no second decision, no audit.
    verify(approvalRepository, never()).find(any(), any(), any());
    verify(approvalRepository, never())
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void a_keyed_repeat_with_a_different_payload_is_a_409() {
    UUID key = UUID.randomUUID();
    ApprovalRow decided =
        rowWithKey(decidedRow(pendingRow(key), null), key, "original-fingerprint");
    when(approvalRepository.findByIdempotencyKey(TENANT, key)).thenReturn(Optional.of(decided));

    assertThatThrownBy(
            () ->
                service.approve(
                    TENANT, ACTOR, RUN, APPROVAL, "changed", key, "changed-fingerprint"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(approvalRepository, never())
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
  }

  @Test
  void a_duplicate_key_race_is_answered_from_the_keyed_row() {
    UUID key = UUID.randomUUID();
    String fingerprint = "the-payload-both-racers-sent";
    ApprovalRow keyedWinner = rowWithKey(decidedRow(pendingRow(key), null), key, fingerprint);
    when(approvalRepository.findByIdempotencyKey(TENANT, key))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(keyedWinner));
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(pendingRow(null)));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));
    stubPinnedGraph();
    doThrow(new DuplicateKeyException("approvals_tenant_idempotency_key"))
        .when(approvalRepository)
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());

    // The unique guard turned the race into an answerable fact: the loser replies from the keyed
    // row instead of failing the request.
    ApprovalDecisionDto replay =
        service.approve(TENANT, ACTOR, RUN, APPROVAL, null, key, fingerprint);

    assertThat(replay.status()).isEqualTo(ApprovalStatus.APPROVED);
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void no_database_is_a_503() {
    ApprovalService noDb =
        new ApprovalService(
            null, null, null, null, auditService, transitionService, outboxService, FIXED_CLOCK);

    assertThatThrownBy(() -> noDb.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    assertThatThrownBy(() -> noDb.reject(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    verifyNoInteractions(auditService);
  }

  @Test
  void the_decision_write_carries_the_key_and_fingerprint_it_was_decided_under() {
    UUID key = UUID.randomUUID();
    String fingerprint = "sha256-of-the-canonical-payload";
    ApprovalRow pending = pendingRow(null);
    ApprovalRow decided = rowWithKey(decidedRow(pending, null), key, fingerprint);
    when(approvalRepository.find(TENANT, RUN, APPROVAL))
        .thenReturn(Optional.of(pending))
        .thenReturn(Optional.of(decided));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));
    stubPinnedGraph();

    service.approve(TENANT, ACTOR, RUN, APPROVAL, null, key, fingerprint);

    ArgumentCaptor<UUID> keyCaptor = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> fingerprintCaptor = ArgumentCaptor.forClass(String.class);
    verify(approvalRepository)
        .recordApprove(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            keyCaptor.capture(),
            fingerprintCaptor.capture(),
            anyInt());
    assertThat(keyCaptor.getValue()).isEqualTo(key);
    assertThat(fingerprintCaptor.getValue()).isEqualTo(fingerprint);
  }

  @Test
  void approve_resumes_the_run_and_publishes_the_continuation_through_the_outbox() {
    ApprovalRow pending = pendingRow(null);
    ApprovalRow decided = decidedRow(pending, null);
    when(approvalRepository.find(TENANT, RUN, APPROVAL))
        .thenReturn(Optional.of(pending))
        .thenReturn(Optional.of(decided));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));
    stubPinnedGraph();

    service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null);

    // The resume (S11-05): the run leaves the pause, the gate step's execution — the human
    // decision — concludes SUCCEEDED, and the continuation is the only publication.
    verify(transitionService)
        .transitionRun(
            eq(TENANT), eq(RUN), eq(WorkflowRunState.RUNNING), eq("run.approved"), eq(ACTOR));
    verify(transitionService)
        .transitionStep(
            eq(TENANT),
            eq(RUN),
            eq(STEP),
            eq(WorkflowStepState.RUNNING),
            eq("run.approved"),
            eq(ACTOR));
    verify(transitionService)
        .transitionStep(
            eq(TENANT),
            eq(RUN),
            eq(STEP),
            eq(WorkflowStepState.SUCCEEDED),
            eq("step.approved"),
            eq(ACTOR));
    ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxService).enqueue(event.capture());
    assertThat(event.getValue().topic()).isEqualTo("agentflow.workflow.run-resumed.v1");
    assertThat(event.getValue().aggregateType()).isEqualTo("workflow_run");
    assertThat(event.getValue().aggregateId()).isEqualTo(RUN);
    assertThat(event.getValue().payloadJson()).contains("\"runId\":\"" + RUN + "\"");
    assertThat(event.getValue().eventKey()).isEqualTo(TENANT + ":" + RUN);
  }

  @Test
  void an_approve_revalidates_the_payload_fingerprint_against_the_pinned_graph() {
    // S11-06: the decision is the moment the approved argument set is authorized to execute, so
    // the service must consult the run's pinned graph and compare the freshly computed digest with
    // the one stored on the request.
    when(approvalRepository.find(TENANT, RUN, APPROVAL))
        .thenReturn(Optional.of(pendingRow(null)))
        .thenReturn(Optional.of(decidedRow(pendingRow(null), null)));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));
    stubPinnedGraph();

    service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null);

    verify(runRepository).findRun(TENANT, RUN);
    verify(versionService).loadGraph(TENANT, VERSION);
    verify(approvalRepository)
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
  }

  @Test
  void an_approve_whose_gated_parameters_changed_is_a_409_without_side_effects() {
    // S11-06 acceptance: an approval of one argument set can never authorize the execution of
    // another — the pinned graph now carries a different payload than the one fingerprinted on the
    // request, so the decision is refused and nothing is written.
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(pendingRow(null)));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));
    when(runRepository.findRun(TENANT, RUN))
        .thenReturn(
            Optional.of(
                new RunView(
                    RUN,
                    VERSION,
                    WorkflowRunState.WAITING_FOR_APPROVAL,
                    1,
                    OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))));
    Map<String, Object> tamperedConfig =
        Map.of(
            "action",
            "Send the monthly financial report",
            "tool",
            "email.send",
            "arguments",
            Map.of("to", "attacker@evil.example"));
    when(versionService.loadGraph(TENANT, VERSION))
        .thenReturn(
            new WorkflowGraph(
                List.of(new WorkflowNode(STEP, "HUMAN_APPROVAL", tamperedConfig)), List.of()));

    assertThatThrownBy(() -> service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              assertThat(((ResponseStatusException) e).getStatusCode())
                  .isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.getMessage()).contains("fingerprint");
            });
    verify(approvalRepository, never())
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    verifyNoInteractions(transitionService);
    verifyNoInteractions(outboxService);
  }

  @Test
  void an_approve_whose_step_is_gone_from_the_pinned_graph_is_a_409() {
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(pendingRow(null)));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));
    when(runRepository.findRun(TENANT, RUN))
        .thenReturn(
            Optional.of(
                new RunView(
                    RUN,
                    VERSION,
                    WorkflowRunState.WAITING_FOR_APPROVAL,
                    1,
                    OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))));
    // the pinned graph no longer holds the gated step: the binding is unverifiable, so the
    // decision is refused rather than authorizing an unknown payload
    when(versionService.loadGraph(TENANT, VERSION))
        .thenReturn(
            new WorkflowGraph(
                List.of(new WorkflowNode("another-step", "NOOP", Map.of())), List.of()));

    assertThatThrownBy(() -> service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(approvalRepository, never())
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verifyNoInteractions(transitionService);
    verifyNoInteractions(outboxService);
  }

  @Test
  void an_approve_with_a_missing_pinned_graph_is_a_409() {
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(pendingRow(null)));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));
    when(runRepository.findRun(TENANT, RUN))
        .thenReturn(
            Optional.of(
                new RunView(
                    RUN,
                    VERSION,
                    WorkflowRunState.WAITING_FOR_APPROVAL,
                    1,
                    OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))));
    when(versionService.loadGraph(TENANT, VERSION)).thenReturn(null);

    assertThatThrownBy(() -> service.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(approvalRepository, never())
        .recordApprove(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verifyNoInteractions(transitionService);
    verifyNoInteractions(outboxService);
  }

  @Test
  void a_repeat_approve_makes_no_progress_and_publishes_nothing() {
    ApprovalRow decided = decidedRow(pendingRow(null), "first call");
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(decided));

    service.approve(TENANT, ACTOR, RUN, APPROVAL, "second call", null, null);

    verifyNoInteractions(transitionService);
    verifyNoInteractions(outboxService);
  }

  @Test
  void reject_records_the_decision_fails_the_run_and_publishes_the_refusal() {
    ApprovalRow pending = pendingRow(null);
    ApprovalRow decided = rejectedRow(pending, "too risky");
    when(approvalRepository.find(TENANT, RUN, APPROVAL))
        .thenReturn(Optional.of(pending))
        .thenReturn(Optional.of(decided));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));

    ApprovalDecisionDto dto = service.reject(TENANT, ACTOR, RUN, APPROVAL, "too risky", null, null);

    assertThat(dto.status()).isEqualTo(ApprovalStatus.REJECTED);
    verify(approvalRepository)
        .recordReject(
            eq(TENANT),
            eq(RUN),
            eq(APPROVAL),
            eq(ACTOR),
            eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)),
            eq("too risky"),
            eq(null),
            eq(null),
            eq(0));
    verify(auditService)
        .record(
            eq(TENANT),
            eq(ACTOR),
            eq(AuditAction.REJECT),
            eq(AuditResourceType.APPROVAL),
            eq(APPROVAL),
            eq("step=" + STEP));
    // The refusal (S11-05): the run and the gated step fail WITHOUT the side effect ever
    // executing, and the refusal — not a continuation — is what the outbox carries.
    verify(transitionService)
        .transitionRun(
            eq(TENANT), eq(RUN), eq(WorkflowRunState.FAILED), eq("run.rejected"), eq(ACTOR));
    verify(transitionService)
        .transitionStep(
            eq(TENANT),
            eq(RUN),
            eq(STEP),
            eq(WorkflowStepState.FAILED),
            eq("run.rejected"),
            eq(ACTOR));
    ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxService).enqueue(event.capture());
    assertThat(event.getValue().topic()).isEqualTo("agentflow.workflow.run-rejected.v1");
    assertThat(event.getValue().payloadJson()).contains("\"cause\":\"REJECTED\"");
  }

  @Test
  void a_repeat_reject_replays_the_recorded_decision_without_a_second_write() {
    ApprovalRow decided = rejectedRow(pendingRow(null), "first call");
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(decided));

    ApprovalDecisionDto replay =
        service.reject(TENANT, ACTOR, RUN, APPROVAL, "second call", null, null);

    assertThat(replay.decisionComment()).isEqualTo("first call");
    verify(approvalRepository, never())
        .recordReject(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    verifyNoInteractions(transitionService);
    verifyNoInteractions(outboxService);
  }

  @Test
  void an_approval_decided_approve_cannot_be_rejected() {
    ApprovalRow approved = decidedRow(pendingRow(null), "yes");
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(approved));

    assertThatThrownBy(() -> service.reject(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(approvalRepository, never())
        .recordReject(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verifyNoInteractions(transitionService);
    verifyNoInteractions(outboxService);
  }

  @Test
  void an_expired_approval_cannot_be_rejected() {
    ApprovalRow expired =
        pendingRowWithExpiry(null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusSeconds(1));
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(expired));

    assertThatThrownBy(() -> service.reject(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(approvalRepository, never())
        .recordReject(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verifyNoInteractions(transitionService);
    verifyNoInteractions(outboxService);
  }

  @Test
  void a_keyed_reject_repeat_replays_without_even_reading_the_targeted_approval() {
    UUID key = UUID.randomUUID();
    String fingerprint = "fingerprint-of-the-original-payload";
    ApprovalRow decided = rowWithKey(rejectedRow(pendingRow(key), null), key, fingerprint);
    when(approvalRepository.findByIdempotencyKey(TENANT, key)).thenReturn(Optional.of(decided));

    ApprovalDecisionDto replay =
        service.reject(TENANT, ACTOR, RUN, APPROVAL, null, key, fingerprint);

    assertThat(replay.status()).isEqualTo(ApprovalStatus.REJECTED);
    verify(approvalRepository, never()).find(any(), any(), any());
    verify(approvalRepository, never())
        .recordReject(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verifyNoInteractions(transitionService);
    verifyNoInteractions(outboxService);
  }

  @Test
  void a_lost_reject_race_replays_the_winning_reject_decision() {
    when(approvalRepository.find(TENANT, RUN, APPROVAL))
        .thenReturn(Optional.of(pendingRow(null)))
        .thenReturn(Optional.of(rejectedRow(pendingRow(null), "concurrent winner")));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(
                    UUID.randomUUID().toString(), WorkflowStepState.WAITING_FOR_APPROVAL, 0)));
    doThrow(new ConflictException("another decision won the race"))
        .when(approvalRepository)
        .recordReject(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());

    ApprovalDecisionDto replay = service.reject(TENANT, ACTOR, RUN, APPROVAL, null, null, null);

    assertThat(replay.status()).isEqualTo(ApprovalStatus.REJECTED);
    assertThat(replay.decisionComment()).isEqualTo("concurrent winner");
    verifyNoInteractions(transitionService);
    verifyNoInteractions(outboxService);
  }

  @Test
  void a_reject_whose_step_moved_on_is_a_409_without_side_effects() {
    when(approvalRepository.find(TENANT, RUN, APPROVAL)).thenReturn(Optional.of(pendingRow(null)));
    when(stepRepository.findState(TENANT, RUN, STEP))
        .thenReturn(
            Optional.of(
                new StepStateSnapshot(UUID.randomUUID().toString(), WorkflowStepState.RUNNING, 4)));

    assertThatThrownBy(() -> service.reject(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    verify(approvalRepository, never())
        .recordReject(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    verifyNoInteractions(transitionService);
    verifyNoInteractions(outboxService);
  }

  @Test
  void expire_overdue_refuses_the_side_effect_under_the_system_actor() {
    OverdueApprovalRow overdue = new OverdueApprovalRow(APPROVAL, TENANT, RUN, STEP, 0);

    assertThat(service.expireOverdue(overdue)).isTrue();

    verify(approvalRepository)
        .expirePending(
            eq(TENANT),
            eq(RUN),
            eq(APPROVAL),
            eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)),
            eq(0));
    // An expiry is never a human decision: the audit row names the system actor and the EXPIRE
    // verb, and the refusal event carries the EXPIRED cause.
    verify(auditService)
        .record(
            eq(TENANT),
            eq("agentflow-approval-expiry"),
            eq(AuditAction.EXPIRE),
            eq(AuditResourceType.APPROVAL),
            eq(APPROVAL),
            eq("step=" + STEP));
    verify(transitionService)
        .transitionRun(
            eq(TENANT),
            eq(RUN),
            eq(WorkflowRunState.FAILED),
            eq("run.rejected"),
            eq("agentflow-approval-expiry"));
    verify(transitionService)
        .transitionStep(
            eq(TENANT),
            eq(RUN),
            eq(STEP),
            eq(WorkflowStepState.FAILED),
            eq("run.rejected"),
            eq("agentflow-approval-expiry"));
    ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxService).enqueue(event.capture());
    assertThat(event.getValue().topic()).isEqualTo("agentflow.workflow.run-rejected.v1");
    assertThat(event.getValue().payloadJson()).contains("\"cause\":\"EXPIRED\"");
  }

  @Test
  void a_lost_expire_race_leaves_the_row_to_the_winner() {
    OverdueApprovalRow overdue = new OverdueApprovalRow(APPROVAL, TENANT, RUN, STEP, 0);
    doThrow(new ConflictException("a human decision won the race"))
        .when(approvalRepository)
        .expirePending(any(), any(), any(), any(), anyInt());

    assertThat(service.expireOverdue(overdue)).isFalse();

    verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    verifyNoInteractions(transitionService);
    verifyNoInteractions(outboxService);
  }

  private static ApprovalRow pendingRow(UUID idempotencyKey) {
    return pendingRowWithExpiry(idempotencyKey, null);
  }

  private static ApprovalRow pendingRowWithExpiry(UUID idempotencyKey, OffsetDateTime expiry) {
    return new ApprovalRow(
        APPROVAL,
        RUN,
        STEP,
        PAYLOAD_FINGERPRINT,
        ApprovalStatus.PENDING.name(),
        expiry,
        0,
        null,
        null,
        null,
        idempotencyKey,
        null);
  }

  private static ApprovalRow decidedRow(ApprovalRow from, String comment) {
    return new ApprovalRow(
        from.id(),
        from.runId(),
        from.stepKey(),
        from.payloadFingerprint(),
        ApprovalStatus.APPROVED.name(),
        from.expiry(),
        from.version() + 1,
        ACTOR,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        comment,
        from.idempotencyKey(),
        from.idempotencyFingerprint());
  }

  private static ApprovalRow rejectedRow(ApprovalRow from, String comment) {
    return new ApprovalRow(
        from.id(),
        from.runId(),
        from.stepKey(),
        from.payloadFingerprint(),
        ApprovalStatus.REJECTED.name(),
        from.expiry(),
        from.version() + 1,
        ACTOR,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        comment,
        from.idempotencyKey(),
        from.idempotencyFingerprint());
  }

  private static ApprovalRow rowWithKey(ApprovalRow from, UUID key, String fingerprint) {
    return new ApprovalRow(
        from.id(),
        from.runId(),
        from.stepKey(),
        from.payloadFingerprint(),
        from.status(),
        from.expiry(),
        from.version(),
        from.decisionActor(),
        from.decisionAt(),
        from.decisionComment(),
        key,
        fingerprint);
  }

  /**
   * The S11-06 pinned-graph stubs every approve decision consults: the run names its pinned
   * workflow version, the version resolves to the graph holding the gated step with the exact
   * payload the approval fingerprinted.
   */
  private void stubPinnedGraph() {
    when(runRepository.findRun(TENANT, RUN))
        .thenReturn(
            Optional.of(
                new RunView(
                    RUN,
                    VERSION,
                    WorkflowRunState.WAITING_FOR_APPROVAL,
                    1,
                    OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))));
    when(versionService.loadGraph(TENANT, VERSION))
        .thenReturn(
            new WorkflowGraph(
                List.of(new WorkflowNode(STEP, "HUMAN_APPROVAL", GATE_CONFIG)), List.of()));
  }
}
