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
import com.example.agentflow.workflowexecution.domain.ApprovalStatus;
import com.example.agentflow.workflowexecution.domain.ConflictException;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import com.example.agentflow.workflowexecution.persistence.ApprovalRepository;
import com.example.agentflow.workflowexecution.persistence.ApprovalRow;
import com.example.agentflow.workflowexecution.persistence.StepStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * DB-free tests for the approve half of the approval lifecycle ({@link ApprovalService}, S11-04).
 * The repositories and the audit service are Mockito mocks, so every guard — tenant (404),
 * status/expiry/step (409), the conditional write, the atomic audit append and both idempotent
 * replay mechanisms — is asserted directly; the real conditional update runs against a real
 * PostgreSQL row in the integration suite.
 */
class ApprovalServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID RUN = UUID.randomUUID();
  private static final UUID APPROVAL = UUID.randomUUID();
  private static final String STEP = "send-monthly-report";
  private static final String ACTOR = "approver@acme.example";

  private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
  private final WorkflowStepRunRepository stepRepository = mock(WorkflowStepRunRepository.class);
  private final AuditService auditService = mock(AuditService.class);

  private final ApprovalService service =
      new ApprovalService(approvalRepository, stepRepository, auditService, FIXED_CLOCK);

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
    ApprovalService noDb = new ApprovalService(null, null, auditService, FIXED_CLOCK);

    assertThatThrownBy(() -> noDb.approve(TENANT, ACTOR, RUN, APPROVAL, null, null, null))
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

  private static ApprovalRow pendingRow(UUID idempotencyKey) {
    return pendingRowWithExpiry(idempotencyKey, null);
  }

  private static ApprovalRow pendingRowWithExpiry(UUID idempotencyKey, OffsetDateTime expiry) {
    return new ApprovalRow(
        APPROVAL,
        RUN,
        STEP,
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
        ApprovalStatus.APPROVED.name(),
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
        from.status(),
        from.expiry(),
        from.version(),
        from.decisionActor(),
        from.decisionAt(),
        from.decisionComment(),
        key,
        fingerprint);
  }
}
